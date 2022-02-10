package rdma

import spinal.core._
import spinal.lib._
import BusWidth._
import ConstantSettings._
import RdmaConstants._

case class ZipOutputData(busWidth: BusWidth) extends Bundle {
  val leftValid = Bool()
  val rightValid = Bool()
  val leftOutput = Bits(busWidth.id bits)
  val rightOutput = Bits(busWidth.id bits)
}

class StreamZipByConditionWrapper(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val leftInputStream = slave(Stream(Bits(busWidth.id bits)))
    val rightInputStream = slave(Stream(Bits(busWidth.id bits)))
    val leftFireCond = in(Bool())
    val rightFireCond = in(Bool())
    val bothFireCond = in(Bool())
    val zipOutputStream = master(Stream(ZipOutputData(busWidth)))
//    val leftOutputStream = master(Stream(Bits(busWidth.id bits)))
//    val rightOutputStream = master(Stream(Bits(busWidth.id bits)))
  }

//  val (leftOutputStream, rightOutputStream) = StreamZipByCondition(
  val zipOutputStream = StreamZipByCondition(
    io.leftInputStream,
    io.rightInputStream,
    io.leftFireCond,
    io.rightFireCond,
    io.bothFireCond
  )

//  val halfWidth = busWidth.id >> 1
//  val halfWidthMask = setAllBits(halfWidth)
//  val leftUpperHalf = zipOutputStream._2 & (halfWidthMask << halfWidth)
//  val rightLowerHalf = zipOutputStream._4 & halfWidthMask
  io.zipOutputStream.arbitrationFrom(zipOutputStream)
  io.zipOutputStream.leftValid := zipOutputStream._1
  io.zipOutputStream.leftOutput := zipOutputStream._2
  io.zipOutputStream.rightValid := zipOutputStream._3
  io.zipOutputStream.rightOutput := zipOutputStream._4

//  io.leftOutputStream << leftOutputStream
//  io.rightOutputStream << rightOutputStream
}

class StreamDropHeaderWrapper(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val inputStream = slave(Stream(Fragment(Bits(busWidth.id bits))))
    val headerFragNum = in(UInt(PMTU_FRAG_NUM_WIDTH bits))
    val outputStream = master(Stream(Fragment(Bits(busWidth.id bits))))
  }

  io.outputStream << StreamDropHeader(io.inputStream, io.headerFragNum)
}

class StreamSegmentWrapper(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val inputStream = slave(Stream(Fragment(Bits(busWidth.id bits))))
    val segmentFragNum = in(UInt(PMTU_FRAG_NUM_WIDTH bits))
    val outputStream = master(Stream(Fragment(Bits(busWidth.id bits))))
  }

  io.outputStream << StreamSegment(io.inputStream, io.segmentFragNum)
}

class StreamAddHeaderWrapper(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val inputStream = slave(Stream(Fragment(DataAndMty(busWidth))))
    val inputHeader = slave(Stream(HeaderDataAndMty(NoData(), busWidth)))
    val outputStream = master(
      Stream(Fragment(HeaderDataAndMty(NoData(), busWidth)))
    )
  }

//  val countMtyOne = CountOne(io.inputHeader.mty)
//  report(L"${REPORT_TIME} time: header MTY countMtyOne=${countMtyOne}")
  io.outputStream << StreamAddHeader(io.inputStream, io.inputHeader, busWidth)
}

class StreamRemoveHeaderWrapper(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val inputStream = slave(Stream(Fragment(DataAndMty(busWidth))))
    val headerLenBytes = in(UInt(log2Up(busWidth.id / BYTE_WIDTH) + 1 bits))
    val outputStream = master(Stream(Fragment(DataAndMty(busWidth))))
  }

  io.outputStream << StreamRemoveHeader(
    io.inputStream,
    io.headerLenBytes,
    busWidth
  )
}

class FragmentStreamJoinStreamWrapper(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val inputFragmentStream = slave(Stream(Fragment(Bits(busWidth.id bits))))
    val inputStream = slave(Stream(UInt(busWidth.id bits)))
    val outputJoinStream = master(Stream(Fragment(UInt(busWidth.id bits))))
  }

  val halfWidth = busWidth.id / 2

  val outputJoinStream =
    FragmentStreamJoinStream(io.inputFragmentStream, io.inputStream)
  io.outputJoinStream << outputJoinStream.translateWith {
    val rslt = cloneOf(io.outputJoinStream.payloadType)
    rslt :=
      (outputJoinStream._1 & (setAllBits(halfWidth) << halfWidth)).asUInt +
        (outputJoinStream._2 & setAllBits(halfWidth))
    rslt.last := outputJoinStream.isLast
    rslt
  }
}

