package rdma

import spinal.core._
import spinal.lib._
import BusWidth.BusWidth
import RdmaConstants._
import ConstantSettings._
import StreamVec._

case class DevMetaData() extends Bundle {
  val maxPendingReqNum = UInt(MAX_WR_NUM_WIDTH bits)
  val maxPendingReadAtomicReqNum = UInt(MAX_WR_NUM_WIDTH bits)
  val minRnrTimeOut = UInt(RNR_TIMER_WIDTH bits)

  // TODO: remove this
  def setDefaultVal(): this.type = {
    maxPendingReqNum := PENDING_REQ_NUM
    maxPendingReadAtomicReqNum := MAX_PENDING_READ_ATOMIC_REQ_NUM
    minRnrTimeOut := MIN_RNR_TIMEOUT
    this
  }
}

case class RnrNakSeqClear() extends Bundle {
  val pulse = Bool()
}

case class RnrNak() extends Bundle {
  val psn = UInt(PSN_WIDTH bits)
  val preOpCode = Bits(OPCODE_WIDTH bits)
  val pulse = Bool()

  def findRnrPreOpCode(curOpCode: Bits) =
    new Composite(this) {
      val rslt = Bits(OPCODE_WIDTH bits)
      when(OpCode.isWriteLastReqPkt(curOpCode)) {
        rslt := OpCode.RDMA_WRITE_MIDDLE.id
      } otherwise {
        rslt := OpCode.SEND_ONLY.id
      }
    }.rslt

  // TODO: remove this
  def setDefaultVal(): this.type = {
    psn := 0
    preOpCode := OpCode.SEND_ONLY.id
    pulse := False
    this
  }
}

case class NakErr() extends Bundle {
  val seqErr = Bool()
  val invReq = Bool()
  val rmtAcc = Bool()
  val rmtOp = Bool()
  val localErr = Bool()

  def setSeqErr(): this.type = {
    seqErr := True
    this
  }

  def setInvReq(): this.type = {
    invReq := True
    this
  }

  def setRmtAcc(): this.type = {
    rmtAcc := True
    this
  }

  def setRmtOp(): this.type = {
    rmtOp := True
    this
  }

  def setLocalErr(): this.type = {
    localErr := True
    this
  }

  def setNoErr(): this.type = {
    seqErr := False
    invReq := False
    rmtAcc := False
    rmtOp := False
    localErr := False
    this
  }

  def hasFatalNak(): Bool = invReq || rmtAcc || rmtOp || localErr

  def ||(that: NakErr): NakErr = {
    val rslt = NakErr()
    rslt.seqErr := this.seqErr || that.seqErr
    rslt.invReq := this.invReq || that.invReq
    rslt.rmtAcc := this.rmtAcc || that.rmtAcc
    rslt.rmtOp := this.rmtOp || that.rmtOp
    rslt.localErr := this.localErr || that.localErr
    rslt
  }
}

case class RqNakNotifier() extends Bundle {
  val reqCheck = NakErr()
  val rnr = RnrNak()
  val pktLen = NakErr()
  val addr = NakErr()

  def hasFatalNak(): Bool =
    reqCheck.hasFatalNak() || pktLen.hasFatalNak() || addr.hasFatalNak()

  // TODO: remove this
  def setDefaultVal(): this.type = {
    reqCheck.setNoErr()
    pktLen.setNoErr()
    addr.setNoErr()
    this
  }
}

case class SqNakNotifier() extends Bundle {
  val sendWrite = NakErr()
  val read = NakErr()
  val atomic = NakErr()

  def hasFatalNak(): Bool =
    sendWrite.hasFatalNak() || read.hasFatalNak() || atomic.hasFatalNak()

  // TODO: remove this
  def setDefaultVal(): this.type = {
    sendWrite.setNoErr()
    read.setNoErr()
    atomic.setNoErr()
    this
  }
}

case class NakNotifier() extends Bundle {
  val rq = RqNakNotifier()
  val sq = SqNakNotifier()

  def hasFatalNak(): Bool = rq.hasFatalNak() || sq.hasFatalNak()
}

case class RecvQCtrl() extends Bundle {
  val stateErrFlush = Bool()
  val rnrFlush = Bool()
  val rnrTimeOut = Bool()
  val nakSeqTrigger = Bool()
  val flush = Bool()

  // TODO: remove this
  def setDefaultVal(): this.type = {
    stateErrFlush := False
    rnrFlush := False
    rnrTimeOut := True
    nakSeqTrigger := False
    flush := False
    this
  }
}

case class SendQCtrl() extends Bundle {
  val flush = Bool()
  val fence = Bool
  val psnBeforeFence = UInt(PSN_WIDTH bits)
}

case class EPsnInc() extends Bundle {
  val inc = Bool()
  val incVal = UInt(PSN_WIDTH bits)
  val preReqOpCode = Bits(OPCODE_WIDTH bits)
}

case class PsnInc() extends Bundle {
  val inc = Bool()
  val incVal = UInt(PSN_WIDTH bits)
}

case class RqPsnInc() extends Bundle {
  val epsn = EPsnInc()
  val opsn = PsnInc()
}

case class SqPsnInc() extends Bundle {
  val npsn = PsnInc()
  val opsn = PsnInc()
}

case class PsnIncNotifier() extends Bundle {
  val rq = RqPsnInc()
  val sq = SqPsnInc()
}

