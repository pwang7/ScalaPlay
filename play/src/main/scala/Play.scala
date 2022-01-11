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

class StreamAddHeaderWrapper(width: Int, headerWidth: Int) extends Component {
  val io = new Bundle {
    val inputStream = slave(Stream(Fragment(DataAndMty(width))))
    val header = in(Bits(headerWidth bits))
    val headerMty = in(Bits((headerWidth / BYTE_WIDTH) bits))
    val outputStream = master(Stream(Fragment(DataAndMty(width))))
  }

  io.outputStream << StreamAddHeader(io.inputStream, io.header, io.headerMty)
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

object PlayMain {
  def main(args: Array[String]): Unit = {
    SpinalVerilog(new StreamRemoveHeaderWrapper(width = 32))
      .printPrunedIo()
      .printPruned()
  }
}
