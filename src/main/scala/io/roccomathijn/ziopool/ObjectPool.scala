package io.roccomathijn.ziopool

import zio.ZManaged

trait ObjectPool[-R, +E, +A] {

  val borrowObject: ZManaged[R, E, A]

}