case class QpAttrData() extends Bundle {
  val ipv4Peer = Bits(IPV4_WIDTH bits) // IPv4 only

  val pd = Bits(PD_ID_WIDTH bits)
  val epsn = UInt(PSN_WIDTH bits)
  val npsn = UInt(PSN_WIDTH bits)
  val rqOutPsn = UInt(PSN_WIDTH bits)
  val sqOutPsn = UInt(PSN_WIDTH bits)
  val pmtu = Bits(PMTU_WIDTH bits)
  val maxPendingReadAtomicReqNum = UInt(MAX_WR_NUM_WIDTH bits)
  val maxDstPendingReadAtomicReqNum = UInt(MAX_WR_NUM_WIDTH bits)
  val sqpn = UInt(QPN_WIDTH bits)
  val dqpn = UInt(QPN_WIDTH bits)

  val nakSeqTrigger = Bool()
  val rnrTrigger = Bool()
  // The previous received request opcode of RQ
  val rqPreReqOpCode = Bits(OPCODE_WIDTH bits)

  val minRnrTimer = Bits(RNR_TIMER_WIDTH bits)
  val ackTimeout = Bits(ACK_TIMEOUT_WIDTH bits)
  val maxRetryCnt = UInt(RETRY_COUNT_WIDTH bits)

  val fence = Bool()
  val psnBeforeFence = UInt(PSN_WIDTH bits)

  val state = Bits(QP_STATE_WIDTH bits)

  val modifyMask = Bits(QP_ATTR_MASK_WIDTH bits)

  def isValid = state =/= QpState.RESET.id
  def isReset = state === QpState.RESET.id

  def initOrReset(): this.type = {
    ipv4Peer := 0
    pd := 0
    epsn := 0
    npsn := 0
    rqOutPsn := 0
    sqOutPsn := 0
    pmtu := PMTU.U1024.id
    maxPendingReadAtomicReqNum := 0
    maxDstPendingReadAtomicReqNum := 0
    sqpn := 0
    dqpn := 0

    nakSeqTrigger := False
    rnrTrigger := False

    rqPreReqOpCode := OpCode.SEND_ONLY.id
    minRnrTimer := 1 // 1 means 0.01ms
    ackTimeout := 17 // 17 means 536.8709ms
    maxRetryCnt := 3

    fence := False
    psnBeforeFence := 0

    state := QpState.RESET.id

    modifyMask := 0
    this
  }
}

case class QpStateChange() extends Bundle {
  val changeToState = Bits(QP_STATE_WIDTH bits)
  val changePulse = Bool()

  // TODO: remove this
  def setDefaultVal(): this.type = {
    changeToState := QpState.ERR.id
    changePulse := False
    this
  }
}

case class DmaReadReq() extends Bundle {
  // dmaRespOpCodeStart can only be read response, send/write/atomic request
  private val dmaRespOpCodeStart = Bits(OPCODE_WIDTH bits)
  private val sqpn = UInt(QPN_WIDTH bits)
  private val psnStart = UInt(PSN_WIDTH bits)
  private val addr = UInt(MEM_ADDR_WIDTH bits)
  private val lenBytes = UInt(RDMA_MAX_LEN_WIDTH bits)
  private val hasMultiPkts = Bool()
  private val hasImmDt = Bool()
  private val immDt = Bits(LRKEY_IMM_DATA_WIDTH bits)
  private val hasIeth = Bool()
  private val ieth = Bits(LRKEY_IMM_DATA_WIDTH bits)

  def getSendReqOpCodeStart(
      fromFirstReq: Bool,
      hasMultiPkts: Bool,
      hasImmDt: Bool,
      hasIeth: Bool
  ): Bits =
    new Composite(fromFirstReq) {
      val rslt = Bits(OPCODE_WIDTH bits)
      when(fromFirstReq) {
        when(hasMultiPkts) {
          rslt := OpCode.SEND_FIRST.id
        } otherwise {
          rslt := OpCode.SEND_ONLY.id
          when(hasImmDt) {
            rslt := OpCode.SEND_ONLY_WITH_IMMEDIATE.id
          } elsewhen (hasIeth) {
            rslt := OpCode.SEND_ONLY_WITH_INVALIDATE.id
          }
        }
      } otherwise {
        when(hasMultiPkts) {
          rslt := OpCode.SEND_MIDDLE.id
        } otherwise {
          rslt := OpCode.SEND_LAST.id
          when(hasImmDt) {
            rslt := OpCode.SEND_LAST_WITH_IMMEDIATE.id
          } elsewhen (hasIeth) {
            rslt := OpCode.SEND_LAST_WITH_INVALIDATE.id
          }
        }
      }
    }.rslt

  def setBySendReq(
      sqpn: UInt,
      psn: UInt,
      addr: UInt,
      lenBytes: UInt,
      pmtu: Bits,
      hasImmDt: Bool,
      immDt: Bits,
      hasIeth: Bool,
      ieth: Bits,
      fromFirstReq: Bool
  ): this.type = {
    assert(
      assertion = !(hasImmDt && hasIeth),
      message =
        L"hasImmDt=${hasImmDt} and hasIeth=${hasIeth} cannot be both true",
      severity = FAILURE
    )

    hasMultiPkts := lenBytes > pmtuPktLenBytes(pmtu)
    dmaRespOpCodeStart := getSendReqOpCodeStart(
      fromFirstReq,
      hasMultiPkts,
      hasImmDt,
      hasIeth
    )
    psnStart := psn
    this.sqpn := sqpn
    this.addr := addr
    this.lenBytes := lenBytes
    this.hasImmDt := hasImmDt
    this.immDt := immDt
    this.hasIeth := hasIeth
    this.ieth := ieth
    this
  }

  def getWriteReqOpCodeStart(
      fromFirstReq: Bool,
      hasMultiPkts: Bool,
      hasImmDt: Bool
  ): Bits =
    new Composite(fromFirstReq) {
      val rslt = Bits(OPCODE_WIDTH bits)
      when(fromFirstReq) {
        when(hasMultiPkts) {
          rslt := OpCode.RDMA_WRITE_FIRST.id
        } otherwise {
          rslt := OpCode.RDMA_WRITE_ONLY.id
          when(hasImmDt) {
            rslt := OpCode.RDMA_WRITE_ONLY_WITH_IMMEDIATE.id
          }
        }
      } otherwise {
        when(hasMultiPkts) {
          rslt := OpCode.RDMA_WRITE_MIDDLE.id
        } otherwise {
          rslt := OpCode.RDMA_WRITE_LAST.id
          when(hasImmDt) {
            rslt := OpCode.RDMA_WRITE_LAST_WITH_IMMEDIATE.id
          }
        }
      }
    }.rslt

  def setByWriteReq(
      sqpn: UInt,
      psn: UInt,
      addr: UInt,
      lenBytes: UInt,
      pmtu: Bits,
      hasImmDt: Bool,
      immDt: Bits,
      fromFirstReq: Bool
  ): this.type = {
    hasMultiPkts := lenBytes > pmtuPktLenBytes(pmtu)
    dmaRespOpCodeStart := getWriteReqOpCodeStart(
      fromFirstReq,
      hasImmDt,
      hasImmDt
    )
    psnStart := psn
    this.sqpn := sqpn
    this.addr := addr
    this.lenBytes := lenBytes
    this.hasImmDt := hasImmDt
    this.immDt := immDt
    hasIeth := False
    ieth := 0
    this
  }

  def getReadRespOpCodeStart(fromFirstResp: Bool, hasMultiPkts: Bool): Bits =
    new Composite(fromFirstResp) {
      val rslt = Bits(OPCODE_WIDTH bits)
      when(fromFirstResp) {
        when(hasMultiPkts) {
          rslt := OpCode.RDMA_READ_RESPONSE_FIRST.id
        } otherwise {
          rslt := OpCode.RDMA_READ_RESPONSE_ONLY.id
        }
      } otherwise {
        when(hasMultiPkts) {
          rslt := OpCode.RDMA_READ_RESPONSE_MIDDLE.id
        } otherwise {
          rslt := OpCode.RDMA_READ_RESPONSE_LAST.id
        }
      }
    }.rslt

  def setByReadReq(
      sqpn: UInt,
      psn: UInt,
      addr: UInt,
      lenBytes: UInt,
      pmtu: Bits,
      fromFirstResp: Bool
  ): this.type = {
    hasMultiPkts := lenBytes > pmtuPktLenBytes(pmtu)
    dmaRespOpCodeStart := getReadRespOpCodeStart(fromFirstResp, hasMultiPkts)
    psnStart := psn
    this.sqpn := sqpn
    this.addr := addr
    this.lenBytes := lenBytes
    hasImmDt := False
    immDt := 0
    hasIeth := False
    ieth := 0
    this
  }

  // TODO: remove this
  def setDefaultVal(): this.type = {
    dmaRespOpCodeStart := 0
    sqpn := 0
    psnStart := 0
    addr := 0
    lenBytes := 0
    hasMultiPkts := False
    hasImmDt := False
    immDt := 0
    hasIeth := False
    ieth := 0
    this
  }
}