class FragmentStreamConditionalJoinStreamWrapper(busWidth: BusWidth)
    extends Component {
  val io = new Bundle {
    val inputFragmentStream = slave(Stream(Fragment(Bits(busWidth.id bits))))
    val inputStream = slave(Stream(UInt(busWidth.id bits)))
    val joinCond = in(Bool())
    val outputJoinStream = master(Stream(Fragment(UInt(busWidth.id bits))))
  }

  val halfWidth = busWidth.id / 2

  val outputJoinStream = FragmentStreamConditionalJoinStream(
    io.inputFragmentStream,
    io.inputStream,
    io.joinCond
  )
  io.outputJoinStream << outputJoinStream.translateWith {
    val rslt = cloneOf(io.outputJoinStream.payloadType)
    rslt :=
      (outputJoinStream._1 & (setAllBits(halfWidth) << halfWidth)).asUInt +
        (outputJoinStream._2 & setAllBits(halfWidth))
    rslt.last := outputJoinStream.isLast
    rslt
  }
}

class StreamConditionalFork2Wrapper(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val inputFragmentStream = slave(Stream(Fragment(Bits(busWidth.id bits))))
    val longOutputStream = master(Stream(Fragment(Bits(busWidth.id bits))))
    val shortOutputStream = master(Stream(Fragment(Bits(busWidth.id bits))))
  }

  val firstBeat = io.inputFragmentStream.valid && io.inputFragmentStream.isFirst
  val (shortOutputStream, longOutputStream) =
    StreamConditionalFork2(io.inputFragmentStream, firstBeat)
  io.longOutputStream << longOutputStream
  io.shortOutputStream << shortOutputStream
}

class StreamReqAndRespWrapper(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val on = in(Bool())
    val req = master(Stream(UInt(busWidth.id bits)))
    val resp = slave(Stream(UInt(busWidth.id bits)))
  }

  val cnt = Counter(busWidth.id bits, inc = io.req.fire)
  io.req << StreamSource().continueWhen(io.on).translateWith(cnt.value)
  StreamSink(NoData()) << io.resp.translateWith(NoData())
}

class PktFragStreamWrapper(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val input = slave(RdmaDataBus(busWidth))
    val output = master(RdmaDataBus(busWidth))
  }

  io.output.pktFrag <-/< io.input.pktFrag
}

//class FragmentStreamForkQueryJoinRespSimpleWrapper(busWidth: BusWidth)
//    extends Component {
//  val io = new Bundle {
//    val inputFragmentStream = slave(
//      Stream(Fragment(Bits(busWidth.id / 2 bits)))
//    )
////    val queryStream = master(Stream(Bits(busWidth.id / 2 bits)))
////    val respStream = slave(Stream(Bits(busWidth.id / 2 bits)))
//    val outputJoinStream = master(Stream(Fragment(Bits(busWidth.id bits))))
//  }
//
//  val queryStream = Stream(Bits(busWidth.id / 2 bits))
//  val respStream = Stream(Bits(busWidth.id / 2 bits))
//  respStream <-/< queryStream
//
//  val joinStream = FragmentStreamForkQueryJoinResp(
//    io.inputFragmentStream,
//    queryStream,
//    respStream,
//    waitQueueDepth = 4,
//    buildQuery =
//      (pktFragStream: Stream[Fragment[Bits]]) => pktFragStream.fragment,
//    queryCond = (pktFragStream: Stream[Fragment[Bits]]) => pktFragStream.isFirst,
//    expectResp = (pktFragStream: Stream[Fragment[Bits]]) => pktFragStream.valid,
//    joinRespCond =
//      (pktFragStream: Stream[Fragment[Bits]]) => pktFragStream.isLast
//  )
//
//  io.outputJoinStream << joinStream ~~ { payloadData =>
//    val result = cloneOf(io.outputJoinStream.payloadType)
//    result.fragment := (payloadData._1 ## payloadData._2)
//    result.last := payloadData.last
//    result
//  }
//}

class FragmentStreamForkQueryJoinRespWrapper extends Component {
  val io = new Bundle {
    val hasAddrCacheQuery = in(Bool())
    val inputFragmentStream = slave(Stream(Fragment(Bits(PSN_WIDTH bits))))
    val addrCacheRead = master(QpAddrCacheAgentReadBus())
    val outputJoinStream = master(
      Stream(Fragment(Bits(PSN_WIDTH + MEM_ADDR_WIDTH bits)))
    )
  }

