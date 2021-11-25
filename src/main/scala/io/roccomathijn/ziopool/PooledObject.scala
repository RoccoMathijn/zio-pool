package io.roccomathijn.ziopool

import zio.URIO
import zio.stm.TRef

import java.time.Instant

trait PooledObject[-R, +A] {

  val underlying: A

  val destroy: URIO[R, Unit]

  val validateObject: URIO[R, Boolean]

  val lastUsed: TRef[Instant]

}