case class DmaReadResp(busWidth: BusWidth) extends Bundle {
  val opcodeStart = Bits(OPCODE_WIDTH bits)
  val sqpn = UInt(QPN_WIDTH bits)
  val psnStart = UInt(PSN_WIDTH bits)
  val data = Bits(busWidth.id bits)
  val mty = Bits((busWidth.id / BYTE_WIDTH) bits)
  val lenBytes = UInt(RDMA_MAX_LEN_WIDTH bits)
  val hasMultiPkts = Bool()
  val hasImmDt = Bool()
  val immDt = Bits(LRKEY_IMM_DATA_WIDTH bits)
  val hasIeth = Bool()
  val ieth = Bits(LRKEY_IMM_DATA_WIDTH bits)

  // TODO: remove this
  def setDefaultVal(): this.type = {
    opcodeStart := 0
    sqpn := 0
    psnStart := 0
    data := 0
    mty := 0
    lenBytes := 0
    hasMultiPkts := False
    hasImmDt := False
    immDt := 0
    hasIeth := False
    ieth := 0
    this
  }
}

case class DmaReadReqBus() extends Bundle with IMasterSlave {
  val req = Stream(DmaReadReq())

  def >>(that: DmaReadReqBus): Unit = {
    this.req >> that.req
  }

  def <<(that: DmaReadReqBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(req)
  }
}

case class DmaReadRespBus(busWidth: BusWidth) extends Bundle with IMasterSlave {
  val resp = Stream(Fragment(DmaReadResp(busWidth)))

  def >>(that: DmaReadRespBus): Unit = {
    this.resp >> that.resp
  }

  def <<(that: DmaReadRespBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(resp)
  }
}

case class DmaReadBus(busWidth: BusWidth) extends Bundle with IMasterSlave {
  val req = Stream(DmaReadReq())
  val resp = Stream(Fragment(DmaReadResp(busWidth)))

  def arbitReq(dmaRdReqVec: Vec[Stream[DmaReadReq]]) = new Area {
    val dmaRdReqSel = StreamArbiterFactory.roundRobin.on(dmaRdReqVec)
    req <-/< dmaRdReqSel
  }

  // TODO: should demux by opcode type
  def forkResp(dmaRdRespVec: Vec[Stream[Fragment[DmaReadResp]]]) = new Area {
    dmaRdRespVec <-/< StreamFork(resp, portCount = dmaRdRespVec.size)
  }

  def arbitReqAndDemuxRespByQpn(
      dmaRdReqVec: Vec[Stream[DmaReadReq]],
      dmaRdRespVec: Vec[Stream[Fragment[DmaReadResp]]],
      qpAttrVec: Vec[QpAttrData]
  ) = new Area {
    val dmaRdReqSel = StreamArbiterFactory.roundRobin.on(dmaRdReqVec)
    req <-/< dmaRdReqSel

    val dmaRdRespOH = qpAttrVec.map(_.sqpn === resp.sqpn)
    val dmaRdRespIdx = OHToUInt(dmaRdRespOH)
    dmaRdRespVec <-/< StreamDemux(resp, dmaRdRespIdx, dmaRdRespVec.size)
  }

  def >>(that: DmaReadBus): Unit = {
    this.req >> that.req
    this.resp << that.resp
  }

  def <<(that: DmaReadBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(req)
    slave(resp)
  }
}

case class DmaWriteReq(busWidth: BusWidth) extends Bundle {
  val opcode = Bits(OPCODE_WIDTH bits)
  val sqpn = UInt(QPN_WIDTH bits)
  val psn = UInt(PSN_WIDTH bits)
  val wrId = Bits(WR_ID_WIDTH bits)
  val wrIdValid = Bool()
  val addr = UInt(MEM_ADDR_WIDTH bits)
  val data = Bits(busWidth.id bits)
  val mty = Bits((busWidth.id / BYTE_WIDTH) bits)

  // TODO: remove this
  def setDefaultVal(): this.type = {
    opcode := 0
    sqpn := 0
    psn := 0
    wrId := 0
    wrIdValid := False
    addr := 0
    mty := 0
    data := 0
    this
  }
}

case class DmaWriteResp() extends Bundle {
  val opcode = Bits(OPCODE_WIDTH bits)
  val sqpn = UInt(QPN_WIDTH bits)
  val psn = UInt(PSN_WIDTH bits)
  val wrId = Bits(WR_ID_WIDTH bits)
  val len = UInt(RDMA_MAX_LEN_WIDTH bits)

  // TODO: remove this
  def setDefaultVal(): this.type = {
    opcode := 0
    sqpn := 0
    psn := 0
    wrId := 0
    len := 0
    this
  }
}

case class DmaWriteReqBus(busWidth: BusWidth) extends Bundle with IMasterSlave {
  val req = Stream(Fragment(DmaWriteReq(busWidth)))

  def >>(that: DmaWriteReqBus): Unit = {
    this.req >> that.req
  }

  def <<(that: DmaWriteReqBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(req)
  }
}

