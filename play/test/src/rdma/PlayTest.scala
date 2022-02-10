package rdma

import spinal.core._
import spinal.core.sim._
import spinal.lib._

import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable

import ConstantSettings._
import StreamSimUtil._
import RdmaConstants._
import RdmaTypeReDef._

class StreamZipByConditionTest extends AnyFunSuite {
  val busWidth = BusWidth.W32

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new StreamZipByConditionWrapper(busWidth))

  def streamZipMasterDriver[T <: Data](
      stream: Stream[T],
      bothCond: Bool,
      singleCond: Bool,
      clockDomain: ClockDomain
  ): Unit = fork {
    stream.valid #= false
    clockDomain.waitSampling()

    while (true) {
      stream.valid.randomize()
      stream.payload.randomize()
      sleep(0)
      if (stream.valid.toBoolean) {
        clockDomain.waitSamplingWhere(
          (bothCond.toBoolean || singleCond.toBoolean) && stream.valid.toBoolean && stream.ready.toBoolean
        )
      } else {
        clockDomain.waitSampling()
      }
    }
  }

  def streamZipMasterDriverAlwaysValid[T <: Data](
      stream: Stream[T],
      bothCond: Bool,
      singleCond: Bool,
      clockDomain: ClockDomain
  ): Unit = fork {
    stream.valid #= false
    clockDomain.waitSampling()

    while (true) {
      stream.valid #= true
      stream.payload.randomize()
      sleep(0)
      if (stream.valid.toBoolean) {
        clockDomain.waitSamplingWhere(
          (bothCond.toBoolean || singleCond.toBoolean) && stream.valid.toBoolean && stream.ready.toBoolean
        )
      } else {
        clockDomain.waitSampling()
      }
    }
  }

  test("StreamZipByCondition test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val leftInputQueue = mutable.Queue[(Long, Boolean, Boolean)]()
      val rightInputQueue = mutable.Queue[(Long, Boolean, Boolean)]()
      val zipOutputQueue = mutable.Queue[(Long, Boolean, Long, Boolean)]()
      val matchQueue = mutable.Queue[Long]()

      fork {
        while (true) {
          val randInt = scala.util.Random.nextInt(3)
          // bothFireCond, leftFireCond, rightFireCond are mutually exclusive
          dut.io.bothFireCond #= randInt == 0
          dut.io.leftFireCond #= randInt == 1
          dut.io.rightFireCond #= randInt == 2
          sleep(0)
          if (dut.io.bothFireCond.toBoolean) {
            dut.clockDomain.waitSamplingWhere(
              dut.io.leftInputStream.valid.toBoolean && dut.io.leftInputStream.ready.toBoolean &&
                dut.io.rightInputStream.valid.toBoolean && dut.io.rightInputStream.ready.toBoolean
            )
          } else if (dut.io.leftFireCond.toBoolean) {
            dut.clockDomain.waitSamplingWhere(
              dut.io.leftInputStream.valid.toBoolean && dut.io.leftInputStream.ready.toBoolean
            )
          } else if (dut.io.rightFireCond.toBoolean) {
            dut.clockDomain.waitSamplingWhere(
              dut.io.rightInputStream.valid.toBoolean && dut.io.rightInputStream.ready.toBoolean
            )
          } else {
            dut.clockDomain.waitSampling()
          }
        }
      }

      streamZipMasterDriver(
        dut.io.leftInputStream,
        dut.io.bothFireCond,
        dut.io.leftFireCond,
        dut.clockDomain
      )
      onStreamFire(dut.io.leftInputStream, dut.clockDomain) {
        leftInputQueue.enqueue(
          (
            dut.io.leftInputStream.payload.toLong,
            dut.io.bothFireCond.toBoolean,
            dut.io.leftFireCond.toBoolean
          )
        )
      }

      streamZipMasterDriver(
        dut.io.rightInputStream,
        dut.io.bothFireCond,
        dut.io.rightFireCond,
        dut.clockDomain
      )
      onStreamFire(dut.io.rightInputStream, dut.clockDomain) {
        rightInputQueue.enqueue(
          (
            dut.io.rightInputStream.payload.toLong,
            dut.io.bothFireCond.toBoolean,
            dut.io.rightFireCond.toBoolean
          )
        )
      }

      streamSlaveRandomizer(dut.io.zipOutputStream, dut.clockDomain)
      onStreamFire(dut.io.zipOutputStream, dut.clockDomain) {
        zipOutputQueue.enqueue(
          (
            dut.io.zipOutputStream.leftOutput.toLong,
            dut.io.zipOutputStream.leftValid.toBoolean,
            dut.io.zipOutputStream.rightOutput.toLong,
            dut.io.zipOutputStream.rightValid.toBoolean
          )
        )
      }

      fork {
        var (bothCondLeftIn, bothCondRightIn) = (false, false)
        while (true) {
          bothCondLeftIn = false
          bothCondRightIn = false

          val (leftOutput, leftValidOut, rightOutput, rightValidOut) =
            MiscUtils.safeDeQueue(zipOutputQueue, dut.clockDomain)

          if (leftValidOut) {
            val (leftInput, bothCondFromLeft, leftCond) =
              MiscUtils.safeDeQueue(leftInputQueue, dut.clockDomain)
            assert(
              leftValidOut == leftCond || bothCondFromLeft,
              f"leftValidOut=${leftValidOut} not match leftCond=${leftCond} || bothCondFromLeft=${bothCondFromLeft}"
            )
            assert(
              leftOutput == leftInput,
              f"leftOutput=${leftOutput}%X not match leftInput=${leftInput}%X"
            )

            bothCondLeftIn = bothCondFromLeft
            if (bothCondFromLeft) {
//              println(
//                f"bothCondFromLeft=${bothCondFromLeft} should != leftCond=${leftCond}"
//              )
              assert(
                bothCondFromLeft != leftCond,
                f"bothCondFromLeft=${bothCondFromLeft} should != leftCond=${leftCond}"
              )
            }
          }

          if (rightValidOut) {
            val (rightInput, bothCondFromRight, rightCond) =
              MiscUtils.safeDeQueue(rightInputQueue, dut.clockDomain)
            assert(
              rightValidOut == rightCond || bothCondFromRight,
              f"rightOutput=${rightValidOut} not match rightCond=${rightCond} || bothCondFromRight=${bothCondFromRight}"
            )
            assert(
              rightOutput == rightInput,
              f"rightOutput=${rightOutput}%X not match rightInput=${rightInput}%X"
            )

            bothCondRightIn = bothCondFromRight
            if (bothCondFromRight) {
//              println(
//                f"bothCondFromRight=${bothCondFromRight} should != rightCond=${rightCond})"
//              )
              assert(
                bothCondFromRight != rightCond,
                f"bothCondFromRight=${bothCondFromRight} should != rightCond=${rightCond})"
              )
            }
          }

          assert(
            bothCondLeftIn == bothCondRightIn,
            f"bothCondLeftIn=${bothCondLeftIn} should == bothCondRightIn=${bothCondRightIn}"
          )
          matchQueue.enqueue(rightOutput)
        }
      }

      waitUntil(matchQueue.size > MATCH_CNT)
    }
  }
}

