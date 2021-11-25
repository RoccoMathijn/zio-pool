package io.roccomathijn.ziopool

import zio.clock.Clock
import zio.duration.{Duration, durationInt}
import zio.stm.TRef
import zio.test.Assertion.equalTo
import zio.test._
import zio.test.environment.TestClock
import zio.{Ref, UIO, URIO, ZIO}

import java.time.Instant

object DefaultObjectPoolSpec extends DefaultRunnableSpec {
  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, TestFailure[String]] =
    /**
      * [X] Test if an object is destroyed after use
      * [X] Test if an object is released after use
      * [X] Test if an object can be served more than once
      * [X] Test if an object is evicted in an eviction run after the idle timeout is reached
      * [] Test if objects are validated before lent out
      * [] Test if an error is returned if the pool is exhausted
      * [] Test if the wait queue works when a wait duration is set
      * [] Test if the wait queue is served in order
      * [] Test if an error is returned if the wait queue is at capacity
      */
    suite("DefaultObjectPool") {
      val defaultConfig = DefaultObjectPoolConfig(1, 0, Duration.fromMillis(1000), Duration.fromMillis(30000), None, 1)
      testM("lend out an object and destroy it if the idle pool is at capacity") {
        val config = defaultConfig
        (for {
          poolState <- PoolState.empty
          pool = makeTestPool(config, poolState)
          results <-
            pool
              .use(_.borrowObject.use(_.unit) *> assertM(poolState.history.get.map(_(1).destroyed))(equalTo(true)))
        } yield results).orElseFail(TestFailure.fail("Error"))
      } +
        testM("lend out an object but don't destroy it if the idle pool has room ") {
          val config = defaultConfig.copy(maxIdle = 1)
          (for {
            poolState <- PoolState.empty
            pool = makeTestPool(config, poolState)
            results <-
              pool.use(_.borrowObject.use(_.unit) *> assertM(poolState.history.get.map(_(1).destroyed))(equalTo(false)))
          } yield results).orElseFail(TestFailure.fail("Error"))
        } +
        testM("lend out an object twice") {
          val evictorDelay = 1.minute
          val config = defaultConfig.copy(maxIdle = 1, timeBetweenEvictionRuns = evictorDelay)
          (for {
            poolState <- PoolState.empty
            pool = makeTestPool(config, poolState)
            results <- pool.use { pool =>
              for {
                _ <- pool.borrowObject.use(_.unit)
                _ <- pool.borrowObject.use(_.unit)
                usages <- poolState.history.get.map(_(1).usages)
              } yield assert(usages)(equalTo(2))
            }
          } yield results).orElseFail(TestFailure.fail("Error"))
        } +
        testM("destroy an object after the idle timeout has been reached") {
          val evictionRunDelay = 1.minute
          val config = defaultConfig.copy(maxIdle = 1, timeBetweenEvictionRuns = evictionRunDelay)
          (for {
            poolState <- PoolState.empty
            pool = makeTestPool(config, poolState)
            results <- pool.use { pool =>
              for {
                _ <- pool.borrowObject.use(_.unit)
                beforeEviction <- poolState.history.get.map(_(1).destroyed)
                _ <- TestClock.adjust(evictionRunDelay)
                afterEviction <- poolState.history.get.map(_(1).destroyed)
              } yield assert(beforeEviction -> afterEviction)(equalTo(false -> true))
            }
          } yield results).orElseFail(TestFailure.fail("Error"))
        }
    }

  private def makeTestPool(config: DefaultObjectPoolConfig, poolState: PoolState) =
    DefaultObjectPool
      .make(config, poolState.idGen.update(_ + 1) *> makePooledObject(poolState))

  case class PoolState(idGen: Ref[Int], history: Ref[Map[Int, PooledObjectState]])

  object PoolState {
    val empty: ZIO[Any, Nothing, PoolState] = Ref
      .make(0)
      .flatMap(idGen => Ref.make(Map.empty[Int, PooledObjectState]).map(history => PoolState(idGen, history)))
  }

  case class PooledObjectState(destroyed: Boolean, usages: Int)

  object PooledObjectState {
    val empty: PooledObjectState = PooledObjectState(destroyed = false, usages = 0)
  }

  def makePooledObject(poolState: PoolState): ZIO[Clock, Nothing, PooledObject[Any, UIO[Unit]]] =
    for {

      objectId <- poolState.idGen.get
      _ <- poolState.history.update(h => h.updated(objectId, PooledObjectState.empty))
      time <- ZIO.service[Clock.Service].flatMap(_.instant)
      lastUsedRef <- TRef.make(time).commit
    } yield new PooledObject[Any, UIO[Unit]] {
      override val underlying: UIO[Unit] =
        poolState.history.update(_.updatedWith(objectId)(_.map(s => s.copy(usages = s.usages + 1)))).unit
      override val destroy: URIO[Any, Unit] =
        poolState.history.update(_.updatedWith(objectId)(_.map(s => s.copy(destroyed = true)))).unit
      override val validateObject: URIO[Any, Boolean] = ZIO.succeed(true)
      override val lastUsed: TRef[Instant] = lastUsedRef
    }

}