case class DmaWriteRespBus() extends Bundle with IMasterSlave {
  val resp = Stream(DmaWriteResp())

  def demuxRespByQpn(
      dmaWrRespVec: Vec[Stream[DmaWriteResp]],
      qpAttrVec: Vec[QpAttrData]
  ) = new Area {
    val dmaWrRespOH = qpAttrVec.map(_.sqpn === resp.sqpn)
    val dmaWrRespIdx = OHToUInt(dmaWrRespOH)
    dmaWrRespVec <-/< StreamDemux(resp, dmaWrRespIdx, dmaWrRespVec.size)
  }

  def >>(that: DmaWriteRespBus): Unit = {
    this.resp >> that.resp
  }

  def <<(that: DmaWriteRespBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(resp)
  }
}

case class DmaWriteBus(busWidth: BusWidth) extends Bundle with IMasterSlave {
  val req = Stream(Fragment(DmaWriteReq(busWidth)))
  val resp = Stream(DmaWriteResp())

  def arbitReqAndDemuxRespByQpn(
      dmaWrReqVec: Vec[Stream[Fragment[DmaWriteReq]]],
      dmaWrRespVec: Vec[Stream[DmaWriteResp]],
      qpAttrVec: Vec[QpAttrData]
  ) = new Area {
    arbitReq(dmaWrReqVec)

    val dmaWrRespOH = qpAttrVec.map(_.sqpn === resp.sqpn)
    val dmaWrRespIdx = OHToUInt(dmaWrRespOH)
    dmaWrRespVec <-/< StreamDemux(resp, dmaWrRespIdx, dmaWrRespVec.size)
  }

  def arbitReq(dmaWrReqVec: Vec[Stream[Fragment[DmaWriteReq]]]) =
    new Area {
      val dmaWrReqSel =
        StreamArbiterFactory.roundRobin.fragmentLock.on(dmaWrReqVec)
      req <-/< dmaWrReqSel
    }

  // TODO: should demux by opcode type
  def forkResp(dmaWrRespVec: Vec[Stream[DmaWriteResp]]) = new Area {
    dmaWrRespVec <-/< StreamFork(resp, portCount = dmaWrRespVec.size)
  }

  def >>(that: DmaWriteBus): Unit = {
    this.req >> that.req
    this.resp << that.resp
  }

  def <<(that: DmaWriteBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(req)
    slave(resp)
  }
}

case class DmaBus(busWidth: BusWidth) extends Bundle with IMasterSlave {
  val rd = DmaReadBus(busWidth)
  val wr = DmaWriteBus(busWidth)

  def >>(that: DmaBus): Unit = {
    this.rd >> that.rd
    this.wr >> that.wr
  }

  def <<(that: DmaBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(rd, wr)
  }
}

case class SqDmaBus(busWidth: BusWidth) extends Bundle with IMasterSlave {
  val sq = DmaReadBus(busWidth)
  val retry = DmaReadBus(busWidth)

  def dmaRdReqVec: Vec[Stream[DmaReadReq]] = {
    Vec(sq.req, retry.req)
  }

  def dmaRdRespVec: Vec[Stream[Fragment[DmaReadResp]]] = {
    Vec(sq.resp, retry.resp)
  }

  def >>(that: SqDmaBus): Unit = {
    this.sq >> that.sq
    this.retry >> that.retry
  }

  def <<(that: SqDmaBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(sq, retry)
  }
}

case class RqDmaBus(busWidth: BusWidth) extends Bundle with IMasterSlave {
  val sendWrite = DmaWriteBus(busWidth)
  val dupRead = DmaReadBus(busWidth)
  val read = DmaReadBus(busWidth)
  val atomic = DmaBus(busWidth)

  def dmaWrReqVec: Vec[Stream[Fragment[DmaWriteReq]]] = {
    Vec(sendWrite.req, atomic.wr.req)
  }

  def dmaWrRespVec: Vec[Stream[DmaWriteResp]] = {
    Vec(sendWrite.resp, atomic.wr.resp)
  }

  def dmaRdReqVec: Vec[Stream[DmaReadReq]] = {
    Vec(read.req, dupRead.req, atomic.rd.req)
  }

  def dmaRdRespVec: Vec[Stream[Fragment[DmaReadResp]]] = {
    Vec(read.resp, dupRead.resp, atomic.rd.resp)
  }

  override def asMaster(): Unit = {
    master(sendWrite, read, dupRead, atomic)
  }
}

case class ScatterGather() extends Bundle {
  val va = UInt(MEM_ADDR_WIDTH bits)
  val pa = UInt(MEM_ADDR_WIDTH bits)
  val lkey = Bits(LRKEY_IMM_DATA_WIDTH bits)
  val lenBytes = UInt(RDMA_MAX_LEN_WIDTH bits)
  // next is physical address to next ScatterGather in main memory
  val next = UInt(MEM_ADDR_WIDTH bits)

  def hasNext: Bool = {
    next === INVALID_SG_NEXT_ADDR
  }

  // TODO: remove this
  def setDefaultVal(): this.type = {
    va := 0
    pa := 0
    lkey := 0
    lenBytes := 0
    next := 0
    this
  }
}

case class ScatterGatherList() extends Bundle {
  val first = ScatterGather()
  val sgNum = UInt(MAX_SG_LEN_WIDTH bits)

  // TODO: remove this
  def setDefaultVal(): this.type = {
    first.setDefaultVal()
    sgNum := 0
    this
  }
}

case class WorkReq() extends Bundle {
  val id = Bits(WR_ID_WIDTH bits)
  val opcode = Bits(WR_OPCODE_WIDTH bits)
  val raddr = UInt(MEM_ADDR_WIDTH bits)
  val rkey = Bits(LRKEY_IMM_DATA_WIDTH bits)
  val solicited = Bool()
  val sqpn = UInt(QPN_WIDTH bits)
  val ackreq = Bool()
  val fence = Bool()
  val swap = Bits(LONG_WIDTH bits)
  val comp = Bits(LONG_WIDTH bits)
  val immDt = Bits(LRKEY_IMM_DATA_WIDTH bits)
  val rkeyToInvalidate = Bits(LRKEY_IMM_DATA_WIDTH bits)

  // TODO: assume single SG, if SGL, pa, len and lkey should come from SGL
  val pa = UInt(MEM_ADDR_WIDTH bits)
  val lenBytes = UInt(RDMA_MAX_LEN_WIDTH bits)
  val lkey = Bits(LRKEY_IMM_DATA_WIDTH bits)

  // TODO: remove this
  def setDefaultVal(): this.type = {
    id := 0
    opcode := 0
    raddr := 0
    rkey := 0
    solicited := False
    sqpn := 0
    ackreq := False
    fence := False
    swap := 0
    comp := 0
    immDt := 0
    rkeyToInvalidate := 0

    pa := 0
    lenBytes := 0
    lkey := 0
    this
  }
}

