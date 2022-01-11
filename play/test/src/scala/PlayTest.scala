package rdma

import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
import ConstantSettings._

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
          dut.io.outputStream.ready #= true // ((idx % 2) == 0)
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

    dut.io.header #= setAllBits(headerWidth)
    dut.io.headerMty #= setAllBits(headerWidth / BYTE_WIDTH)
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

  val simCfg = SimConfig.withWave
    .compile(new StreamAddHeaderWrapper(width, headerWidth))

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
