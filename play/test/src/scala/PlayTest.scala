package rdma

import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
import ConstantSettings._
import SimUtils._

class StreamSegmentTest extends AnyFunSuite {
  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new StreamSegmentWrapper(width = 16))

  test("StreamSegment test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      dut.io.fragmentNum #= 10
      dut.io.inputStream.valid #= false
      dut.io.outputStream.ready #= false
      dut.clockDomain.waitSampling(3)

      fork {
        for (idx <- 1 to 100) {
          dut.io.inputStream.valid #= true
          dut.io.inputStream.fragment #= idx
          dut.io.inputStream.last #= false
          dut.clockDomain.waitSamplingWhere(
            dut.io.inputStream.valid.toBoolean && dut.io.inputStream.ready.toBoolean
          )
        }
      }
      fork {
        for (idx <- 1 to 100) {
          randomizeSignalAndWaitUntilTrue(
            dut.io.outputStream.ready,
            dut.clockDomain
          )
          dut.clockDomain.waitSamplingWhere(
            dut.io.outputStream.valid.toBoolean && dut.io.outputStream.ready.toBoolean
          )
          println(
            s"io.outputStream.fragment=${dut.io.outputStream.fragment.toInt} == idx=${idx}"
          )
          assert(dut.io.outputStream.fragment.toInt == idx)
        }
      }
      dut.clockDomain.waitSampling(100)
      simSuccess()
    }
  }
}

class StreamAddHeaderTest extends AnyFunSuite {
  val width = 32
  val headerWidth = 16
  val widthBytes = width / 8
  val headerWidthBytes = headerWidth / 8

  def testFunc(dut: StreamAddHeaderWrapper, lastFragMtyValidWidth: Int) = {
    dut.clockDomain.forkStimulus(10)

    require(
      0 < lastFragMtyValidWidth && lastFragMtyValidWidth <= widthBytes,
      s"must have 0 < lastFragMtyValidWidth=${lastFragMtyValidWidth} <= widthBytes=${widthBytes}"
    )
    val lastFragLeftShiftByteAmt = if (lastFragMtyValidWidth < widthBytes) {
      widthBytes - lastFragMtyValidWidth
    } else 0
    val fragmentNum = 10

    // TODO: input varying headers
    dut.io.inputHeader.valid #= true
    dut.io.inputHeader.data #= setAllBits(headerWidth)
    dut.io.inputHeader.mty #= setAllBits(headerWidth / BYTE_WIDTH)
//    println(f"""dut.io.inputHeader.data #= ${setAllBits(headerWidth)}%X
//         |dut.io.inputHeader.mty #= ${setAllBits(headerWidth / BYTE_WIDTH)}%X
//         |CountOne(dut.io.inputHeader.mty) #= ${dut.countMtyOne.toInt}
//         |""".stripMargin)

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

    val leftShiftByteAmt = widthBytes - headerWidthBytes
    val leftShiftBitAmt = leftShiftByteAmt * 8
    val hasExtraLastFrag = lastFragMtyValidWidth + headerWidthBytes > widthBytes
    val lastFragOutputShiftByteAmt = if (hasExtraLastFrag) {
      2 * widthBytes - lastFragMtyValidWidth - headerWidthBytes
    } else {
      widthBytes - lastFragMtyValidWidth - headerWidthBytes
    }
    val lastFragOutputShiftBitAmt = lastFragOutputShiftByteAmt * 8
    fork {
      dut.clockDomain.waitSampling(waitCycle)
      for (idx <- 0 to 5 * fragmentNum) {
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
        } else if (idx % fragmentNum != 0 && idx < fragmentNum) {
          assert(dut.io.outputStream.data.toLong == (idx << leftShiftBitAmt))
        }
      }
    }
    dut.clockDomain.waitSampling(100)
    simSuccess()
  }

  val simCfg = SimConfig.withWave.compile(new StreamAddHeaderWrapper(width))