case class RecvWorkReq() extends Bundle {
  val sqpn = UInt(QPN_WIDTH bits)
  val id = Bits(WR_ID_WIDTH bits)
  val addr = UInt(MEM_ADDR_WIDTH bits)
  val lkey = Bits(LRKEY_IMM_DATA_WIDTH bits)
  // TODO: assume single SG
  val len = UInt(RDMA_MAX_LEN_WIDTH bits)

  // TODO: remove this
  def setDefaultVal(): this.type = {
    sqpn := 0
    id := 0
    addr := 0
    lkey := 0
    len := 0
    this
  }
}

case class CachedWorkReq() extends Bundle {
  val workReq = WorkReq()
  val psnStart = UInt(PSN_WIDTH bits)
  val pktNum = UInt(PSN_WIDTH bits)

//  // TODO: remove this
//  def toRcReq(): RcReq = {
//    val rcReq = RcReq()
//    rcReq.psn := psnStart
//    rcReq.rnrCnt := 0
//    rcReq.rtyCnt := 0
//    rcReq.opcode := workReq.opcode.resize(OPCODE_WIDTH)
//    rcReq.solicited := workReq.solicited
//    rcReq.sqpn := workReq.sqpn
//    rcReq.ackreq := workReq.ackreq
//    rcReq.len := workReq.len
//    rcReq
//  }

  // TODO: remove this
  def setDefaultVal(): this.type = {
    workReq.setDefaultVal()
    psnStart := 0
    pktNum := 0
    this
  }
}

case class WorkReqCacheQueryReq() extends Bundle {
  val psn = UInt(PSN_WIDTH bits)
}

case class WorkReqCacheQueryResp() extends Bundle {
  val cachedWorkReq = CachedWorkReq()
  val query = WorkReqCacheQueryReq()
  val found = Bool()
}

case class WorkReqCacheQueryRespBus() extends Bundle with IMasterSlave {
  val resp = Stream(WorkReqCacheQueryResp())

  override def asMaster(): Unit = {
    master(resp)
  }
}

case class WorkReqCacheQueryBus() extends Bundle with IMasterSlave {
  val req = Stream(WorkReqCacheQueryReq())
  val resp = Stream(WorkReqCacheQueryResp())

  def >>(that: WorkReqCacheQueryBus): Unit = {
    this.req >> that.req
    this.resp << that.resp
  }

  def <<(that: WorkReqCacheQueryBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(req)
    slave(resp)
  }
}

case class ReadAtomicResultCacheData() extends Bundle {
  val psnStart = UInt(PSN_WIDTH bits)
  val pktNum = UInt(PSN_WIDTH bits)
  val opcode = Bits(OPCODE_WIDTH bits)
  val pa = UInt(MEM_ADDR_WIDTH bits)
  val va = UInt(MEM_ADDR_WIDTH bits)
  val rkey = Bits(LRKEY_IMM_DATA_WIDTH bits)
  val dlen = UInt(RDMA_MAX_LEN_WIDTH bits)
  val swap = Bits(LONG_WIDTH bits)
  val comp = Bits(LONG_WIDTH bits)
  val atomicRslt = Bits(LONG_WIDTH bits)
  val done = Bool()

  // TODO: remote this
  def setDefaultVal(): this.type = {
    psnStart := 0
    pktNum := 0
    opcode := 0
    pa := 0
    va := 0
    rkey := 0
    dlen := 0
    swap := 0
    comp := 0
    atomicRslt := 0
    done := False
    this
  }
}

case class ReadAtomicResultCacheQueryReq() extends Bundle {
  val psn = UInt(PSN_WIDTH bits)
}

case class ReadAtomicResultCacheQueryResp() extends Bundle {
  val cachedData = ReadAtomicResultCacheData()
  val query = ReadAtomicResultCacheQueryReq()
  val found = Bool()
}

case class ReadAtomicResultCacheQueryReqBus() extends Bundle with IMasterSlave {
  val req = Stream(ReadAtomicResultCacheQueryReq())

//  def >>(that: ReadAtomicResultCacheQueryReqBus): Unit = {
//    this.req >> that.req
//  }
//
//  def <<(that: ReadAtomicResultCacheQueryReqBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(req)
  }
}

case class ReadAtomicResultCacheQueryRespBus()
    extends Bundle
    with IMasterSlave {
  val resp = Stream(ReadAtomicResultCacheQueryResp())

//  def >>(that: ReadAtomicResultCacheQueryRespBus): Unit = {
//    this.resp >> that.resp
//  }
//
//  def <<(that: ReadAtomicResultCacheQueryRespBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(resp)
  }
}

case class ReadAtomicResultCacheQueryBus() extends Bundle with IMasterSlave {
  val req = Stream(ReadAtomicResultCacheQueryReq())
  val resp = Stream(ReadAtomicResultCacheQueryResp())

  def >>(that: ReadAtomicResultCacheQueryBus): Unit = {
    this.req >> that.req
    this.resp << that.resp
  }

  def <<(that: ReadAtomicResultCacheQueryBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(req)
    slave(resp)
  }
}

case class SqPktCacheData() extends Bundle {
  val psn = UInt(PSN_WIDTH bits)
  val opcode = Bits(OPCODE_WIDTH bits)
  val pa = UInt(MEM_ADDR_WIDTH bits)
  val va = UInt(MEM_ADDR_WIDTH bits)
  val lkey = Bits(LRKEY_IMM_DATA_WIDTH bits)
  // TODO: each packet max size 4K
  val len = UInt(RDMA_MAX_LEN_WIDTH bits)
  val rnrCnt = UInt(RETRY_CNT_WIDTH bits)
  val retryCnt = UInt(RETRY_CNT_WIDTH bits)

  // TODO: remote this
  def setDefaultVal(): this.type = {
    psn := 0
    opcode := 0
    pa := 0
    va := 0
    lkey := 0
    len := 0
    rnrCnt := 0
    retryCnt := 0
    this
  }
}

