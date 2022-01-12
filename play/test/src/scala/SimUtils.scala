package rdma

import spinal.core._
import spinal.core.sim._

object SimUtils {
  def randomizeSignalAndWaitUntilTrue(signal: Bool, clockDomain: ClockDomain) =
    new Area {
      while (!signal.toBoolean) {
        clockDomain.waitSampling()
        signal.randomize()
        sleep(0) // To make signal random assignment take effect
      }
    }
}