//    .compile {
//      val dut = new StreamAddHeaderWrapper(width)
//      dut.countMtyOne.simPublic()
//      dut
//    }

  test("StreamAddHeader test extra last fragment") {
    val lastFragMtyValidWidth = widthBytes - headerWidthBytes + 1

    simCfg.doSim(dut => testFunc(dut, lastFragMtyValidWidth))
  }

  test("StreamAddHeader test no extra last fragment") {
    val lastFragMtyValidWidth = widthBytes - headerWidthBytes - 1

    simCfg.doSim(dut => testFunc(dut, lastFragMtyValidWidth))
  }
}

class StreamRemoveHeaderTest extends AnyFunSuite {
  val width = 32
  val headerWidth = 16
  val widthBytes = width / 8
  val headerWidthBytes = headerWidth / 8

  def testFunc(dut: StreamRemoveHeaderWrapper, lastFragMtyValidWidth: Int) = {
    dut.clockDomain.forkStimulus(10)

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
        dut.io.outputStream.ready #= true // .randomize() //  #= ((idx % 2) == 0)
        dut.clockDomain.waitSampling()
      }
    }

    val leftShiftByteAmt = widthBytes - headerWidthBytes
    val leftShiftBitAmt = leftShiftByteAmt * 8
    val hasExtraLastFrag = lastFragMtyValidWidth + headerWidthBytes > widthBytes
    val lastFragOutputShiftByteAmt = if (hasExtraLastFrag) {
      2 * widthBytes - lastFragMtyValidWidth - headerWidthBytes
    } else {
      widthBytes - lastFragMtyValidWidth - headerWidthBytes
    }
    val lastFragOutputShiftBitAmt = lastFragOutputShiftByteAmt * 8
    fork {
      dut.clockDomain.waitSampling(waitCycle)
      for (idx <- 1 to 6 * fragmentNum) {
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
        } else if (idx % fragmentNum != 0 && idx < fragmentNum) {
          assert(dut.io.outputStream.data.toLong == (idx << leftShiftBitAmt))
        }
      }
    }
    dut.clockDomain.waitSampling(100)
    simSuccess()
  }

  val simCfg = SimConfig.withWave
    .compile(new StreamRemoveHeaderWrapper(width))

  test("StreamRemoveHeader test extra last fragment") {
    val lastFragMtyValidWidth = widthBytes - headerWidthBytes + 1

    simCfg.doSim(dut => testFunc(dut, lastFragMtyValidWidth))
  }

  test("StreamRemoveHeader test no extra last fragment") {
    val lastFragMtyValidWidth = widthBytes - headerWidthBytes - 1

    simCfg.doSim(dut => testFunc(dut, lastFragMtyValidWidth))
  }
}

class FragmentStreamJoinStreamTest extends AnyFunSuite {
  val width = 16

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new FragmentStreamJoinStreamWrapper(width))

  test("FragmentStreamJoinStream test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val fragNum = 10

      dut.io.inputFragmentStream.valid #= false
      dut.io.inputStream.valid #= false
      dut.io.outputJoinStream.ready #= false
      dut.clockDomain.waitSampling(3)

      fork {
        for (idx <- 1 to 100) {
          randomizeSignalAndWaitUntilTrue(
            dut.io.inputFragmentStream.valid,
            dut.clockDomain
          )
          dut.io.inputFragmentStream.fragment #= idx
          dut.io.inputFragmentStream.last #= (idx % fragNum == 0)
          dut.clockDomain.waitSamplingWhere(
            dut.io.inputFragmentStream.valid.toBoolean && dut.io.inputFragmentStream.ready.toBoolean
          )
        }
      }

      fork {
        for (idx <- 1 to 10) {
          randomizeSignalAndWaitUntilTrue(
            dut.io.inputStream.valid,
            dut.clockDomain
          )
          dut.io.inputStream.payload #= (idx << (width / 2))
          dut.clockDomain.waitSamplingWhere(
            dut.io.inputStream.valid.toBoolean && dut.io.inputStream.ready.toBoolean
          )
        }
      }

      fork {
        for (idx <- 1 to 100) {
          randomizeSignalAndWaitUntilTrue(
            dut.io.outputJoinStream.ready,
            dut.clockDomain
          )
          dut.clockDomain.waitSamplingWhere(
            dut.io.outputJoinStream.valid.toBoolean && dut.io.outputJoinStream.ready.toBoolean
          )
          val outputVal = dut.io.outputJoinStream.fragment.toInt
          val outputValLowerHalf = outputVal & setAllBits(width / 2)
          val outputValHigherHalf =
            outputVal & (setAllBits(width / 2) << (width / 2))
          println(f"""io.outputJoinStream.fragment=${outputVal}%X,
                |  outputValLowerHalf=${outputValLowerHalf}%X,
                |  outputValHigherHalf=${outputValHigherHalf}%X,
                |  idx=${idx}=0x${idx}%X""".stripMargin)
          assert(outputValLowerHalf == idx)
          assert(
            outputValHigherHalf == (((idx - 1) / fragNum) + 1) << (width / 2)
          )
        }
      }
      dut.clockDomain.waitSampling(100)
      simSuccess()
    }
  }
}