case class SqPktCacheQueryReq() extends Bundle {
  val psn = UInt(PSN_WIDTH bits)
}

case class SqPktCacheQueryResp() extends Bundle {
  val cachedPkt = SqPktCacheData()
  val query = SqPktCacheQueryReq()
  val found = Bool()
}

case class SqPktCacheQueryReqBus() extends Bundle with IMasterSlave {
  val req = Stream(ReadAtomicResultCacheQueryReq())

  //  def >>(that: ReadAtomicResultCacheQueryReqBus): Unit = {
  //    this.req >> that.req
  //  }
  //
  //  def <<(that: ReadAtomicResultCacheQueryReqBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(req)
  }
}

case class SqPktCacheQueryRespBus() extends Bundle with IMasterSlave {
  val resp = Stream(SqPktCacheQueryResp())

  //  def >>(that: ReadAtomicResultCacheQueryRespBus): Unit = {
  //    this.resp >> that.resp
  //  }
  //
  //  def <<(that: ReadAtomicResultCacheQueryRespBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(resp)
  }
}

case class SqPktCacheQueryBus() extends Bundle with IMasterSlave {
  val req = Stream(SqPktCacheQueryReq())
  val resp = Stream(SqPktCacheQueryResp())

  def >>(that: SqPktCacheQueryBus): Unit = {
    this.req >> that.req
    this.resp << that.resp
  }

  def <<(that: SqPktCacheQueryBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(req)
    slave(resp)
  }
}

case class WorkComp() extends Bundle {
  val id = Bits(WR_ID_WIDTH bits)
  val opcode = Bits(WC_OPCODE_WIDTH bits)
  val len = UInt(RDMA_MAX_LEN_WIDTH bits)
  val sqpn = UInt(QPN_WIDTH bits)
  val dqpn = UInt(QPN_WIDTH bits)
  val flags = Bits(WC_FLAG_WIDTH bits)
  val immDataOrInvRkey = Bits(LRKEY_IMM_DATA_WIDTH bits)

  // TODO: remove this
  def setDefaultVal(): this.type = {
    id := 0
    opcode := 0
    len := 0
    sqpn := 0
    dqpn := 0
    flags := 0
    immDataOrInvRkey := 0
    this
  }
}

case class AddrCacheReadReq() extends Bundle {
  val key = Bits(LRKEY_IMM_DATA_WIDTH bits)
  val pd = Bits(PD_ID_WIDTH bits)
  // TODO: consider remove remoteOrLocalKey
  private val remoteOrLocalKey = Bool() // True: remote, False: local
  val accessType = Bits(ACCESS_TYPE_WIDTH bits)
  val va = UInt(MEM_ADDR_WIDTH bits)
  val dataLenBytes = UInt(RDMA_MAX_LEN_WIDTH bits)

  def setKeyTypeRemoteOrLocal(isRemoteKey: Bool): this.type = {
    remoteOrLocalKey := isRemoteKey
    this
  }

  // TODO: remove this
  def setDefaultVal(): this.type = {
    key := 0
    pd := 0
    remoteOrLocalKey := True
    accessType := 0
    va := 0
    dataLenBytes := 0
    this
  }
}

case class AddrCacheReadResp() extends Bundle {
  val found = Bool()
  val keyValid = Bool()
  val sizeValid = Bool()
  val va = UInt(MEM_ADDR_WIDTH bits)
  val pa = UInt(MEM_ADDR_WIDTH bits)
  // val len = UInt(RDMA_MAX_LEN_WIDTH bits)

  // TODO: remove this
  def setDefaultVal(): this.type = {
    found := False
    keyValid := False
    sizeValid := False
    va := 0
    pa := 0
    this
  }
}

case class AddrCacheReadReqBus() extends Bundle with IMasterSlave {
  val req = Stream(AddrCacheReadReq())

//  def >>(that: AddrCacheReadReqBus): Unit = {
//    this.req >> that.req
//  }
//
//  def <<(that: AddrCacheReadReqBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(req)
  }
}

case class AddrCacheReadRespBus() extends Bundle with IMasterSlave {
  val resp = Stream(AddrCacheReadResp())

//  def >>(that: AddrCacheReadRespBus): Unit = {
//    this.resp >> that.resp
//  }
//
//  def <<(that: AddrCacheReadRespBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(resp)
  }
}

case class AddrCacheReadBus() extends Bundle with IMasterSlave {
  val req = Stream(AddrCacheReadReq())
  val resp = Stream(AddrCacheReadResp())

  def >>(that: AddrCacheReadBus): Unit = {
    this.req >> that.req
    this.resp << that.resp
  }

  def <<(that: AddrCacheReadBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(req)
    slave(resp)
  }

//  def sendAddrCacheReq(reqValid: Bool,
//                       accessKey: Bits,
//                       accessType: Bits,
//                       pd: Bits,
//                       remoteOrLocalKey: Bool,
//                       va: UInt,
//                       dataLenBytes: UInt) = new Area {
//    req <-/< StreamSource()
//      .throwWhen(!reqValid)
//      .translateWith {
//        val addrCacheReadReq = AddrCacheReadReq()
//        addrCacheReadReq.key := accessKey
//        addrCacheReadReq.pd := pd
//        addrCacheReadReq.remoteOrLocalKey := remoteOrLocalKey
//        addrCacheReadReq.accessType := accessType
//        addrCacheReadReq.va := va
//        addrCacheReadReq.dataLenBytes := dataLenBytes
//        addrCacheReadReq
//      }
//  }

//  def joinWithAddrCacheRespStream[T <: Data](streamIn: Stream[T],
//                                             joinCond: Bool) =
//    new Composite(resp) {
//      val invalidStream =
//        StreamSource().translateWith(AddrCacheReadResp().setDefaultVal())
//      val addrCacheRespStream =
//        StreamMux(select = joinCond.asUInt, Vec(invalidStream, resp))
//      val joinedStream = StreamJoin(streamIn, addrCacheRespStream)
//        .pipelined(m2s = true, s2m = true)
//    }.joinedStream
}