class StreamDropHeaderTest extends AnyFunSuite {
  val busWidth = BusWidth.W32

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new StreamDropHeaderWrapper(busWidth))

  def testFunc(
      dut: StreamDropHeaderWrapper,
      headerFragNum: Int,
      fragmentNum: Int
  ): Unit = {
    dut.clockDomain.forkStimulus(10)

    val inputDataQueue = mutable.Queue[Long]()
    val inputIsLastQueue = mutable.Queue[Boolean]()
    val outputDataQueue = mutable.Queue[Long]()
    val outputIsLastQueue = mutable.Queue[Boolean]()

    val inputIdxItr = NaturalNumber.from(0).iterator
    val fragIdxItr = NaturalNumber.from(0).iterator

    streamMasterDriver(dut.io.inputStream, dut.clockDomain) {
      dut.io.headerFragNum #= headerFragNum
      val inputIdx = inputIdxItr.next()
      val isLast = (inputIdx % fragmentNum == fragmentNum - 1)
      dut.io.inputStream.last #= isLast
//      println(
//        f"inputIdx=${inputIdx}, isLast=${isLast}, inputData=${dut.io.inputStream.fragment.toLong}%X"
//      )
    }
    onStreamFire(dut.io.inputStream, dut.clockDomain) {
      val fragIdx = fragIdxItr.next() % fragmentNum
//      println(
//        f"fragIdx=${fragIdx}, isLast=${dut.io.inputStream.last.toBoolean}, inputData=${dut.io.inputStream.fragment.toLong}%X"
//      )
      if (fragIdx >= headerFragNum) {
        inputDataQueue.enqueue(dut.io.inputStream.fragment.toLong)
        inputIsLastQueue.enqueue(dut.io.inputStream.last.toBoolean)
      }
    }

    streamSlaveRandomizer(dut.io.outputStream, dut.clockDomain)
    onStreamFire(dut.io.outputStream, dut.clockDomain) {
//      println(
//        f"output dut.io.outputStream.fragment=${dut.io.outputStream.fragment.toLong}%X, dut.io.outputStream.last=${dut.io.outputStream.last.toBoolean}"
//      )
      outputDataQueue.enqueue(dut.io.outputStream.fragment.toLong)
      outputIsLastQueue.enqueue(dut.io.outputStream.last.toBoolean)
    }

    MiscUtils.checkInputOutputQueues(
      dut.clockDomain,
      inputDataQueue,
      outputDataQueue,
      MATCH_CNT
    )
    MiscUtils.checkInputOutputQueues(
      dut.clockDomain,
      inputIsLastQueue,
      outputIsLastQueue,
      MATCH_CNT
    )
  }

  test("StreamDropHeader test fragmentNum > headerFragNum") {
    val headerFragNum = 3
    val fragmentNum = 10
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val inputDataQueue = mutable.Queue[Long]()
      val inputIsLastQueue = mutable.Queue[Boolean]()
      val outputDataQueue = mutable.Queue[Long]()
      val outputIsLastQueue = mutable.Queue[Boolean]()

      val inputIdxItr = NaturalNumber.from(0).iterator
      val fragIdxItr = NaturalNumber.from(0).iterator

      streamMasterDriver(dut.io.inputStream, dut.clockDomain) {
        dut.io.headerFragNum #= headerFragNum
        val inputIdx = inputIdxItr.next()
        val isLast = (inputIdx % fragmentNum == fragmentNum - 1)
        dut.io.inputStream.last #= isLast
//      println(
//        f"inputIdx=${inputIdx}, isLast=${isLast}, inputData=${dut.io.inputStream.fragment.toLong}%X"
//      )
      }
      onStreamFire(dut.io.inputStream, dut.clockDomain) {
        val fragIdx = fragIdxItr.next() % fragmentNum
//      println(
//        f"fragIdx=${fragIdx}, isLast=${dut.io.inputStream.last.toBoolean}, inputData=${dut.io.inputStream.fragment.toLong}%X"
//      )
        if (fragIdx >= headerFragNum) {
          inputDataQueue.enqueue(dut.io.inputStream.fragment.toLong)
          inputIsLastQueue.enqueue(dut.io.inputStream.last.toBoolean)
        }
      }

      streamSlaveRandomizer(dut.io.outputStream, dut.clockDomain)
      onStreamFire(dut.io.outputStream, dut.clockDomain) {
//      println(
//        f"output dut.io.outputStream.fragment=${dut.io.outputStream.fragment.toLong}%X, dut.io.outputStream.last=${dut.io.outputStream.last.toBoolean}"
//      )
        outputDataQueue.enqueue(dut.io.outputStream.fragment.toLong)
        outputIsLastQueue.enqueue(dut.io.outputStream.last.toBoolean)
      }

      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputDataQueue,
        outputDataQueue,
        MATCH_CNT
      )
      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputIsLastQueue,
        outputIsLastQueue,
        MATCH_CNT
      )
    }
  }

  test("StreamDropHeader test fragmentNum < headerFragNum") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      // Since fragmentNum < headerFragNum, no output at all
      val headerFragNum = 7
      val fragmentNum = 5

      val outputDataQueue = mutable.Queue[Long]()
      val outputIsLastQueue = mutable.Queue[Boolean]()

      val inputIdxItr = NaturalNumber.from(0).iterator

      streamMasterDriver(dut.io.inputStream, dut.clockDomain) {
        dut.io.headerFragNum #= headerFragNum
        val inputIdx = inputIdxItr.next()
        val isLast = (inputIdx % fragmentNum == fragmentNum - 1)
        dut.io.inputStream.last #= isLast
//      println(
//        f"inputIdx=${inputIdx}, isLast=${isLast}, inputData=${dut.io.inputStream.fragment.toLong}%X"
//      )
      }

      streamSlaveRandomizer(dut.io.outputStream, dut.clockDomain)
      onStreamFire(dut.io.outputStream, dut.clockDomain) {
//      println(
//        f"output dut.io.outputStream.fragment=${dut.io.outputStream.fragment.toLong}%X, dut.io.outputStream.last=${dut.io.outputStream.last.toBoolean}"
//      )
        outputDataQueue.enqueue(dut.io.outputStream.fragment.toLong)
        outputIsLastQueue.enqueue(dut.io.outputStream.last.toBoolean)
      }

      val checkCycles = MATCH_CNT
      MiscUtils.checkConditionForSomePeriod(dut.clockDomain, checkCycles) {
        outputDataQueue.isEmpty && outputIsLastQueue.isEmpty
      }
    }
  }
}

class StreamSegmentTest extends AnyFunSuite {
  val busWidth = BusWidth.W32

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new StreamSegmentWrapper(busWidth))

  test("StreamSegment test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val inputDataQueue = mutable.Queue[Long]()
      val inputIsLastQueue = mutable.Queue[Boolean]()
      val outputDataQueue = mutable.Queue[Long]()
      val outputIsLastQueue = mutable.Queue[Boolean]()

      val inputIdxItr = NaturalNumber.from(0).iterator
      val fragIdxItr = NaturalNumber.from(0).iterator
      val inputFragNum = 15
      val segmentFragNum = 10

      streamMasterDriver(dut.io.inputStream, dut.clockDomain) {
        dut.io.segmentFragNum #= segmentFragNum
        val inputIdx = inputIdxItr.next()
        val isLast = inputIdx % inputFragNum == inputFragNum - 1
        dut.io.inputStream.last #= isLast
//        println(
//          f"inputIdx=${inputIdx}, isLast=${isLast}, inputData=${dut.io.inputStream.fragment.toLong}%X"
//        )
      }
      onStreamFire(dut.io.inputStream, dut.clockDomain) {
        val fragIdx = fragIdxItr.next() % inputFragNum
        val isFragLast = fragIdx == inputFragNum - 1
        val isSegLast = fragIdx % segmentFragNum == segmentFragNum - 1
        inputDataQueue.enqueue(dut.io.inputStream.fragment.toLong)
        inputIsLastQueue.enqueue(isFragLast || isSegLast)
      }

      streamSlaveRandomizer(dut.io.outputStream, dut.clockDomain)
      onStreamFire(dut.io.outputStream, dut.clockDomain) {
        outputDataQueue.enqueue(dut.io.outputStream.fragment.toLong)
        outputIsLastQueue.enqueue(dut.io.outputStream.last.toBoolean)
      }

      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputDataQueue,
        outputDataQueue,
        MATCH_CNT
      )
      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputIsLastQueue,
        outputIsLastQueue,
        MATCH_CNT
      )
    }
  }
}

class StreamAddHeaderTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val fragmentNum = 10

  def busWidthBits: Int = busWidth.id
  def busWidthBytes: Int = busWidthBits / BYTE_WIDTH

  def expectedData(
      preData: BigInt,
      curData: BigInt,
      headerWidthBits: Int
  ): BigInt = {
    MiscUtils.truncate(
      preData << (busWidthBits - headerWidthBits),
      busWidthBits
    ) + MiscUtils.truncate(curData >> headerWidthBits, busWidthBits)
  }

  def expectedMty(
      preMty: BigInt,
      curMty: BigInt,
      headerWidthBytes: Int
  ): BigInt = {
    MiscUtils.truncate(
      preMty << (busWidthBytes - headerWidthBytes),
      busWidthBytes
    ) + MiscUtils.truncate(curMty >> headerWidthBytes, busWidthBytes)
  }

  def checkInputOutput(
      preInputData: BigInt,
      preInputMty: BigInt,
      curInputData: BigInt,
      curInputMty: BigInt,
      outputData: BigInt,
      outputMty: BigInt,
      headerWidthBytes: Int
  ): Unit = {
    val headerWidthBits = headerWidthBytes * BYTE_WIDTH

    val expectedOutputData =
      expectedData(preInputData, curInputData, headerWidthBits)
//    println(
//      f"outputData=${outputData}%X not match expectedOutputData=${expectedOutputData}%X, headerWidthBytes=${headerWidthBytes}"
//    )
    assert(
      outputData.toString(16) == expectedOutputData.toString(16),
      f"outputData=${outputData}%X not match expectedOutputData=${expectedOutputData}%X, headerWidthBytes=${headerWidthBytes}"
    )

    val expectedOutputMty =
      expectedMty(preInputMty, curInputMty, headerWidthBytes)
//    println(
//      f"outputMty=${outputMty}%X not match expectedOutputMty=${expectedOutputMty}%X, preInputMty=${preInputMty}%X, curInputMty=${curInputMty}%X, headerWidthBytes=${headerWidthBytes}"
//    )
    assert(
      outputMty.toString(16) == expectedOutputMty.toString(16),
      f"outputMty=${outputMty}%X not match expectedOutputMty=${expectedOutputMty}%X, preInputMty=${preInputMty}%X, curInputMty=${curInputMty}%X, headerWidthBytes=${headerWidthBytes}"
    )
  }

  def commTestFunc(
      dut: StreamAddHeaderWrapper,
      headerWidthBytes: Int,
      lastFragMtyValidWidth: Int
  ): Unit = {
    require(
      0 < lastFragMtyValidWidth && lastFragMtyValidWidth <= busWidthBytes,
      s"must have 0 < lastFragMtyValidWidth=${lastFragMtyValidWidth} <= busWidthBytes=${busWidthBytes}"
    )
    dut.clockDomain.forkStimulus(10)

    val lastFragLeftShiftByteAmt = busWidthBytes - lastFragMtyValidWidth
    val lastFragLeftShiftBitAmt = lastFragLeftShiftByteAmt * BYTE_WIDTH
    val hasExtraLastFrag =
      lastFragMtyValidWidth + headerWidthBytes > busWidthBytes
    println(f"""headerWidthBytes=${headerWidthBytes}
               |    busWidthBytes=${busWidthBytes}
               |    lastFragMtyValidWidth=${lastFragMtyValidWidth}
               |    hasExtraLastFrag=${hasExtraLastFrag}
               |    lastFragLeftShiftByteAmt=${lastFragLeftShiftByteAmt}
               |    lastFragLeftShiftBitAmt=${lastFragLeftShiftBitAmt}
               |""".stripMargin)

    val inputIdxItr = NaturalNumber.from(1).iterator

    val inputDataQueue = mutable.Queue[(BigInt, BigInt, Boolean)]()
    val headerQueue = mutable.Queue[(BigInt, BigInt)]()
    val outputDataQueue = mutable.Queue[(BigInt, BigInt, Boolean)]()
    val matchQueue = mutable.Queue[Boolean]()

    streamMasterDriverAlwaysValid(dut.io.inputHeader, dut.clockDomain) {
      dut.io.inputHeader.mty #= setAllBits(headerWidthBytes)
    }
    onStreamFire(dut.io.inputHeader, dut.clockDomain) {
      headerQueue.enqueue(
        (dut.io.inputHeader.data.toBigInt, dut.io.inputHeader.mty.toBigInt)
      )
    }

    streamMasterDriverAlwaysValid(dut.io.inputStream, dut.clockDomain) {
      val inputIdx = inputIdxItr.next()
      if (inputIdx % fragmentNum == 0) {
        dut.io.inputStream.data #= (BigInt(inputIdx) << lastFragLeftShiftBitAmt)
        dut.io.inputStream.mty #= setAllBits(
          lastFragMtyValidWidth
        ) << lastFragLeftShiftByteAmt
        dut.io.inputStream.last #= true
      } else {
        dut.io.inputStream.data #= BigInt(inputIdx)
        dut.io.inputStream.mty #= setAllBits(busWidthBytes)
        dut.io.inputStream.last #= false
      }
    }
    onStreamFire(dut.io.inputStream, dut.clockDomain) {
      inputDataQueue.enqueue(
        (
          dut.io.inputStream.data.toBigInt,
          dut.io.inputStream.mty.toBigInt,
          dut.io.inputStream.last.toBoolean
        )
      )
    }

    streamSlaveAlwaysReady(dut.io.outputStream, dut.clockDomain)
    onStreamFire(dut.io.outputStream, dut.clockDomain) {
      outputDataQueue.enqueue(
        (
          dut.io.outputStream.data.toBigInt,
          dut.io.outputStream.mty.toBigInt,
          dut.io.outputStream.last.toBoolean
        )
      )
    }

    // Check input and output
    fork {
      while (true) {
        while (headerQueue.isEmpty) {
          dut.clockDomain.waitFallingEdge()
        }
        val (headerData, headerMty) = headerQueue.dequeue()
        var (preInputData, preInputMty, preInputIsLast) =
          (headerData, headerMty, false)

        while (!preInputIsLast) {
          while (inputDataQueue.isEmpty || outputDataQueue.isEmpty) {
            dut.clockDomain.waitFallingEdge()
          }
          val (inputData, inputMty, inputIsLast) = inputDataQueue.dequeue()
          val (outputData, outputMty, outputIsLast) =
            outputDataQueue.dequeue()

          checkInputOutput(
            preInputData,
            preInputMty,
            inputData,
            inputMty,
            outputData,
            outputMty,
            headerWidthBytes
          )
          preInputData = inputData
          preInputMty = inputMty
          preInputIsLast = inputIsLast

          if (inputIsLast) {
            val numOfValidBits = MiscUtils.countOnes(inputMty, busWidthBytes)
            val lastFragHasResidue =
              numOfValidBits + headerWidthBytes > busWidthBytes

            if (lastFragHasResidue) {
              while (outputDataQueue.isEmpty) {
                dut.clockDomain.waitFallingEdge()
              }
              val (extraOutputData, extraOutputMty, extraOutputIsLast) =
                outputDataQueue.dequeue()
              val zeroInputData = BigInt(0)
              val zeroInputMty = BigInt(0)

              checkInputOutput(
                preInputData = inputData,
                preInputMty = inputMty,
                curInputData = zeroInputData,
                curInputMty = zeroInputMty,
                outputData = extraOutputData,
                outputMty = extraOutputMty,
                headerWidthBytes = headerWidthBytes
              )
              assert(
                extraOutputIsLast == true,
                f"when lastFragHasResidue=${lastFragHasResidue}, extraOutputIsLast=${extraOutputIsLast} should be true"
              )
            } else {
//            println(
//              f"when lastFragHasResidue=${lastFragHasResidue}, outputIsLast=${outputIsLast} should == inputIsLast=${inputIsLast}"
//            )
              assert(
                outputIsLast == inputIsLast,
                f"when lastFragHasResidue=${lastFragHasResidue}, outputIsLast=${outputIsLast} should == inputIsLast=${inputIsLast}"
              )
            }
          } else {
            assert(
              outputIsLast == false,
              f"when inputIsLast=${inputIsLast}, outputIsLast=${outputIsLast} should be false"
            )
          }

          matchQueue.enqueue(outputIsLast)
        }
      }
    }

    waitUntil(matchQueue.size > 100)
  }

//  def testFunc(dut: StreamAddHeaderWrapper, lastFragMtyValidWidth: Int) = {
//    dut.clockDomain.forkStimulus(10)
//
//    require(
//      0 < lastFragMtyValidWidth && lastFragMtyValidWidth <= widthBytes,
//      s"must have 0 < lastFragMtyValidWidth=${lastFragMtyValidWidth} <= widthBytes=${widthBytes}"
//    )
//    val lastFragLeftShiftByteAmt = if (lastFragMtyValidWidth < widthBytes) {
//      widthBytes - lastFragMtyValidWidth
//    } else 0
//
//    // TODO: input varying headers
//    dut.io.inputHeader.valid #= true
//    dut.io.inputHeader.data #= setAllBits(headerWidth)
//    dut.io.inputHeader.mty #= setAllBits(headerWidth / BYTE_WIDTH)
//    println(f"""dut.io.inputHeader.data #= ${setAllBits(headerWidth)}%X
//         |    dut.io.inputHeader.mty #= ${setAllBits(headerWidth / BYTE_WIDTH)}%X
//         |    lastFragLeftShiftByteAmt=${lastFragLeftShiftByteAmt}
//         |""".stripMargin)
//
//    dut.io.inputStream.valid #= false
//    dut.io.outputStream.ready #= false
//    dut.clockDomain.waitSampling(3)
//
//    fork {
//      for (idx <- 1 to 100) {
//        dut.io.inputStream.valid #= true
//        if (idx % fragmentNum == 0) {
//          dut.io.inputStream.data #= (BigInt(
//            idx
//          ) << (lastFragLeftShiftByteAmt * BYTE_WIDTH))
//          dut.io.inputStream.mty #= setAllBits(widthBytes) - setAllBits(
//            widthBytes - lastFragMtyValidWidth
//          )
//          dut.io.inputStream.last #= true
//        } else {
//          dut.io.inputStream.data #= BigInt(idx)
//          dut.io.inputStream.mty #= setAllBits(widthBytes)
//          dut.io.inputStream.last #= false
//        }
//        dut.clockDomain.waitSamplingWhere(
//          dut.io.inputStream.valid.toBoolean && dut.io.inputStream.ready.toBoolean
//        )
//      }
//    }
//
//    val waitCycle = 2
//    fork {
//      dut.clockDomain.waitSampling(waitCycle)
//      for (_ <- 0 to 100) {
//        dut.io.outputStream.ready.randomize() // #= ((idx % 2) == 0) // true
//        dut.clockDomain.waitSampling()
//      }
//    }
//
//    val leftShiftByteAmt = widthBytes - headerWidthBytes
//    val leftShiftBitAmt = leftShiftByteAmt * 8
//    val hasExtraLastFrag = lastFragMtyValidWidth + headerWidthBytes > widthBytes
//    val lastFragOutputShiftByteAmt = if (hasExtraLastFrag) {
//      2 * widthBytes - lastFragMtyValidWidth - headerWidthBytes
//    } else {
//      widthBytes - lastFragMtyValidWidth - headerWidthBytes
//    }
//    val lastFragOutputShiftBitAmt = lastFragOutputShiftByteAmt * 8
//    fork {
//      dut.clockDomain.waitSampling(waitCycle)
//
//      for (fragIdx <- 0 to 5 * fragmentNum) {
//        dut.clockDomain.waitSamplingWhere(
//          dut.io.outputStream.valid.toBoolean && dut.io.outputStream.ready.toBoolean
//        )
//        println(f"dut.io.outputStream.data=${dut.io.outputStream.data.toBigInt
//          .toString(16)} @ fragIdx=${fragIdx}%X, dut.io.outputStream.last=${dut.io.outputStream.last.toBoolean}")
//
//        if ((fragIdx + 1) % fragmentNum == 1) {
//          // TODO: check header
//        } else if ((fragIdx + 1) % fragmentNum != 0) {
////          println(
////            f"fragIdx=${fragIdx}, the fragment dut.io.outputStream.data=${dut.io.outputStream.data.toBigInt}%X should == (BigInt(fragIdx=${fragIdx}) << leftShiftBitAmt=${leftShiftBitAmt})=${(BigInt(fragIdx) << leftShiftBitAmt)}%X"
////          )
//          assert(
//            dut.io.outputStream.data.toBigInt.toString(16) ==
//              (BigInt(fragIdx) << leftShiftBitAmt).toString(16),
//            f"fragIdx=${fragIdx}, the fragment dut.io.outputStream.data=${dut.io.outputStream.data.toBigInt}%X should == (BigInt(fragIdx=${fragIdx}) << leftShiftBitAmt=${leftShiftBitAmt})=${(BigInt(fragIdx) << leftShiftBitAmt)}%X"
//          )
//        } else {
//          if (hasExtraLastFrag) {
//            assert(
//              dut.io.outputStream.data.toBigInt.toString(16) ==
//                (BigInt(fragIdx) << leftShiftBitAmt).toString(16),
//              f"fragIdx=${fragIdx}, the fragment dut.io.outputStream.data=${dut.io.outputStream.data.toBigInt}%X should == (BigInt(fragIdx=${fragIdx}) << leftShiftBitAmt=${leftShiftBitAmt})=${(BigInt(fragIdx) << leftShiftBitAmt)}%X"
//            )
//
//            // Wait extract last fragment
//            dut.clockDomain.waitSamplingWhere(
//              dut.io.outputStream.valid.toBoolean && dut.io.outputStream.ready.toBoolean
//            )
////            println(
////              f"when hasExtraLastFrag=${hasExtraLastFrag}, has extra last fragment after fragIdx=${fragIdx}, the last fragment dut.io.outputStream.data=${dut.io.outputStream.data.toBigInt}%X should == (BigInt(fragIdx=${fragIdx + 1}) << lastFragOutputShiftBitAmt=${lastFragOutputShiftBitAmt})=${(BigInt(fragIdx + 1) << lastFragOutputShiftBitAmt)}%X"
////            )
//            assert(
//              dut.io.outputStream.data.toBigInt.toString(16) ==
//                (BigInt(fragIdx + 1) << lastFragOutputShiftBitAmt).toString(16),
//              f"when hasExtraLastFrag=${hasExtraLastFrag}, has extra last fragment after fragIdx=${fragIdx + 1}, the last fragment dut.io.outputStream.data=${dut.io.outputStream.data.toBigInt}%X should == (BigInt(fragIdx=${fragIdx + 1}) << lastFragOutputShiftBitAmt=${lastFragOutputShiftBitAmt})=${(BigInt(fragIdx + 1) << lastFragOutputShiftBitAmt)}%X"
//            )
//          } else {
////            println(
////              f"when hasExtraLastFrag=${hasExtraLastFrag}, no extra last fragment, fragIdx=${fragIdx}, the last fragment dut.io.outputStream.data=${dut.io.outputStream.data.toBigInt}%X should == ${((BigInt(
////                fragIdx
////              ) << leftShiftBitAmt)) + ((BigInt(fragIdx) + 1) << lastFragOutputShiftBitAmt)}%X"
////            )
//            assert(
//              dut.io.outputStream.data.toBigInt.toString(16) ==
//                ((BigInt(fragIdx) << leftShiftBitAmt) + ((BigInt(
//                  fragIdx
//                ) + 1) << lastFragOutputShiftBitAmt))
//                  .toString(16),
//              f"when hasExtraLastFrag=${hasExtraLastFrag}, fragIdx=${fragIdx}, the last fragment dut.io.outputStream.data=${dut.io.outputStream.data.toBigInt}%X should == ${((BigInt(
//                fragIdx
//              ) << leftShiftBitAmt)) + ((BigInt(fragIdx) + 1) << lastFragOutputShiftBitAmt)}%X"
//            )
//          }
//        }
//      }
//    }
//    dut.clockDomain.waitSampling(100)
//    simSuccess()
//  }

  val simCfg = SimConfig.withWave.compile(new StreamAddHeaderWrapper(busWidth))

  test("StreamAddHeader test has extra last fragment") {
    val headerWidthBytes = MiscUtils.randomHeaderByteWidth()
    val lastFragMtyValidWidth = busWidthBytes - headerWidthBytes + 1

    simCfg.doSim(dut =>
      commTestFunc(dut, headerWidthBytes, lastFragMtyValidWidth)
    )
  }

  test("StreamAddHeader test no extra last fragment") {
    val headerWidthBytes = MiscUtils.randomHeaderByteWidth()
    val lastFragMtyValidWidth = busWidthBytes - headerWidthBytes - 1

    simCfg.doSim(dut =>
      commTestFunc(dut, headerWidthBytes, lastFragMtyValidWidth)
    )
  }
}

class StreamRemoveHeaderTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
//  val headerWidthBytes = 16
  val fragmentNum = 10

  def busWidthBits: Int = busWidth.id
  def busWidthBytes: Int = busWidth.id / BYTE_WIDTH
//  def headerWidth: Int = headerWidthBytes * BYTE_WIDTH

  def expectedData(
      preData: BigInt,
      curData: BigInt,
      headerWidthBits: Int
  ): BigInt = {
    MiscUtils.truncate(
      preData << headerWidthBits,
      busWidthBits
    ) + (curData >> (busWidthBits - headerWidthBits))
  }

  def expectedMty(
      preMty: BigInt,
      curMty: BigInt,
      headerWidthBytes: Int
  ): BigInt = {
    MiscUtils.truncate(
      preMty << headerWidthBytes,
      busWidthBytes
    ) + (curMty >> (busWidthBytes - headerWidthBytes))
  }

  def checkInputOutput(
      preInputData: BigInt,
      preInputMty: BigInt,
      curInputData: BigInt,
      curInputMty: BigInt,
      outputData: BigInt,
      outputMty: BigInt,
      headerWidthBytes: Int
  ): Unit = {
    val headerWidthBits = headerWidthBytes * BYTE_WIDTH
    val expectedOutputData =
      expectedData(preInputData, curInputData, headerWidthBits)
//    println(
//      f"outputData=${outputData}%X not match expectedOutputData=${expectedOutputData}%X"
//    )
    assert(
      outputData.toString(16) == expectedOutputData.toString(16),
      f"outputData=${outputData}%X not match expectedOutputData=${expectedOutputData}%X"
    )

    val expectedOutputMty =
      expectedMty(preInputMty, curInputMty, headerWidthBytes)
//    println(
//      f"outputMty=${outputMty}%X not match expectedOutputMty=${expectedOutputMty}%X, preInputMty=${preInputMty}%X, curInputMty=${curInputMty}%X"
//    )
    assert(
      outputMty.toString(16) == expectedOutputMty.toString(16),
      f"outputMty=${outputMty}%X not match expectedOutputMty=${expectedOutputMty}%X, preInputMty=${preInputMty}%X, curInputMty=${curInputMty}%X"
    )
  }

  def commTestFunc(
      dut: StreamRemoveHeaderWrapper,
      headerWidthBytes: Int,
      lastFragMtyValidWidth: Int
  ): Unit = {
    dut.clockDomain.forkStimulus(10)

    val lastFragLeftShiftByteAmt = busWidthBytes - lastFragMtyValidWidth
    val lastFragLeftShiftBitAmt = lastFragLeftShiftByteAmt * BYTE_WIDTH
    val hasExtraLastFrag = lastFragMtyValidWidth > headerWidthBytes
    println(f"""hasExtraLastFrag=${hasExtraLastFrag}
               |    lastFragMtyValidWidth=${lastFragMtyValidWidth}
               |    headerWidthBytes=${headerWidthBytes}
               |    lastFragLeftShiftByteAmt=${lastFragLeftShiftByteAmt}
               |    lastFragLeftShiftBitAmt=${lastFragLeftShiftBitAmt}
               |""".stripMargin)

    val inputIdxItr = NaturalNumber.from(1).iterator

    val inputDataQueue = mutable.Queue[(BigInt, BigInt, Boolean)]()
    val outputDataQueue = mutable.Queue[(BigInt, BigInt, Boolean)]()
    val matchQueue = mutable.Queue[Boolean]()

    dut.io.headerLenBytes #= headerWidthBytes

    // Input to DUT
    streamMasterDriver(dut.io.inputStream, dut.clockDomain) {
      val inputIdx = inputIdxItr.next()
      if (inputIdx % fragmentNum == 0) {
        // Handle last input fragment
        dut.io.inputStream.data #= (BigInt(inputIdx) << lastFragLeftShiftBitAmt)
        dut.io.inputStream.mty #= setAllBits(
          lastFragMtyValidWidth
        ) << lastFragLeftShiftByteAmt
        dut.io.inputStream.last #= true
      } else {
        dut.io.inputStream.data #= BigInt(inputIdx)
        dut.io.inputStream.mty #= setAllBits(busWidthBytes)
        dut.io.inputStream.last #= false
      }
    }
    onStreamFire(dut.io.inputStream, dut.clockDomain) {
      inputDataQueue.enqueue(
        (
          dut.io.inputStream.data.toBigInt,
          dut.io.inputStream.mty.toBigInt,
          dut.io.inputStream.last.toBoolean
        )
      )
    }

    // Collect DUT output
    streamSlaveRandomizer(dut.io.outputStream, dut.clockDomain)
    onStreamFire(dut.io.outputStream, dut.clockDomain) {
      outputDataQueue.enqueue(
        (
          dut.io.outputStream.data.toBigInt,
          dut.io.outputStream.mty.toBigInt,
          dut.io.outputStream.last.toBoolean
        )
      )
    }

    // Check input and output
    fork {
      while (inputDataQueue.isEmpty) {
        dut.clockDomain.waitFallingEdge()
      }
      var (preInputData, preInputMty, preInputIsLast) = inputDataQueue.dequeue()
      while (true) {
        while (inputDataQueue.isEmpty) {
          dut.clockDomain.waitFallingEdge()
        }
        val (inputData, inputMty, inputIsLast) = inputDataQueue.dequeue()
        while (outputDataQueue.isEmpty) {
          dut.clockDomain.waitFallingEdge()
        }
        val (outputData, outputMty, outputIsLast) =
          outputDataQueue.dequeue()
        checkInputOutput(
          preInputData,
          preInputMty,
          inputData,
          inputMty,
          outputData,
          outputMty,
          headerWidthBytes
        )

        preInputData = inputData
        preInputMty = inputMty
        preInputIsLast = inputIsLast

        if (inputIsLast) {
          val numOfValidBits = MiscUtils.countOnes(inputMty, busWidthBytes)
          val lastFragHasResidue = numOfValidBits > headerWidthBytes

          if (lastFragHasResidue) {
            while (outputDataQueue.isEmpty) {
              dut.clockDomain.waitFallingEdge()
            }
            val (extraOutputData, extraOutputMty, extraOutputIsLast) =
              outputDataQueue.dequeue()
            val zeroInputData = BigInt(0)
            val zeroInputMty = BigInt(0)

            checkInputOutput(
              preInputData = inputData,
              preInputMty = inputMty,
              curInputData = zeroInputData,
              curInputMty = zeroInputMty,
              outputData = extraOutputData,
              outputMty = extraOutputMty,
              headerWidthBytes = headerWidthBytes
            )
            assert(
              extraOutputIsLast == true,
              f"when lastFragHasResidue=${lastFragHasResidue}, extraOutputIsLast=${extraOutputIsLast} should be true"
            )

            while (inputDataQueue.isEmpty) {
              dut.clockDomain.waitFallingEdge()
            }
            val (nextInputData, nextInputMty, nextInputIsLast) =
              inputDataQueue.dequeue()
            preInputData = nextInputData
            preInputMty = nextInputMty
            preInputIsLast = nextInputIsLast
          } else {
//            println(
//              f"when lastFragHasResidue=${lastFragHasResidue}, outputIsLast=${outputIsLast} should == inputIsLast=${inputIsLast}"
//            )
            assert(
              outputIsLast == inputIsLast,
              f"when lastFragHasResidue=${lastFragHasResidue}, outputIsLast=${outputIsLast} should == inputIsLast=${inputIsLast}"
            )
            // When last fragment has no residue, set previous input to the next first input fragment
            while (inputDataQueue.isEmpty) {
              dut.clockDomain.waitFallingEdge()
            }
            val (extraInputData, extraInputMty, extraInputIsLast) =
              inputDataQueue.dequeue()
            preInputData = extraInputData
            preInputMty = extraInputMty
            preInputIsLast = extraInputIsLast
          }
        } else {
          assert(
            outputIsLast == false,
            f"when inputIsLast=${inputIsLast}, outputIsLast=${outputIsLast} should be false"
          )
        }

        matchQueue.enqueue(outputIsLast)
      }
    }

    waitUntil(matchQueue.size > MATCH_CNT)
  }

  val simCfg = SimConfig.withWave
    .compile(new StreamRemoveHeaderWrapper(busWidth))

  test("StreamRemoveHeader test has extra last fragment") {
    val headerWidthBytes = MiscUtils.randomHeaderByteWidth()
    val lastFragMtyValidWidth = headerWidthBytes + 1

    simCfg.doSim(dut =>
      commTestFunc(dut, headerWidthBytes, lastFragMtyValidWidth)
    )
  }

  test("StreamRemoveHeader test no extra last fragment") {
    val headerWidthBytes = MiscUtils.randomHeaderByteWidth()
    val lastFragMtyValidWidth = headerWidthBytes - 1

    simCfg.doSim(dut =>
      commTestFunc(dut, headerWidthBytes, lastFragMtyValidWidth)
    )
  }
}