class SignalEdgeDrivenStreamWrapperTest extends AnyFunSuite {
  val width = 8

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new SignalEdgeDrivenStreamWrapper(width))

  test("SignalEdgeDrivenStream test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val fragNum = 10

      dut.io.inputFragmentStream.valid #= false
      dut.io.outputStream.ready #= false
      dut.clockDomain.waitSampling(3)

      fork {
        for (idx <- 1 to 1000) {
          randomizeSignalAndWaitUntilTrue(
            dut.io.inputFragmentStream.valid,
            dut.clockDomain
          )
          // dut.io.inputFragmentStream.valid #= true
          dut.io.inputFragmentStream.fragment #= idx
          dut.io.inputFragmentStream.last #= (idx % fragNum == 0)
          dut.clockDomain.waitSamplingWhere(
            dut.io.inputFragmentStream.valid.toBoolean && dut.io.inputFragmentStream.ready.toBoolean
          )
        }
      }

      fork {
        while (true) {
          dut.clockDomain.waitSampling()
          dut.io.outputStream.ready.randomize()
//          if (dut.io.outputStream.valid.toBoolean) {
//            dut.io.outputStream.ready.randomize()
//          } else {
//            dut.io.outputStream.ready #= false
//          }
//          randomizeSignalAndWaitUntilTrue(
//            dut.io.outputStream.ready,
//            dut.clockDomain
//          )
//          dut.clockDomain.waitSamplingWhere(
//            dut.io.outputStream.valid.toBoolean && dut.io.outputStream.ready.toBoolean
//          )
        }
      }
      fork {
        for (idx <- 0 to 100) {
          dut.clockDomain.waitSamplingWhere(
            dut.io.outputStream.valid.toBoolean && dut.io.outputStream.ready.toBoolean
          )
          println(
            f"io.outputStream.payload=${dut.io.outputStream.payload.toInt} @ idx=${idx}"
          )
          assert(dut.io.outputStream.payload.toInt == (idx * fragNum + 1))
        }
      }

      dut.clockDomain.waitSampling(300)
      simSuccess()
    }
  }
}

/*
class MergeDemuxStreamsTest extends AnyFunSuite {
  val width = 32

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new MergeDemuxStreamsWrapper(width))

  test("MergeDemuxStreams test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val widthBytes = width / BYTE_WIDTH

      dut.io.fragmentNum #= 5
      dut.io.inputStream.valid #= false
      dut.io.outputStream.ready #= false
      dut.clockDomain.waitSampling(3)

      fork {
        for (idx <- 1 to 100) {
          dut.io.inputStream.valid #= true
          dut.io.inputStream.data #= idx
          dut.io.inputStream.mty #= setAllBits(widthBytes)
          dut.io.inputStream.last #= false
          dut.clockDomain.waitSamplingWhere(
            dut.io.inputStream.valid.toBoolean && dut.io.inputStream.ready.toBoolean
          )
        }
      }
      fork {
        for (idx <- 1 to 100) {
          dut.io.outputStream.ready #= true
          dut.clockDomain.waitSamplingWhere(
            dut.io.outputStream.valid.toBoolean && dut.io.outputStream.ready.toBoolean
          )
          println(
            f"io.outputStream.data=${dut.io.outputStream.data.toLong}%X == idx=${idx}"
          )
          // assert(dut.io.outputStream.data.toLong == idx)
        }
      }
      dut.clockDomain.waitSampling(100)
      simSuccess()
    }
  }
}
 */