//case class RqAddrCacheReadBus() extends Bundle with IMasterSlave {
//  val bus = AddrCacheReadBus()
//
//  def >>(that: RqAddrCacheReadBus): Unit = {
//    this.bus >> that.bus
//  }
////  val sendWrite = AddrCacheReadBus()
////  val read = AddrCacheReadBus()
////  val atomic = AddrCacheReadBus()
////
////  def >>(that: RqAddrCacheReadBus): Unit = {
////    this.sendWrite >> that.sendWrite
////    this.read >> that.read
////    this.atomic >> that.atomic
////  }
//
//  def <<(that: RqAddrCacheReadBus): Unit = that >> this
//
//  def asMaster(): Unit = {
//    master(bus)
//    // master(sendWrite, read, atomic)
//  }
//}

case class SqOrRetryAddrCacheReadBus() extends Bundle with IMasterSlave {
  val send = AddrCacheReadBus()
  val write = AddrCacheReadBus()

  def >>(that: SqOrRetryAddrCacheReadBus): Unit = {
    this.send >> that.send
    this.write >> that.write
  }

  def <<(that: SqOrRetryAddrCacheReadBus): Unit = that >> this

  def asMaster(): Unit = {
    master(send, write)
  }
}

case class RespPsnRange() extends Bundle {
  val opcode = Bits(OPCODE_WIDTH bits)
  val start = UInt(PSN_WIDTH bits)
  // end PSN is included in the range
  val end = UInt(PSN_WIDTH bits)
}

case class UdpMetaData() extends Bundle {
  val ip = Bits(IPV4_WIDTH bits) // IPv4 only
  val len = UInt(RDMA_MAX_LEN_WIDTH bits)
}

case class UdpData(busWidth: BusWidth) extends Bundle {
  val udp = UdpMetaData()
  val data = Bits(busWidth.id bits)
  val mty = Bits((busWidth.id / BYTE_WIDTH) bits)
  val sop = Bool()
}

case class UdpDataBus(busWidth: BusWidth) extends Bundle with IMasterSlave {
  val pktFrag = Stream(Fragment(UdpData(busWidth)))

  def >>(that: UdpDataBus): Unit = {
    this.pktFrag >> that.pktFrag
  }

  def <<(that: UdpDataBus): Unit = that >> this

  override def asMaster(): Unit = master(pktFrag)
}

//----------Combined packets----------//
// TODO: defined as IMasterSlave
case class RdmaDataBus(busWidth: BusWidth) extends Bundle with IMasterSlave {
  val pktFrag = Stream(Fragment(RdmaDataPacket(busWidth)))

  def >>(that: RdmaDataBus): Unit = {
    this.pktFrag >> that.pktFrag
  }

  def <<(that: RdmaDataBus): Unit = that >> this

  override def asMaster(): Unit = master(pktFrag)

  // TODO: remove this
  def setDefaultVal() = {
    val rslt = Fragment(RdmaDataPacket(busWidth))
    rslt.fragment.setDefaultVal()
    rslt.last := False
    rslt
  }

}

// DmaCommHeader has the same layout as RETH
case class DmaCommHeader() extends Bundle {
  val va = UInt(MEM_ADDR_WIDTH bits)
  val pa = UInt(MEM_ADDR_WIDTH bits)
  val lrkey = Bits(LRKEY_IMM_DATA_WIDTH bits)
  val dlen = UInt(RDMA_MAX_LEN_WIDTH bits)

  // TODO: remove this
  def setDefaultVal(): this.type = {
    va := 0
    pa := 0
    lrkey := 0
    dlen := 0
    this
  }
}

case class RqReqCheckRslt() extends Bundle {
  val psnCheckRslt = Bool()
  val isDupReq = Bool()
  val isInvReq = Bool()
  val epsn = UInt(PSN_WIDTH bits)
}

case class RqReqWithRecvBuf(busWidth: BusWidth) extends Bundle {
  val pktFrag = RdmaDataPacket(busWidth)
  // RecvWorkReq is only valid at the first or only fragment for send,
  // or valid at the last or only fragment for write imm
  val recvBufValid = Bool()
  val recvBuffer = RecvWorkReq()
}

case class RqReqWithRecvBufBus(busWidth: BusWidth)
    extends Bundle
    with IMasterSlave {
  val reqWithRecvBuf = Stream(Fragment(RqReqWithRecvBuf(busWidth)))

  def >>(that: RqReqWithRecvBufBus): Unit = {
    this.reqWithRecvBuf >> that.reqWithRecvBuf
  }

  def <<(that: RqReqWithRecvBufBus): Unit = that >> this

  override def asMaster(): Unit = master(reqWithRecvBuf)
}

case class RqReqCheckOutput(busWidth: BusWidth) extends Bundle {
  val pktFrag = RdmaDataPacket(busWidth)
  val checkRslt = RqReqCheckRslt()
}

case class RqReqCommCheckInternalRsltBus(busWidth: BusWidth)
    extends Bundle
    with IMasterSlave {
  val req = Stream(Fragment(RqReqCheckOutput(busWidth)))

  override def asMaster(): Unit = master(req)
}

case class RqReqWithRecvBufAndDmaCommHeader(busWidth: BusWidth) extends Bundle {
  val pktFrag = RdmaDataPacket(busWidth)
  // RecvWorkReq is only valid at the first or only fragment for send,
  // or valid at the last or only fragment for write imm
  val recvBufValid = Bool()
  val recvBuffer = RecvWorkReq()
  // DmaCommHeader is only valid at the first or only fragment
  val dmaHeaderValid = Bool()
  val dmaCommHeader = DmaCommHeader()
}

case class RqReqWithRecvBufAndDmaCommHeaderBus(busWidth: BusWidth)
    extends Bundle
    with IMasterSlave {
  val reqWithRecvBufAndDmaCommHeader = Stream(
    Fragment(RqReqWithRecvBufAndDmaCommHeader(busWidth))
  )

  def >>(that: RqReqWithRecvBufAndDmaCommHeaderBus): Unit = {
    this.reqWithRecvBufAndDmaCommHeader >> that.reqWithRecvBufAndDmaCommHeader
  }

  def <<(that: RqReqWithRecvBufAndDmaCommHeaderBus): Unit = that >> this

  override def asMaster(): Unit = master(reqWithRecvBufAndDmaCommHeader)
}

sealed abstract class RdmaBasePacket extends Bundle {
  // this: Bundle => // RdmaDataPacket must be of Bundle class
  val bth = BTH()
  // val eth = Bits(ETH_WIDTH bits)

}

case class DataAndMty(width: Int) extends Bundle {
  require(isPow2(width), s"width=${width} should be power of 2")
  val data = Bits(width bits)
  val mty = Bits((width / BYTE_WIDTH) bits)
}