class FragmentStreamJoinStreamTest extends AnyFunSuite {
  val busWidth = BusWidth.W32
  val fragmentNum = 10

  def halfWidth: Int = busWidth.id / 2

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new FragmentStreamJoinStreamWrapper(busWidth))

  test("FragmentStreamJoinStream test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val inputFragDataQueue = mutable.Queue[Long]()
      val inputFragIsLastQueue = mutable.Queue[Boolean]()
      val inputStreamDataQueue = mutable.Queue[Long]()
      val outputFragDataQueue = mutable.Queue[Long]()
      val outputFragIsLastQueue = mutable.Queue[Boolean]()
      val matchQueue = mutable.Queue[Long]()

      val inputFragIdxItr = NaturalNumber.from(0).iterator

      // Input to DUT
      streamMasterDriver(dut.io.inputFragmentStream, dut.clockDomain) {
        val fragIdx = inputFragIdxItr.next()
        val last = fragIdx % fragmentNum == fragmentNum - 1
        dut.io.inputFragmentStream.last #= last
      }
      onStreamFire(dut.io.inputFragmentStream, dut.clockDomain) {
        inputFragDataQueue.enqueue(dut.io.inputFragmentStream.fragment.toLong)
        inputFragIsLastQueue.enqueue(dut.io.inputFragmentStream.last.toBoolean)
      }
      streamMasterDriver(dut.io.inputStream, dut.clockDomain) {
        // No need to explicitly assign dut.io.inputStream, just use randomized assignment
      }
      onStreamFire(dut.io.inputStream, dut.clockDomain) {
        inputStreamDataQueue.enqueue(dut.io.inputStream.payload.toLong)
      }

      // Collect DUT output
      streamSlaveRandomizer(dut.io.outputJoinStream, dut.clockDomain)
      onStreamFire(dut.io.outputJoinStream, dut.clockDomain) {
        outputFragDataQueue.enqueue(dut.io.outputJoinStream.fragment.toLong)
        outputFragIsLastQueue.enqueue(dut.io.outputJoinStream.last.toBoolean)
      }

      // Check input and output
      fork {
        while (true) {
          dut.clockDomain.waitFallingEdge()
          if (inputStreamDataQueue.nonEmpty) {
            val inputStreamData = inputStreamDataQueue.dequeue()
            val inputStreamDataSecondHalf =
              inputStreamData & setAllBits(halfWidth).toLong

            for (fragIdx <- 0 until fragmentNum) {
              while (
                inputFragDataQueue.isEmpty || outputFragDataQueue.isEmpty
              ) {
                dut.clockDomain.waitFallingEdge()
              }
              val inputFragData = inputFragDataQueue.dequeue()
              val outputFragData = outputFragDataQueue.dequeue()
              val inputFragDataSecondHalf = (inputFragData & (setAllBits(
                halfWidth
              ).toLong << halfWidth))
              assert(
                outputFragData == inputFragDataSecondHalf + inputStreamDataSecondHalf,
                f"outputFragData=${outputFragData}%X should == (inputFragDataSecondHalf=${inputFragDataSecondHalf}%X + inputStreamDataSecondHalf=${inputStreamDataSecondHalf}%X)=${inputFragDataSecondHalf + inputStreamDataSecondHalf}%X @ fragIdx=${fragIdx}"
              )

              val outputIsLast = outputFragIsLastQueue.dequeue()
              val inputIsLast = inputFragIsLastQueue.dequeue()
              assert(
                outputIsLast == inputIsLast,
                f"outputIsLast=${outputIsLast} should == inputIsLast=${inputIsLast}"
              )

              matchQueue.enqueue(outputFragData)
            }
          }
        }
      }

      waitUntil(matchQueue.size > MATCH_CNT)
    }
  }
}

class FragmentStreamConditionalJoinStreamTest extends AnyFunSuite {
  val busWidth = BusWidth.W32
  val fragmentNum = 5
  val joinPktNum = 3

  def halfWidth: Int = busWidth.id / 2

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new FragmentStreamConditionalJoinStreamWrapper(busWidth))

  test("FragmentStreamJoinStream test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val inputFragDataQueue = mutable.Queue[Long]()
      val inputFragIsLastQueue = mutable.Queue[Boolean]()
      val inputStreamDataQueue = mutable.Queue[Long]()
      val outputFragDataQueue = mutable.Queue[Long]()
      val outputFragIsLastQueue = mutable.Queue[Boolean]()
      val matchQueue = mutable.Queue[Long]()

      val inputFragIdxItr = NaturalNumber.from(0).iterator
      val joinPktFragNum = joinPktNum * fragmentNum
//      dut.io.joinCond #= false

      // Input to DUT
      streamMasterDriverAlwaysValid(
        dut.io.inputFragmentStream,
        dut.clockDomain
      ) {
        val fragIdx = inputFragIdxItr.next()
        val last = fragIdx % fragmentNum == fragmentNum - 1
        dut.io.inputFragmentStream.last #= last
        dut.io.joinCond #= fragIdx % joinPktFragNum == joinPktFragNum - 1
      }
      onStreamFire(dut.io.inputFragmentStream, dut.clockDomain) {
        inputFragDataQueue.enqueue(dut.io.inputFragmentStream.fragment.toLong)
        inputFragIsLastQueue.enqueue(dut.io.inputFragmentStream.last.toBoolean)
      }
      streamMasterDriverAlwaysValid(dut.io.inputStream, dut.clockDomain) {
        // No need to explicitly assign dut.io.inputStream, just use randomized assignment
      }
      onStreamFire(dut.io.inputStream, dut.clockDomain) {
        inputStreamDataQueue.enqueue(dut.io.inputStream.payload.toLong)
      }

      // Collect DUT output
      streamSlaveAlwaysReady(dut.io.outputJoinStream, dut.clockDomain)
      onStreamFire(dut.io.outputJoinStream, dut.clockDomain) {
        outputFragDataQueue.enqueue(dut.io.outputJoinStream.fragment.toLong)
        outputFragIsLastQueue.enqueue(dut.io.outputJoinStream.last.toBoolean)
      }

      // Check input and output
      fork {
        while (true) {
          dut.clockDomain.waitFallingEdge()
          if (inputStreamDataQueue.nonEmpty) {
            val inputStreamData = inputStreamDataQueue.dequeue()
            val inputStreamDataSecondHalf =
              inputStreamData & setAllBits(halfWidth).toLong

            for (_ <- 0 until joinPktNum) {
              for (fragIdx <- 0 until fragmentNum) {
                val inputFragData =
                  MiscUtils.safeDeQueue(inputFragDataQueue, dut.clockDomain)
                val outputFragData =
                  MiscUtils.safeDeQueue(outputFragDataQueue, dut.clockDomain)
                val inputFragDataSecondHalf = (inputFragData & (setAllBits(
                  halfWidth
                ).toLong << halfWidth))
                assert(
                  outputFragData == inputFragDataSecondHalf + inputStreamDataSecondHalf,
                  f"outputFragData=${outputFragData}%X should == (inputFragDataSecondHalf=${inputFragDataSecondHalf}%X + inputStreamDataSecondHalf=${inputStreamDataSecondHalf}%X)=${inputFragDataSecondHalf + inputStreamDataSecondHalf}%X @ fragIdx=${fragIdx}"
                )

                val outputIsLast = outputFragIsLastQueue.dequeue()
                val inputIsLast = inputFragIsLastQueue.dequeue()
                assert(
                  outputIsLast == inputIsLast,
                  f"outputIsLast=${outputIsLast} should == inputIsLast=${inputIsLast}"
                )

                matchQueue.enqueue(outputFragData)
              }
            }
          }
        }
      }

      waitUntil(matchQueue.size > MATCH_CNT)
    }
  }
}

