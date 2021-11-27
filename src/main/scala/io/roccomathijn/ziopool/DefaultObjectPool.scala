package io.roccomathijn.ziopool

import zio.blocking.Blocking
import zio.clock.Clock
import zio.duration.Duration
import zio.stm.{TPromise, TQueue, TRef, ZSTM}
import zio.{RIO, Schedule, URIO, ZIO, ZManaged}

import java.time.Instant
import java.util.NoSuchElementException

class DefaultObjectPool[R <: Clock with Blocking, A](
    config: DefaultObjectPoolConfig,
    acquirePooledObject: RIO[R, PooledObject[R, A]],
    idleObjects: TQueue[PooledObject[R, A]],
    objectCounter: TRef[Int],
    queue: TQueue[TPromise[Throwable, PooledObject[R, A]]],
    clock: Clock.Service,
    blocking: Blocking.Service
) extends ObjectPool[R, Throwable, A] {

  override val borrowObject: ZManaged[R, Throwable, A] = {
    def isValid(idleObject: PooledObject[R, A]): ZIO[R, Nothing, Boolean] =
      for {
        valid <- idleObject.validateObject
        _ <- destroy(idleObject).when(!valid)
      } yield valid

    val getFirstValidIdleObject: ZIO[R, NoSuchElementException, PooledObject[R, A]] =
      idleObjects.poll.commit
        .someOrFail(new NoSuchElementException("No idle objects!"))
        .repeatUntilM(isValid)

    val createNewObject: RIO[R, PooledObject[R, A]] = {
      val shouldCreateNew: ZIO[Any, Nothing, Boolean] =
        objectCounter.getAndUpdate(_ + 1).map(_ < config.poolSize).commit

      ZIO.uninterruptible {
        (for {
          create <- shouldCreateNew
          newObject <- if (create) acquirePooledObject else ZIO.fail(new NoSuchElementException("Pool is exhausted!"))
        } yield newObject).onError(_ => objectCounter.getAndUpdate(_ - 1).commit)
      }
    }

    def waitForObject(maxWaitDuration: Duration): ZIO[R, Throwable, PooledObject[R, A]] =
      for {
        p <- TPromise.make[Throwable, PooledObject[R, A]].commit
        _ <- ZSTM.ifM(queue.isFull)(onTrue = ZSTM.fail(new RuntimeException("Max queue size reached")), onFalse = queue.offer(p)).commit
        obj <- blocking.blocking( // Not sure if it's necessary to run this on the blocking thread pool
          p.await.commit
            .timeoutFail(new NoSuchElementException("Pool is exhausted! Max wait time reached"))(maxWaitDuration)
            .onError(cause => p.fail(cause.squash).commit)
        )
        validatedObject <- ZIO.ifM(isValid(obj))(ZIO.succeed(obj), ZIO.fail(new RuntimeException("Object invalid")))
      } yield validatedObject

    val getIdleCreateOrWait: ZIO[R, Throwable, PooledObject[R, A]] =
      getFirstValidIdleObject
        .orElse(createNewObject)
        .catchSome {
          case e: NoSuchElementException =>
            for {
              maxWaitDuraiton <- ZIO.fromOption(config.maxWaitDuration).orElseFail(e)
              obj <- waitForObject(maxWaitDuraiton)
            } yield obj
        }

    ZManaged
      .make(acquire = getIdleCreateOrWait)(release = returnObject)
      .map(_.underlying)
  }

  private def returnObject(pooledObject: PooledObject[R, A]): URIO[R, Unit] = {
    val releaseObject =
      ZSTM
        .ifM(idleObjects.isFull)(
          onTrue = ZSTM.fail(()),
          onFalse = ZSTM.ifM(queue.isEmpty)(
            onTrue = idleObjects.offer(pooledObject),
            // TODO handle the case that a promise may already be failed due to a max wait timeout being reached
            onFalse = queue.take.flatMap(_.succeed(pooledObject))
          )
        )
        .commit

    ZIO.uninterruptible {
      for {
        now <- clock.instant
        _ <- pooledObject.lastUsed.set(now).commit
        _ <- releaseObject.orElse(destroy(pooledObject))
      } yield ()
    }
  }

  private def destroy(pooledObject: PooledObject[R, A]): ZIO[R, Nothing, Unit] =
    ZIO
      .uninterruptible(pooledObject.destroy)
      .ensuring(objectCounter.update(_ - 1).commit)
      .fork // life's too short to wait for object to be destroyed
      .unit

  private val close: RIO[R, Unit] =
    ZIO.uninterruptible {
      for {
        idleObjects <- idleObjects.takeAll.commit
        _ <- ZIO.foreach_(idleObjects)(destroy)
      } yield ()
    }

  private val evictor: ZIO[R with Clock, Nothing, Unit] = {
    def pullEvictable(currentTime: Instant): ZSTM[Any, String, PooledObject[R, A]] =
      for {
        obj <- idleObjects.peekOption.someOrFail("Nothing to evict. Queue is empty")
        lastUsed <- obj.lastUsed.get
        evictableObject <-
          if (Duration.fromInterval(lastUsed, currentTime).getSeconds > config.idleTimeout.getSeconds)
            idleObjects.take
          else
            ZSTM.fail("Nothing to evict. Objects are young enough")
      } yield evictableObject

    val evict: ZIO[R, String, Boolean] = {
      for {
        currentTime <- clock.instant
        obj <- pullEvictable(currentTime).commit
        _ <- destroy(obj)
      } yield true
    }

    evict
      .repeatUntil(_ == false)
      .option
      .repeat(Schedule.spaced(config.timeBetweenEvictionRuns))
      .unit
      .delay(config.timeBetweenEvictionRuns)
  }
}

object DefaultObjectPool {
  def make[R <: Clock with Blocking, A](
      config: DefaultObjectPoolConfig,
      acquirePooledObject: RIO[R, PooledObject[R, A]]
  ): ZManaged[R with Blocking, Nothing, DefaultObjectPool[R, A]] =
    for {
      clock <- ZManaged.service[Clock.Service]
      blocking <- ZManaged.service[Blocking.Service]
      idleObjects <- TQueue.bounded[PooledObject[R, A]](config.maxIdle).commit.toManaged_
      objectCount <- TRef.make[Int](0).commit.toManaged_
      queue <- TQueue.bounded[TPromise[Throwable, PooledObject[R, A]]](config.queueSize).commit.toManaged_
      pool = new DefaultObjectPool(
        config = config,
        acquirePooledObject = acquirePooledObject,
        idleObjects = idleObjects,
        objectCounter = objectCount,
        queue = queue,
        clock = clock,
        blocking = blocking
      )
      _ <- pool.evictor.forkManaged
      _ <- ZManaged.finalizer(pool.close.orDie)
    } yield pool
}
