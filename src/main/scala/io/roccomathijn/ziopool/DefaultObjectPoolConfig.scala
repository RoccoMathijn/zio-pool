package io.roccomathijn.ziopool

import zio.duration.Duration

case class DefaultObjectPoolConfig(
    poolSize: Int,
    maxIdle: Int,
    idleTimeout: Duration,
    timeBetweenEvictionRuns: Duration,
    maxWaitDuration: Option[Duration],
    queueSize: Int
)
