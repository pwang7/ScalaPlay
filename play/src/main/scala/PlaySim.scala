package rdma

import spinal.core.sim._
import ConstantSettings._

object PlaySim extends App {
  val width = 32
  val headerWidth = 16
  val widthBytes = width / BYTE_WIDTH
  val headerWidthBytes = headerWidth / BYTE_WIDTH

  SimConfig.allOptimisation.withWave
    .compile(new StreamRemoveHeaderWrapper(width))
    .doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val lastFragMtyValidWidth = widthBytes - 3
      val lastFragLeftShiftByteAmt = widthBytes - lastFragMtyValidWidth
      val fragmentNum = 10

      dut.io.headerLenBytes #= headerWidthBytes
      dut.io.inputStream.valid #= false
      dut.io.outputStream.ready #= false
      dut.clockDomain.waitSampling(3)

      fork {
        for (idx <- 1 to 100) {
          dut.io.inputStream.valid #= true
          if (idx % fragmentNum == 0) {
            dut.io.inputStream.data #= (idx << (lastFragLeftShiftByteAmt * 8))
            dut.io.inputStream.mty #= setAllBits(widthBytes) - setAllBits(
              widthBytes - lastFragMtyValidWidth
            )
            dut.io.inputStream.last #= true
          } else {
            dut.io.inputStream.data #= idx
            dut.io.inputStream.mty #= setAllBits(widthBytes)
            dut.io.inputStream.last #= false
          }
          dut.clockDomain.waitSamplingWhere(
            dut.io.inputStream.valid.toBoolean && dut.io.inputStream.ready.toBoolean
          )
        }
      }

      val waitCycle = 2
      fork {
        dut.clockDomain.waitSampling(waitCycle)
        for (_ <- 0 to 100) {
          dut.io.outputStream.ready.randomize() //  #= ((idx % 2) == 0) // true
          dut.clockDomain.waitSampling()
        }
      }

//      fork {
//        dut.clockDomain.waitSampling(waitCycle)
//        for (idx <- 1 to 100) {
//          dut.clockDomain.waitSamplingWhere(
//            dut.io.outputStream.valid.toBoolean && dut.io.outputStream.ready.toBoolean
//          )
//          println(
//            f"dut.io.outputStream.data=${dut.io.outputStream.data.toBigInt}%X @ idx=${idx}%X"
//          )
//        }
//      }
      val leftShiftByteAmt = widthBytes - headerWidthBytes
      val leftShiftBitAmt = leftShiftByteAmt * 8
      val hasExtraLastFrag =
        lastFragMtyValidWidth + headerWidthBytes > widthBytes
      val lastFragOutputShiftByteAmt = if (hasExtraLastFrag) {
        2 * widthBytes - lastFragMtyValidWidth - headerWidthBytes
      } else {
        widthBytes - lastFragMtyValidWidth - headerWidthBytes
      }
      val lastFragOutputShiftBitAmt = lastFragOutputShiftByteAmt * 8
      fork {
        dut.clockDomain.waitSampling(waitCycle)
        for (idx <- 1 to fragmentNum) {
          dut.clockDomain.waitSamplingWhere(
            dut.io.outputStream.valid.toBoolean && dut.io.outputStream.ready.toBoolean
          )
          println(
            f"dut.io.outputStream.data=${dut.io.outputStream.data.toBigInt}%X @ idx=${idx}%X"
          )
          if (idx == fragmentNum && hasExtraLastFrag) {
            assert(
              dut.io.outputStream.data.toLong == (idx << lastFragOutputShiftBitAmt)
            )
          } else if (idx == fragmentNum - 1 && !hasExtraLastFrag) {
            assert(
              dut.io.outputStream.data.toLong == ((idx << leftShiftBitAmt)) + ((idx + 1) << lastFragOutputShiftBitAmt)
            )
          } else if (idx % fragmentNum != 0) {
            assert(dut.io.outputStream.data.toLong == (idx << leftShiftBitAmt))
          }
        }
      }
      dut.clockDomain.waitSampling(100)
      simSuccess()
    }
}