class StreamConditionalFork2Test extends AnyFunSuite {
  val busWidth = BusWidth.W32

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new StreamConditionalFork2Wrapper(busWidth))

  test("StreamConditionalFork2 test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val inputQueue = mutable.Queue[(BigInt, Boolean)]()
      val longOutQueue = mutable.Queue[(BigInt, Boolean)]()
      val shortOutQueue = mutable.Queue[(BigInt, Boolean)]()
      val matchQueue = mutable.Queue[Boolean]()

      streamMasterDriver(dut.io.inputFragmentStream, dut.clockDomain) {
        // No action here
      }
      onStreamFire(dut.io.inputFragmentStream, dut.clockDomain) {
        inputQueue.enqueue(
          (
            dut.io.inputFragmentStream.fragment.toBigInt,
            dut.io.inputFragmentStream.last.toBoolean
          )
        )
      }

      streamSlaveRandomizer(dut.io.longOutputStream, dut.clockDomain)
      onStreamFire(dut.io.longOutputStream, dut.clockDomain) {
        longOutQueue.enqueue(
          (
            dut.io.longOutputStream.fragment.toBigInt,
            dut.io.longOutputStream.last.toBoolean
          )
        )
      }
      streamSlaveRandomizer(dut.io.shortOutputStream, dut.clockDomain)
      onStreamFire(dut.io.shortOutputStream, dut.clockDomain) {
        shortOutQueue.enqueue(
          (
            dut.io.shortOutputStream.fragment.toBigInt,
            dut.io.shortOutputStream.last.toBoolean
          )
        )
      }

      fork {
        var (isFirst, isLast) = (true, false)
        while (true) {
          isFirst = true
          val (shortOutputData, shortOutputLast) =
            MiscUtils.safeDeQueue(shortOutQueue, dut.clockDomain)
          do {
            val (longOutputData, longOutputLast) =
              MiscUtils.safeDeQueue(longOutQueue, dut.clockDomain)
            val (inputData, inputLast) =
              MiscUtils.safeDeQueue(inputQueue, dut.clockDomain)
            if (isFirst) {
              assert(
                longOutputLast == shortOutputLast,
                f"longOutputData=${longOutputData} should == shortOutputLast=${shortOutputLast}"
              )
              assert(
                longOutputData == shortOutputData,
                f"longOutputData=${longOutputData}%X should == shortOutputData=${shortOutputData}%X"
              )
            }
            assert(
              longOutputLast == inputLast,
              f"longOutputData=${longOutputData} should == inputLast=${inputLast}"
            )
//            println(
//              f"longOutputData=${longOutputData}%X should == inputData=${inputData}%X"
//            )
            assert(
              longOutputData == inputData,
              f"longOutputData=${longOutputData}%X should == inputData=${inputData}%X"
            )
            isFirst = false
            isLast = longOutputLast
          } while (!isLast)

          matchQueue.enqueue(isFirst)
        }
      }
      waitUntil(matchQueue.size > MATCH_CNT)
    }
  }
}

class StreamReqAndRespTest extends AnyFunSuite {
  val busWidth = BusWidth.W32

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new StreamReqAndRespWrapper(busWidth))

  test("StreamReqAndResp streamMasterDriverOneShot test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val reqQueue = mutable.Queue[Long]()
      val matchQueue = mutable.Queue[Long]()

      dut.io.on #= false
      dut.clockDomain.waitSampling(3)
      dut.io.on #= true

      val naturalNumItr = NaturalNumber.from(0).iterator

      streamSlaveRandomizer(dut.io.req, dut.clockDomain)
      onStreamFire(dut.io.req, dut.clockDomain) {
        reqQueue.enqueue(dut.io.req.payload.toLong)
      }
      waitUntil(reqQueue.nonEmpty)

      streamMasterDriverOneShot(dut.io.resp, dut.clockDomain) {
        val respPayload = reqQueue.dequeue()
        dut.io.resp.payload #= respPayload
//        println(f"reqPayload=${reqPayload}%X")
        val nextNaturalNum = naturalNumItr.next()
        assert(
          respPayload == nextNaturalNum,
          f"respPayload=${respPayload} should == nextNaturalNum=${nextNaturalNum}"
        )
      }
      onStreamFire(dut.io.resp, dut.clockDomain) {
        matchQueue.enqueue(dut.io.resp.payload.toLong)
      }

      waitUntil(matchQueue.nonEmpty)
      dut.clockDomain.waitSampling(10)
      assert(
        matchQueue.size == 1,
        f"matchQueue should have only 1 element, but matchQueue.size=${matchQueue.size}"
      )
    }
  }

  test("StreamReqAndResp onReceiveStreamReqAndThenResponseOneShot test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val reqQueue = mutable.Queue[Long]()
      val matchQueue = mutable.Queue[Long]()

      dut.io.on #= false
      dut.clockDomain.waitSampling(3)
      dut.io.on #= true

      val naturalNumItr = NaturalNumber.from(0).iterator
      onReceiveStreamReqAndThenResponseOneShot(
        dut.io.req,
        dut.io.resp,
        dut.clockDomain
      ) {
        val reqPayload = dut.io.req.payload.toLong
        reqQueue.enqueue(reqPayload)
//        println(f"reqPayload=${reqPayload}%X")
      } {
        val respPayload = reqQueue.dequeue()
//        println(f"respPayload=${respPayload}%X")
        dut.io.resp.payload #= respPayload

        val nextNaturalNum = naturalNumItr.next()
        assert(
          respPayload == nextNaturalNum,
          f"respPayload=${respPayload} should == nextNaturalNum=${nextNaturalNum}"
        )
        matchQueue.enqueue(respPayload)
      }

      waitUntil(matchQueue.nonEmpty)
      dut.clockDomain.waitSampling(10)
      assert(
        matchQueue.size == 1,
        f"matchQueue should have only 1 element, but matchQueue.size=${matchQueue.size}"
      )
    }
  }

  test("StreamReqAndResp deterministic test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val reqQueue = mutable.Queue[Long]()
      val matchQueue = mutable.Queue[Long]()

      dut.io.on #= false
      dut.clockDomain.waitSampling(3)
      dut.io.on #= true

      val naturalNumItr = NaturalNumber.from(0).iterator
      onReceiveStreamReqAndThenResponseAlways(
        dut.io.req,
        dut.io.resp,
        dut.clockDomain
      ) {
        val reqPayload = dut.io.req.payload.toLong
        reqQueue.enqueue(reqPayload)
//        println(f"reqPayload=${reqPayload}%X")
      } {
        val respPayload = reqQueue.dequeue()
//        println(f"respPayload=${respPayload}%X")
        dut.io.resp.payload #= respPayload

        val nextNaturalNum = naturalNumItr.next()
        assert(
          respPayload == nextNaturalNum,
          f"respPayload=${respPayload} should == nextNaturalNum=${nextNaturalNum}"
        )
        matchQueue.enqueue(respPayload)
      }
      waitUntil(matchQueue.size > MATCH_CNT)
    }
  }

  test("StreamReqAndResp random test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val reqQueue = mutable.Queue[Long]()
      val matchQueue = mutable.Queue[Long]()

      dut.io.on #= false
      dut.clockDomain.waitSampling(3)
      dut.io.on #= true

      val naturalNumItr = NaturalNumber.from(0).iterator
      onReceiveStreamReqAndThenResponseRandom(
        dut.io.req,
        dut.io.resp,
        dut.clockDomain
      ) {
        val reqPayload = dut.io.req.payload.toLong
        reqQueue.enqueue(reqPayload)
//        println(f"reqPayload=${reqPayload}%X")
      } {
        val respPayload = reqQueue.dequeue()
//        println(f"respPayload=${respPayload}%X")
        dut.io.resp.payload #= respPayload

        val nextNaturalNum = naturalNumItr.next()
        assert(
          respPayload == nextNaturalNum,
          f"respPayload=${respPayload} should == nextNaturalNum=${nextNaturalNum}"
        )
        matchQueue.enqueue(respPayload)
      }
      waitUntil(matchQueue.size > MATCH_CNT)
    }
  }
}

class PktFragStreamTest extends AnyFunSuite {
  val busWidth = BusWidth.W32

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new PktFragStreamWrapper(busWidth))

  test("PktFragStream pktFragStreamMasterDriverAlwaysValid test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val pmtuLen = PMTU.U256

      // Input to DUT
      val maxFragNum = 17
      val (totalFragNumItr, pktNumItr, psnItr, totalLenItr) =
        SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)

      val inputQueue = mutable.Queue[(PSN, FragLast, PktFragData)]()
      val outputQueue = mutable.Queue[(PSN, FragLast, PktFragData)]()
