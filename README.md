# ZIO Pool

This project is still a work in progress but it came to my attention that similar work is being done for ZIO 2 with ZPool. So I'm abandoning this.

ZIO Pool is an ZIO ZManaged powered object pooling library inspired by Apache Commons Pool and ZIO SQL.

## Features

- Object pooling for objects which are expensive to acquire
- Either block (for a specified time) or error when the pool is exhausted
- Destroy objects which have reached the idle-timout

## How to use

```scala
val config = DefaultObjectPoolConfig(
  poolSize = 1,
  maxIdle = 1,
  idleTimeout = Duration.fromMillis(5000),
  timeBetweenEvictionRuns = Duration.fromMillis(10000),
  maxWaitDuration = Some(Duration.fromMillis(100000)),
  queueSize = 10
)

val acquirePooledObject: RIO[R, PooledObject[R, A]] = ??? // See examples on how to create a pooled object

DefaultObjectPool.make(DefaultObjectPool.make(config, acquirePooledObject)).use { pool =>
  
  pool.borrowObject.use { obj: A =>
    // borrowed objects will be released back to the pool after use automatically
  }
}
```


