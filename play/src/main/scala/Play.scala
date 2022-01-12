package rdma

import spinal.core._
import spinal.lib._
// import BusWidth.BusWidth
import ConstantSettings._
import RdmaConstants._

class StreamSegmentWrapper(width: Int) extends Component {
  val io = new Bundle {
    val inputStream = slave(Stream(Fragment(Bits(width bits))))
    val fragmentNum = in(UInt(PMTU_FRAG_NUM_WIDTH bits))
    val outputStream = master(Stream(Fragment(Bits(width bits))))
  }

  io.outputStream << StreamSegment(io.inputStream, io.fragmentNum)
}

class StreamAddHeaderWrapper(width: Int) extends Component {
  val io = new Bundle {
    val inputStream = slave(Stream(Fragment(DataAndMty(width))))
    val inputHeader = slave(Stream(HeaderDataAndMty(NoData, width)))
    val outputStream = master(Stream(Fragment(HeaderDataAndMty(NoData, width))))
  }

//  val countMtyOne = CountOne(io.inputHeader.mty)
//  report(L"header MTY countMtyOne=${countMtyOne}")
  io.outputStream << StreamAddHeader(io.inputStream, io.inputHeader)
}

class StreamRemoveHeaderWrapper(width: Int) extends Component {
  val io = new Bundle {
    val inputStream = slave(Stream(Fragment(DataAndMty(width))))
    val headerLenBytes = in(UInt(log2Up(width / BYTE_WIDTH) + 1 bits))
    val outputStream = master(Stream(Fragment(DataAndMty(width))))
  }

  io.outputStream << StreamRemoveHeader(
    io.inputStream,
    io.headerLenBytes,
    width
  )
}

class FragmentStreamJoinStreamWrapper(width: Int) extends Component {
  val io = new Bundle {
    val inputFragmentStream = slave(Stream(Fragment(Bits(width bits))))
    val inputStream = slave(Stream(UInt(width bits)))
    val outputJoinStream = master(Stream(Fragment(UInt(width bits))))
  }

  val outputJoinStream =
    FragmentStreamJoinStream(io.inputFragmentStream, io.inputStream)
  io.outputJoinStream << outputJoinStream.translateWith {
    val rslt = cloneOf(io.outputJoinStream.payloadType)
    rslt := outputJoinStream._1.asUInt + outputJoinStream._2
    rslt.last := outputJoinStream.isLast
    rslt
  }
}

class SignalEdgeDrivenStreamWrapper(width: Int) extends Component {
  val io = new Bundle {
    val inputFragmentStream = slave(Stream(Fragment(Bits(width bits))))
    val outputStream = master(Stream(Bits(width bits)))
  }

  /** Send a payload at each signal rise edge,
    * clear the payload after fire.
    * No more than one signal rise before fire.
    */
  object SignalEdgeDrivenStream {
    def apply(signal: Bool): Stream[NoData] =
      new Composite(signal) {
        val rslt = Stream(NoData)
        val signalRiseEdge = signal.rise(initAt = False)
        val validReg = Reg(Bool())
          .setWhen(signalRiseEdge)
          .clearWhen(rslt.fire)
        rslt.valid := signal.rise(initAt = False) || validReg
      }.rslt
  }

  val firstBeat = io.inputFragmentStream.valid && io.inputFragmentStream.isFirst
  io.outputStream << SignalEdgeDrivenStream(firstBeat).translateWith(
    io.inputFragmentStream.payload
  )

  val fireReg = RegInit(False)
    .setWhen(io.outputStream.fire)

  val cntOverFlowReg = RegInit(False)

  val cnt = Counter(stateCount = 3)
  when(firstBeat && !cntOverFlowReg) {
    cnt.increment()
    cntOverFlowReg := cnt.willOverflow
  }
  // when(firstBeat.fall(initAt = False)) {
  when(io.inputFragmentStream.fire) {
    cnt.clear()
    fireReg.clear()
    cntOverFlowReg.clear()
  }
  StreamSink(NoData) << io.inputFragmentStream
    .continueWhen(firstBeat ? (cntOverFlowReg && fireReg) | True)
    .translateWith(NoData)
}

/*
class MergeDemuxStreamsWrapper(width: Int) extends Component {
  val io = new Bundle {
    val inputStream = slave(Stream(Fragment(DataAndMty(width))))
    val fragmentNum = in(UInt(PMTU_FRAG_NUM_WIDTH bits))
    val outputStream = master(Stream(Fragment(DataAndMty(width))))
  }
  val segInput = StreamSegment(io.inputStream, io.fragmentNum)
  require(width >= 24)
  val portCount = 3
  val cnt =
    Counter(stateCount = portCount, inc = segInput.fire && segInput.isLast)
  val demuxStreams =
    StreamDemux(segInput, select = cnt.value, portCount = portCount)
//  val s0 = demuxStreams(0)
//  val s1 = demuxStreams(1)
//  val s2 = demuxStreams(2)
  val s0 = StreamAddHeader(demuxStreams(0), B"24'hFFFFFF", B"3'b111")
  val s1 = StreamAddHeader(demuxStreams(1), B"16'hFFFF", B"2'b11")
  val s2 = StreamAddHeader(demuxStreams(2), B"8'hFF", B"1'b1")
//  io.outputStream << MergeDemuxStreams(Vec(s0, s1, s2))
  io.outputStream << s0
  StreamSink(NoData) << s1.translateWith(NoData)
  StreamSink(NoData) << s2.translateWith(NoData)
}
 */
object PlayMain {
  def main(args: Array[String]): Unit = {
    SpinalVerilog(new StreamRemoveHeaderWrapper(width = 32))
      .printPrunedIo()
      .printPruned()
  }
}