//      val matchQueue = mutable.Queue[Boolean]()

      pktFragStreamMasterDriverAlwaysValid(
        dut.io.input.pktFrag,
        dut.clockDomain
      ) {
        val totalFragNum = totalFragNumItr.next()
        val pktNum = pktNumItr.next()
        val psnStart = psnItr.next()
        val totalLenBytes = totalLenItr.next()
        val outerLoopRslt = (pktNum, totalLenBytes)

//        println(
//          f"${simTime()} time: pktNum=${pktNum}, totalFragNum=${totalFragNum}, psnStart=${psnStart}, totalLenBytes=${totalLenBytes}"
//        )
        (psnStart, totalFragNum, pktNum, pmtuLen, busWidth, outerLoopRslt)
      } { (psn, _, isLast, _, _, _, _, _) =>
        inputQueue.enqueue((psn, isLast, dut.io.input.pktFrag.data.toBigInt))
        dut.io.input.pktFrag.bth.psn #= psn
        dut.io.input.pktFrag.last #= isLast
      }

      streamSlaveRandomizer(dut.io.output.pktFrag, dut.clockDomain)
      onStreamFire(dut.io.output.pktFrag, dut.clockDomain) {
        outputQueue.enqueue(
          (
            dut.io.output.pktFrag.bth.psn.toInt,
            dut.io.output.pktFrag.last.toBoolean,
            dut.io.output.pktFrag.data.toBigInt
          )
        )
      }

      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputQueue,
        outputQueue,
        MATCH_CNT
      )
    }
  }

  test("PktFragStream pktFragStreamMasterDriver test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val pmtuLen = PMTU.U256

      // Input to DUT
      val maxFragNum = 17
      val (totalFragNumItr, pktNumItr, psnItr, totalLenItr) =
        SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)

      val inputQueue = mutable.Queue[(PSN, FragLast, PktFragData)]()
      val outputQueue = mutable.Queue[(PSN, FragLast, PktFragData)]()

      pktFragStreamMasterDriver(dut.io.input.pktFrag, dut.clockDomain) {
        val totalFragNum = totalFragNumItr.next()
        val pktNum = pktNumItr.next()
        val psnStart = psnItr.next()
        val totalLenBytes = totalLenItr.next()
        val outerLoopRslt = (pktNum, totalLenBytes)

//        println(
//          f"${simTime()} time: pktNum=${pktNum}, totalFragNum=${totalFragNum}, psnStart=${psnStart}, totalLenBytes=${totalLenBytes}"
//        )
        (psnStart, totalFragNum, pktNum, pmtuLen, busWidth, outerLoopRslt)
      } { (psn, _, isLast, _, _, _, _, _) =>
        inputQueue.enqueue((psn, isLast, dut.io.input.pktFrag.data.toBigInt))
        dut.io.input.pktFrag.bth.psn #= psn
        dut.io.input.pktFrag.last #= isLast
      }

      streamSlaveRandomizer(dut.io.output.pktFrag, dut.clockDomain)
      onStreamFire(dut.io.output.pktFrag, dut.clockDomain) {
        outputQueue.enqueue(
          (
            dut.io.output.pktFrag.bth.psn.toInt,
            dut.io.output.pktFrag.last.toBoolean,
            dut.io.output.pktFrag.data.toBigInt
          )
        )
      }

      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputQueue,
        outputQueue,
        MATCH_CNT
      )
    }
  }
}

//class FragmentStreamForkQueryJoinRespSimpleTest extends AnyFunSuite {
//  val busWidth = BusWidth.W32
//
//  val simCfg = SimConfig.allOptimisation.withWave
//    .compile(new FragmentStreamForkQueryJoinRespSimpleWrapper(busWidth))
//
//  test("FragmentStreamForkQueryJoinResp simple test") {
//    simCfg.doSim { dut =>
//      dut.clockDomain.forkStimulus(10)
//
//      val pmtuLen = PMTU.U256
//
//      // Input to DUT
//      val maxFragNum = 37
//      val (totalFragNumItr, pktNumItr, psnItr, totalLenItr) =
//        SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)
//
//      val inputQueue = mutable.Queue[(PktFragData, FragLast)]()
//      val outputQueue = mutable.Queue[(PktFragData, FragLast)]()
//
//      val maxFragNumPerPkt =
//        SendWriteReqReadRespInputGen.maxFragNumPerPkt(pmtuLen, busWidth)
//      var firstFragData = BigInt(0)
//      pktFragStreamMasterDriver(dut.io.inputFragmentStream, dut.clockDomain) {
//        val totalFragNum = totalFragNumItr.next()
//        val pktNum = pktNumItr.next()
//        val psnStart = psnItr.next()
//        val totalLenBytes = totalLenItr.next()
//        val outerLoopRslt = (pktNum, totalLenBytes)
////        println(
////          f"${simTime()} time: pktNum=${pktNum}, totalFragNum=${totalFragNum}, psnStart=${psnStart}, totalLenBytes=${totalLenBytes}"
////        )
//        (psnStart, totalFragNum, pktNum, pmtuLen, busWidth, outerLoopRslt)
//      } { (_, _, isLast, fragIdx, _, _, _, _) =>
//        val isFirst = fragIdx % maxFragNumPerPkt == 0
//        val inputFragData = dut.io.inputFragmentStream.fragment.toBigInt
//        if (isFirst) {
//          firstFragData = inputFragData
//        }
//        val outputFragData =
//          (inputFragData << (busWidth.id / 2)) | firstFragData
//        dut.io.inputFragmentStream.last #= isLast
//        inputQueue.enqueue((outputFragData, isLast))
//      }
//
//      streamSlaveRandomizer(dut.io.outputJoinStream, dut.clockDomain)
//      onStreamFire(dut.io.outputJoinStream, dut.clockDomain) {
//        outputQueue.enqueue(
//          (
//            dut.io.outputJoinStream.fragment.toBigInt,
//            dut.io.outputJoinStream.last.toBoolean
//          )
//        )
//      }
//
//      MiscUtils.checkInputOutputQueues(
//        dut.clockDomain,
//        inputQueue,
//        outputQueue,
//        MATCH_CNT
//      )
//    }
//  }
//}

class FragmentStreamForkQueryJoinRespTest extends AnyFunSuite {
  val busWidth = BusWidth.W512

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new FragmentStreamForkQueryJoinRespWrapper)

  test("FragmentStreamForkQueryJoinResp has query test") {
    testFunc(hasAddrCacheQuery = true)
  }

  test("FragmentStreamForkQueryJoinResp no query test") {
    testFunc(hasAddrCacheQuery = false)
  }

  def testFunc(hasAddrCacheQuery: Boolean) = simCfg.doSim { dut =>
    dut.clockDomain.forkStimulus(10)
    dut.io.hasAddrCacheQuery #= hasAddrCacheQuery

    val pmtuLen = PMTU.U256

    // Input to DUT
    val maxFragNum = 37
    val (totalFragNumItr, pktNumItr, psnItr, totalLenItr) =
      SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)

    val inputQueue = mutable.Queue[(PSN, FragLast)]()
    val outputQueue = mutable.Queue[(PktFragData, FragLast)]()
    val matchQueue = mutable.Queue[FragLast]()

    pktFragStreamMasterDriver(dut.io.inputFragmentStream, dut.clockDomain) {
      val totalFragNum = totalFragNumItr.next()
      val pktNum = pktNumItr.next()
      val psnStart = psnItr.next()
      val totalLenBytes = totalLenItr.next()
      val outerLoopRslt = (pktNum, totalLenBytes)
//        println(
//          f"${simTime()} time: pktNum=${pktNum}, totalFragNum=${totalFragNum}, psnStart=${psnStart}, totalLenBytes=${totalLenBytes}"
//        )
      (psnStart, totalFragNum, pktNum, pmtuLen, busWidth, outerLoopRslt)
    } { (psn, _, isLast, _, _, _, _, _) =>
      dut.io.inputFragmentStream.fragment #= psn
      dut.io.inputFragmentStream.last #= isLast
    }
    onStreamFire(dut.io.inputFragmentStream, dut.clockDomain) {
      inputQueue.enqueue(
        (
          dut.io.inputFragmentStream.fragment.toInt,
          dut.io.inputFragmentStream.last.toBoolean
        )
      )
    }

    val addrCacheRespQueue = if (hasAddrCacheQuery) {
      AddrCacheSim
        .randomStreamFireAndRespSuccess(dut.io.addrCacheRead, dut.clockDomain)
    } else {
      MiscUtils.checkConditionAlways(dut.clockDomain)(
        !dut.io.addrCacheRead.req.valid.toBoolean
//        !(dut.io.addrCacheRead.req.valid.toBoolean && dut.io.addrCacheRead.req.ready.toBoolean)
      )
      MiscUtils.checkConditionAlways(dut.clockDomain)(
        !dut.io.addrCacheRead.resp.ready.toBoolean
//        !(dut.io.addrCacheRead.resp.valid.toBoolean && dut.io.addrCacheRead.resp.ready.toBoolean)
      )

      mutable.Queue[(PSN, KeyValid, SizeValid, AccessValid, Addr)]()
    }

    streamSlaveRandomizer(dut.io.outputJoinStream, dut.clockDomain)
    onStreamFire(dut.io.outputJoinStream, dut.clockDomain) {
      outputQueue.enqueue(
        (
          dut.io.outputJoinStream.fragment.toBigInt,
          dut.io.outputJoinStream.last.toBoolean
        )
      )
    }

    fork {
      while (true) {
        val (
          psnFirstOrOnly,
          keyValid,
          sizeValid,
          accessValid,
          paAddrCacheResp
        ) = if (hasAddrCacheQuery) {
          MiscUtils.safeDeQueue(addrCacheRespQueue, dut.clockDomain)
        } else {
          (0, false, false, false, BigInt(0))
        }

        var isFirst = true
        var isLast = false
        do {
          val (psnIn, isLastIn) =
            MiscUtils.safeDeQueue(inputQueue, dut.clockDomain)
          val (dataOut, isLastOut) =
            MiscUtils.safeDeQueue(outputQueue, dut.clockDomain)
          val dataIn = if (hasAddrCacheQuery) {
            if (isFirst) {
              assert(psnIn == psnFirstOrOnly)
              assert(
                keyValid == true && sizeValid == true && accessValid == true
              )
            }

            (BigInt(psnIn) << MEM_ADDR_WIDTH) | paAddrCacheResp
          } else {
            (BigInt(psnIn) << MEM_ADDR_WIDTH)
          }
          assert(
            dataIn == dataOut,
            f"${simTime()} time: dataIn=${dataIn} should == dataOut=${dataOut}"
          )
          assert(
            isLastIn == isLastOut,
            f"${simTime()} time: isLastIn=${isLastIn} should == isLastOut=${isLastOut}"
          )
          isFirst = false
          isLast = isLastOut
        } while (!isLast)

        matchQueue.enqueue(isLast)
      }
    }
    waitUntil(matchQueue.size > MATCH_CNT)
  }
}