  val joinStream = FragmentStreamForkQueryJoinResp(
    io.inputFragmentStream,
    io.addrCacheRead.req,
    io.addrCacheRead.resp,
    waitQueueDepth = 4,
    buildQuery = (pktFragStream: Stream[Fragment[Bits]]) =>
      new Composite(pktFragStream, "buildQuery") {
        val addrCacheReadReq =
          QpAddrCacheAgentReadReq().assignDontCare().allowOverride
        addrCacheReadReq.psn := pktFragStream.fragment.asUInt
      }.addrCacheReadReq,
    queryCond =
      (pktFragStream: Stream[Fragment[Bits]]) => pktFragStream.isFirst,
    expectResp = (pktFragStream: Stream[Fragment[Bits]]) =>
      new Composite(pktFragStream, "expectResp") {
        val result = pktFragStream.valid && io.hasAddrCacheQuery
      }.result,
    joinRespCond =
      (pktFragStream: Stream[Fragment[Bits]]) => pktFragStream.isLast
  )

  io.outputJoinStream << joinStream ~~ { payloadData =>
    val result = cloneOf(io.outputJoinStream.payloadType)
    val pa = io.hasAddrCacheQuery
      .mux(True -> payloadData._2.pa, False -> U(0, MEM_ADDR_WIDTH bits))
    result.fragment := (payloadData._1 ## pa)
    result.last := payloadData.last
    result
  }
}

class StreamCounterSourceWrapper(cnt: Int) extends Component {
  val width = log2Up(cnt)

  val io = new Bundle {
    val startPulse = in(Bool())
    val done = out(Bool())
    val startPtr = in(UInt(width bits))
    val stopPtr = in(UInt(width bits))
    val streamCounter = master(Stream(UInt(width bits)))
  }

  val flush = False
//  val start = U(0, width bits)
//  val stop = U(cnt - 1, width bits)
  val result =
    StreamCounterSource(io.startPulse, io.startPtr, io.stopPtr, flush, cnt)
  io.streamCounter << result._1
  io.done := result._2
}

class StreamExtractCompanyWrapper(width: Int) extends Component {
  val io = new Bundle {
    val input = slave(Stream(Fragment(Bits(width bits))))
    val output = master(Stream(Fragment(Bits(width * 2 bits))))
  }
  val output = StreamExtractCompany(
    io.input,
    companyExtractFunc = (input: Stream[Fragment[Bits]]) => {
      val result = Flow(input.fragmentType)
      result.valid := input.isFirst
      result.payload := input.payload
      result
    }
  )
  io.output << output ~~ { payloadData =>
    val result = cloneOf(io.output.payloadType)
    result.last := payloadData._1.last
    result.fragment := payloadData._2 ## payloadData._1.fragment
    result
  }
}

//class FragmentStreamForkQueryJoinRespNoQueryWrapper extends Component {
//  val io = new Bundle {
//    val inputFragmentStream = slave(Stream(Fragment(Bits(PSN_WIDTH bits))))
//    val addrCacheRead = master(QpAddrCacheAgentReadBus())
//    val outputJoinStream = master(Stream(Fragment(Bits(PSN_WIDTH bits))))
//  }
//
//  val joinStream = FragmentStreamForkQueryJoinResp(
//    io.inputFragmentStream,
//    io.addrCacheRead.req,
//    io.addrCacheRead.resp,
//    waitQueueDepth = 4,
//    buildQuery = (pktFragStream: Stream[Fragment[Bits]]) => {
//      val addrCacheReadReq =
//        QpAddrCacheAgentReadReq().assignDontCare().allowOverride
//      addrCacheReadReq.psn := pktFragStream.fragment.asUInt
//      addrCacheReadReq
//    },
//    queryCond = (pktFragStream: Stream[Fragment[Bits]]) => pktFragStream.isFirst,
//    expectResp = (_: Stream[Fragment[Bits]]) => False,
//    joinRespCond =
//      (pktFragStream: Stream[Fragment[Bits]]) => pktFragStream.isLast
//  )
//
//  io.outputJoinStream << joinStream ~~ { payloadData =>
//    val result = cloneOf(io.outputJoinStream.payloadType)
//    result.fragment := payloadData._1
//    result.last := payloadData.last
//    result
//  }
//}

class DualPortRam(busWidth: BusWidth, depth: Int) extends Component {
  val io = new Bundle {
    val clk1 = in(Bool())
    val clk2 = in(Bool())
    val we = in(Bool())
    val addrW = in(UInt(log2Up(depth) bits))
    val addrR = in(UInt(log2Up(depth) bits))
    val din = in(Bits(busWidth.id bits))
    val dout = out(Bits(busWidth.id bits))
  }

  val mem = Mem(Bits(busWidth.id bits), depth)
  val cd1 = ClockDomain(io.clk1)
  val ca1 = new ClockingArea(cd1) {
    mem.write(io.addrW, io.din, io.we)
  }
  val cd2 = ClockDomain(io.clk2)
  val ca2 = new ClockingArea(cd2) {
    io.dout := mem.readAsync(io.addrR)
  }
}

object PlayMain {
  def main(args: Array[String]): Unit = {
    SpinalVerilog(new DualPortRam(BusWidth.W32, depth = 16))
      .printPrunedIo()
      .printPruned()
  }
}
