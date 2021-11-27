package io.roccomathijn.ziopool

import zio.duration.Duration

/**
  * @param poolSize the maximum number of objects this pool should manage at a time.
  * @param maxIdle the maximum number of objects that should be idle at a time. Objects that are released back to the pool will be destroyed when the maximum is reached.
  * @param idleTimeout the max duration for which an object should stay idle. Objects for which the idle duration has been reached will be destroyed in the next eviction run.
  * @param timeBetweenEvictionRuns this determines the frequency of eviction runs.
  * @param maxWaitDuration when this parameter is set calls to `borrowObject` will block for the specified duration if there are no objects available.
  * @param queueSize the maximum number of callers that can wait at any given time for new objects.
  */
case class DefaultObjectPoolConfig(
    poolSize: Int,
    maxIdle: Int,
    idleTimeout: Duration,
    timeBetweenEvictionRuns: Duration,
    maxWaitDuration: Option[Duration],
    queueSize: Int
)