object RdmaDataPacket {
  def apply(busWidth: BusWidth) = new RdmaDataPacket(busWidth)
}

sealed class RdmaDataPacket(busWidth: BusWidth) extends RdmaBasePacket {
  // data include BTH
  val data = Bits(busWidth.id bits)
  // mty does not include BTH
  val mty = Bits((busWidth.id / BYTE_WIDTH) bits)

  // TODO: remove this
  def setDefaultVal(): this.type = {
    bth.setDefaultVal()
    data := 0
    mty := 0
    this
  }

  def mtuWidth(pmtuEnum: Bits): Bits = {
    val pmtuBytes = Bits(log2Up(busWidth.id / BYTE_WIDTH) bits)
    switch(pmtuEnum) {
      is(PMTU.U256.id) { pmtuBytes := 256 / BYTE_WIDTH } // 32B
      is(PMTU.U512.id) { pmtuBytes := 512 / BYTE_WIDTH } // 64B
      is(PMTU.U1024.id) { pmtuBytes := 1024 / BYTE_WIDTH } // 128B
      is(PMTU.U2048.id) { pmtuBytes := 2048 / BYTE_WIDTH } // 256B
      is(PMTU.U4096.id) { pmtuBytes := 4096 / BYTE_WIDTH } // 512B
    }
    pmtuBytes
  }
}

trait ImmDtHeader extends RdmaBasePacket {
  // val immDtValid = Bool()
  val immdt = ImmDt()
}

trait RdmaReq extends RdmaBasePacket {
  val reth = RETH()
}

trait Response extends RdmaBasePacket {
  val aeth = AETH()
}

trait IethHeader extends RdmaBasePacket {
  // val iethValid = Bool()
  val ieth = IETH()
}

case class SendReq(busWidth: BusWidth)
    extends RdmaDataPacket(busWidth)
    with ImmDtHeader
    with IethHeader {}

case class WriteReq(busWidth: BusWidth)
    extends RdmaDataPacket(busWidth)
    with RdmaReq
    with ImmDtHeader {}

case class ReadReq() extends RdmaReq {
  def set(thatBth: BTH, rethBits: Bits): this.type = {
    bth := thatBth
    // TODO: verify rethBits is big endian
    reth.assignFromBits(rethBits)
    this
  }

  def set(
      dqpn: UInt,
      psn: UInt,
      va: UInt,
      rkey: Bits,
      dlen: UInt
  ): this.type = {
    val opcode = Bits(OPCODE_WIDTH bits)
    opcode := OpCode.RDMA_READ_REQUEST.id
    bth.set(opcode, dqpn, psn)
    reth.va := va
    reth.rkey := rkey
    reth.dlen := dlen
    this
  }
}

case class ReadOnlyFirstLastResp(busWidth: BusWidth)
    extends RdmaDataPacket(busWidth)
    with Response {
//  when(OpCode.isMidReadRespPkt(bth.opcode)) {
//    assert(
//      assertion = !aethValid,
//      message =
//        L"read response middle packet should have no AETH, but opcode=${bth.opcode}, aethValid=${aethValid}",
//      severity = FAILURE
//    )
//  }
}

case class ReadMidResp(busWidth: BusWidth) extends RdmaDataPacket(busWidth) {}

case class Acknowlege() extends Response {
  def setAck(ackType: AckType.AckType, psn: UInt, dqpn: UInt): this.type = {
//    val ackTypeBits = Bits(ACK_TYPE_WIDTH bits)
//    ackTypeBits := ackType.id

    val rnrTimeOut = Bits(RNR_TIMER_WIDTH bits)
    rnrTimeOut := MIN_RNR_TIMEOUT

    setAckHelper(
      ackType,
      psn,
      dqpn,
      msn = 0,
      creditCnt = 0,
      rnrTimeOut = rnrTimeOut
    )
  }

  def setAck(
      ackType: AckType.AckType,
      psn: UInt,
      dqpn: UInt,
      rnrTimeOut: Bits
  ): this.type = {
//    val ackTypeBits = Bits(ACK_TYPE_WIDTH bits)
//    ackTypeBits := ackType.id
    setAckHelper(ackType, psn, dqpn, msn = 0, creditCnt = 0, rnrTimeOut)
  }

  private def setAckHelper(
      ackType: AckType.AckType,
      psn: UInt,
      dqpn: UInt,
      msn: Int,
      creditCnt: Int,
      rnrTimeOut: Bits
  ): this.type = {
    bth.set(opcode = OpCode.ACKNOWLEDGE.id, dqpn = dqpn, psn = psn)
    aeth.set(ackType, msn, creditCnt, rnrTimeOut)
    this
  }

  // TODO: remove this
  def setDefaultVal(): this.type = {
    bth.setDefaultVal()
    aeth.setDefaultVal()
    this
  }
}

case class AtomicReq() extends RdmaBasePacket {
  val atomicEth = AtomicEth()

  def set(
      isCompSwap: Bool,
      dqpn: UInt,
      psn: UInt,
      va: UInt,
      rkey: Bits,
      comp: Bits,
      swap: Bits
  ): this.type = {
    val opcode = Bits(OPCODE_WIDTH bits)
    when(isCompSwap) {
      opcode := OpCode.COMPARE_SWAP.id
    } otherwise {
      opcode := OpCode.FETCH_ADD.id
    }

    bth.set(opcode, dqpn, psn)
    atomicEth.va := va
    atomicEth.rkey := rkey
    atomicEth.comp := comp
    atomicEth.swap := swap
    this
  }

  // TODO: remove this
  def setDefaultVal(): this.type = {
    bth.setDefaultVal()
    atomicEth.setDefaultVal()
    this
  }
}

case class AtomicResp() extends Response {
  val atomicAckETH = AtomicAckETH()

  def set(dqpn: UInt, psn: UInt, orig: Bits): this.type = {
    val opcode = Bits(OPCODE_WIDTH bits)
    opcode := OpCode.ATOMIC_ACKNOWLEDGE.id

    bth.set(opcode, dqpn, psn)
    // TODO: verify the AckType when atomic change failed
    aeth.set(AckType.NORMAL)
    atomicAckETH.orig := orig
    this
  }
  // TODO: remove this
  def setDefaultVal(): this.type = {
    bth.setDefaultVal()
    aeth.setDefaultVal()
    atomicAckETH.setDefaultVal()
    this
  }
}

case class CNP() extends RdmaBasePacket {
  val padding = CNPPadding()
}