class SpinalEnumTest extends AnyFunSuite {
  object WorkReqOpCode extends SpinalEnum {
    val RDMA_WRITE, RDMA_WRITE_WITH_IMM, SEND, SEND_WITH_IMM, RDMA_READ,
        ATOMIC_CMP_AND_SWP, ATOMIC_FETCH_AND_ADD, LOCAL_INV, BIND_MW,
        SEND_WITH_INV, TSO, DRIVER1 = newElement()

    defaultEncoding = SpinalEnumEncoding("opt")(
      RDMA_WRITE -> 0,
      RDMA_WRITE_WITH_IMM -> 1,
      SEND -> 2,
      SEND_WITH_IMM -> 3,
      RDMA_READ -> 4,
      ATOMIC_CMP_AND_SWP -> 5,
      ATOMIC_FETCH_AND_ADD -> 6,
      LOCAL_INV -> 7,
      BIND_MW -> 8,
      SEND_WITH_INV -> 9,
      TSO -> 10,
      DRIVER1 -> 11
    )
  }

  class SpinalEnumExample extends Component {
    val io = new Bundle {
      val workReqOpCode = in(WorkReqOpCode())
    }
    val opcode = RegNext(io.workReqOpCode) init (WorkReqOpCode.TSO)

    report(L"${REPORT_TIME} time: in report ${opcode}".toSeq)
  }

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new SpinalEnumExample)

  test("FragmentStreamForkQueryJoinResp no query test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val workReqOpCode = WorkReqOpCode.RDMA_READ
      dut.io.workReqOpCode #= workReqOpCode

      dut.clockDomain.waitSampling(3)
      println(f"${simTime()} time: in sim ${workReqOpCode}")
    }
  }
}

class StreamCounterSourceTest extends AnyFunSuite {
  val finiteCnt = 32

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(
      new StreamCounterSourceWrapper(finiteCnt)
    )

  test("StreamCounterSource test 2") {
    testFunc(start = 17, stop = 5)
  }

  test("StreamCounterSource test 1") {
    testFunc(start = 1, stop = 17)
  }

  def testFunc(start: Int, stop: Int) =
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      require(
        start >= 0 && start < finiteCnt,
        f"${simTime()} time, start=${start} should >= 0 and < finiteCnt=${finiteCnt}"
      )
      require(
        stop >= 0 && stop < finiteCnt,
        f"${simTime()} time, stop=${stop} should >= 0 and < finiteCnt=${finiteCnt}"
      )
      require(
        finiteCnt >= 0,
        f"${simTime()} time, finiteCnt=${finiteCnt} should >= 0"
      )
      val scanCnt = (stop + finiteCnt - start) % finiteCnt

      val outQueue = mutable.Queue[Int]()
      dut.io.startPulse #= false
      dut.io.startPtr #= start
      dut.io.stopPtr #= stop
      streamSlaveRandomizer(dut.io.streamCounter, dut.clockDomain)
      onStreamFire(dut.io.streamCounter, dut.clockDomain) {
        outQueue.enqueue(dut.io.streamCounter.payload.toInt)
      }

      dut.clockDomain.waitSampling(5)
      dut.io.startPulse #= true
      dut.clockDomain.waitSampling()
      dut.io.startPulse #= false
      waitUntil(dut.io.done.toBoolean)
      println(f"${simTime()} time: dut.io.done=${dut.io.done.toBoolean}")

      dut.clockDomain.waitSampling()
      for (idx <- 0 until scanCnt) {
        val expectCnt = (start + idx) % finiteCnt
        val outCnt =
          MiscUtils.safeDeQueue(outQueue, dut.clockDomain) // outQueue.dequeue()
        assert(
          outCnt == expectCnt,
          f"${simTime()} time: outCnt=${outCnt} == expectCnt=${expectCnt}"
        )
//        println(
//          f"${simTime()} time: outCnt=${outCnt} == expectCnt=${expectCnt}"
//        )
      }

      dut.clockDomain.waitSampling(3)
    }
}

class StreamExtractCompanyTest extends AnyFunSuite {
  val width = 64

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(
      new StreamExtractCompanyWrapper(width)
    )

  test("StreamExtractCompany normal case") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val fragLen = 10
      val idxItr = NaturalNumber.from(1).iterator
      val inputQueue = mutable.Queue[(BigInt, Boolean)]()
      val outputQueue = mutable.Queue[(BigInt, Boolean)]()

      streamMasterDriver(dut.io.input, dut.clockDomain) {
        val idx = idxItr.next()
        dut.io.input.last #= (idx % fragLen == 0)
      }
      var isFirst = true
      var firstFrag = BigInt(0)
      onStreamFire(dut.io.input, dut.clockDomain) {
        val isLast = dut.io.input.last.toBoolean
        val fragData = dut.io.input.fragment.toBigInt
        if (isFirst) {
          firstFrag = fragData << width
          isFirst = false
        }
        if (isLast) {
          isFirst = true
        }
        val expectedOutputData = firstFrag + fragData
        inputQueue.enqueue((expectedOutputData, isLast))
      }

      streamSlaveRandomizer(dut.io.output, dut.clockDomain)
      onStreamFire(dut.io.output, dut.clockDomain) {
        outputQueue.enqueue(
          (dut.io.output.fragment.toBigInt, dut.io.output.last.toBoolean)
        )
      }

      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputQueue,
        outputQueue,
        MATCH_CNT
      )
    }
  }
}

//class FragmentStreamForkQueryJoinRespNoQueryTest extends AnyFunSuite {
//  val busWidth = BusWidth.W512
//
//  val simCfg = SimConfig.allOptimisation.withWave
//    .compile(
//      new FragmentStreamForkQueryJoinRespWrapper(hasAddrCacheQuery = false)
//    )
//
//  test("FragmentStreamForkQueryJoinResp no query test") {
//    simCfg.doSim { dut =>
//      dut.clockDomain.forkStimulus(10)
//
//      val pmtuLen = PMTU.U256
//
//      // Input to DUT
//      val maxFragNum = 37
//      val (totalFragNumItr, pktNumItr, psnItr, totalLenItr) =
//        SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)
//
//      val inputQueue = mutable.Queue[(PSN, FragLast)]()
//      val outputQueue = mutable.Queue[(PSN, FragLast)]()
//
//      pktFragStreamMasterDriverAlwaysValid(
//        dut.io.inputFragmentStream,
//        dut.clockDomain
//      ) {
//        val totalFragNum = totalFragNumItr.next()
//        val pktNum = pktNumItr.next()
//        val psnStart = psnItr.next()
//        val totalLenBytes = totalLenItr.next()
//        val outerLoopRslt = (pktNum, totalLenBytes)
////        println(
////          f"${simTime()} time: pktNum=${pktNum}, totalFragNum=${totalFragNum}, psnStart=${psnStart}, totalLenBytes=${totalLenBytes}"
////        )
//        (psnStart, totalFragNum, pktNum, pmtuLen, busWidth, outerLoopRslt)
//      } { (psn, _, isLast, _, _, _, _, _) =>
//        dut.io.inputFragmentStream.fragment #= psn
//        dut.io.inputFragmentStream.last #= isLast
//      }
//      onStreamFire(dut.io.inputFragmentStream, dut.clockDomain) {
//        inputQueue.enqueue(
//          (
//            dut.io.inputFragmentStream.fragment.toInt,
//            dut.io.inputFragmentStream.last.toBoolean
//          )
//        )
//      }
//
//      MiscUtils.checkConditionAlways(dut.clockDomain)(
//        !dut.io.addrCacheRead.req.valid.toBoolean
////        !(dut.io.addrCacheRead.req.valid.toBoolean && dut.io.addrCacheRead.req.ready.toBoolean)
//      )
//      MiscUtils.checkConditionAlways(dut.clockDomain)(
//        !dut.io.addrCacheRead.resp.ready.toBoolean
////        !(dut.io.addrCacheRead.resp.valid.toBoolean && dut.io.addrCacheRead.resp.ready.toBoolean)
//      )
//
//      streamSlaveAlwaysReady(dut.io.outputJoinStream, dut.clockDomain)
//      onStreamFire(dut.io.outputJoinStream, dut.clockDomain) {
//        val outputData = dut.io.outputJoinStream.fragment.toBigInt >> MEM_ADDR_WIDTH
//        outputQueue.enqueue(
//          (outputData.toInt, dut.io.outputJoinStream.last.toBoolean)
//        )
//      }
//
//      MiscUtils.checkInputOutputQueues(
//        dut.clockDomain,
//        inputQueue,
//        outputQueue,
//        MATCH_CNT
//      )
//    }
//  }
//}
