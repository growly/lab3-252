package boom

import Chisel._

import freechips.rocketchip.config._
import freechips.rocketchip.tile._
import freechips.rocketchip.util._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.rocket

class FpgaMemReq()(implicit p: Parameters) extends BoomBundle()(p)
  with freechips.rocketchip.rocket.constants.MemoryOpConstants
{
  val addr     = UInt(width = vaddrBitsExtended)
  val is_load  = Bool()
  val is_store = Bool()
  val tag      = UInt(width = 32)
  val data     = UInt(width = xLen)
  val mem_cmd  = UInt(width = M_SZ)
}

class FpgaMemResp()(implicit p: Parameters) extends BoomBundle()(p)
{
  val tag      = UInt(width = 32)
  val data     = UInt(width = xLen)
}

class FpgaInterface() (implicit p: Parameters) extends BoomModule()(p)
  with freechips.rocketchip.rocket.constants.MemoryOpConstants
  with HasBoomCoreParameters {

  val io = IO(new BoomBundle()(p) {
    val currentPC       = UInt(INPUT, vaddrBitsExtended)

    // High when this interface can replace an instruction sequence starting
    // at the given PC.
    val runnable        = Bool(OUTPUT)

    val fetch_inst      = UInt(OUTPUT, xLen)
    val fetch_valid     = Bool(OUTPUT)

    val rob_valid       = Bool(INPUT)
    val rob_data        = UInt(INPUT, xLen)

    val laq_full        = Bool(INPUT)
    val stq_full        = Bool(INPUT)

    val memreq          = new DecoupledIO(new FpgaMemReq())
    val memresp         = new DecoupledIO(new FpgaMemResp()).flip()

    // The number of bytes of instructions that can be skipped by activating
    // this module.
    //val reach         = Output(UInt(addrWidth.W))

    // High when the backing logic has finished computation.
    //val done          = Output(Bool())

    // High when the module should start copying data in.
    //val start         = Input(Bool())

    // Register copy interface.
    //val archRegAddr   = Output(UInt(regAddrWidth.W))
    //val regData       = Input(UInt(xLen.W))
    //val regCopyDone   = Output(Bool())

    // This is just for temp debugging.
    //val result        = Output(UInt(xLen.W))

  })

   val mem_read_reg_insts = Mem(10, UInt(32.W))
   mem_read_reg_insts(0) := UInt(0x00050533) // add a0, a0, x0
   mem_read_reg_insts(1) := UInt(0x000585b3) // add a1, a1, x0
   mem_read_reg_insts(2) := UInt(0x00060633) // add a2, a2, x0
   mem_read_reg_insts(3) := UInt(0x000686b3) // add a3, a3, x0
   mem_read_reg_insts(4) := UInt(0x00070733) // add a4, a4, x0
   mem_read_reg_insts(5) := UInt(0x000787b3) // add a5, a5, x0
   mem_read_reg_insts(6) := UInt(0x00080833) // add a6, a6, x0

   val RegA0 = RegInit(0.U(xLen.W))
   val RegA1 = RegInit(0.U(xLen.W))
   val RegA2 = RegInit(0.U(xLen.W))
   val RegA3 = RegInit(0.U(xLen.W))
   val RegA4 = RegInit(0.U(xLen.W))
   val RegA5 = RegInit(0.U(xLen.W))
   val RegA6 = RegInit(0.U(xLen.W))

   val stallCnt = RegInit(0.U(32.W))
   val runnable_reg = RegInit(false.B)
   val fetch_inst_reg = RegInit(0.U(xLen.W))
   val fetch_valid_reg = RegInit(false.B)
   val fetch_start = RegInit(false.B)
   val fetch_done = RegInit(false.B)
   val sum = RegInit(0.U(32.W))
   val cnt0 = RegInit(0.U(32.W)) // count fetch
   val cnt1 = RegInit(0.U(32.W)) // count commit
//   val memreq_addr_reg = RegInit(0.U(vaddrBitsExtended.W))
//   val memreq_store_addr_reg = RegInit(0.U(vaddrBitsExtended.W))
//   val memreq_load_addr_reg = RegInit(0.U(vaddrBitsExtended.W))
//   val memreq_is_load_reg = RegInit(false.B)
//   val memreq_is_store_reg = RegInit(false.B)
//   val memreq_data_reg = RegInit(0.U(xLen.W))
//   val memreq_tag_reg = RegInit(0.U(32.W))
//   val memreq_store_tag_reg = RegInit(0.U(32.W))
//   val memreq_load_tag_reg = RegInit(0.U(32.W))
//   // 0x8001fe28 exceeds UInt size ... have to break it down as follows
//   val test1 = Reg(UInt(vaddrBitsExtended.W))
//   val test2 = Reg(UInt(vaddrBitsExtended.W))
//   val test3 = RegInit(0x2345.U(xLen.W))
//   test1 := 0x8.U << 28
//   test2 := test1 + 0x1fe28.U
//
//   val start_memreq_load = RegInit(false.B)
//   val start_memreq_store = RegInit(false.B)
//   val memreq_load_cnt = RegInit(0.U(32.W))
//   val memreq_store_cnt = RegInit(0.U(32.W))

//   val toggle = stallCnt(0)
//   val load_en = Mux(!memreq_is_load_reg, start_memreq_load,
//                   Mux(!memreq_is_load_reg || !memreq_is_store_reg, memreq_is_load_reg,
//                     toggle === 1.U))
//   val store_en = Mux(!memreq_is_store_reg, start_memreq_store,
//                   Mux(!memreq_is_load_reg || !memreq_is_store_reg, memreq_is_store_reg,
//                     toggle === 0.U))
//
//   io.memreq.valid := (memreq_is_store_reg & !io.stq_full) |
//                      (memreq_is_load_reg & !io.laq_full)
//
//   io.memreq.bits.addr := Mux(store_en, memreq_store_addr_reg, memreq_load_addr_reg)
//   io.memreq.bits.is_load := memreq_is_load_reg & !io.laq_full & load_en
//   io.memreq.bits.is_store := memreq_is_store_reg & !io.stq_full & store_en
//   io.memreq.bits.data := memreq_data_reg
//   io.memreq.bits.tag := Mux(store_en, memreq_store_tag_reg, memreq_load_tag_reg)

   // PC value of the jump_to_kernel instruction: 0x0080001bb0
   // check: $TOPDIR/install/riscv-bmarks/simple.riscv.dump
   when (io.currentPC(15, 0) === UInt(0x1bb0)) {
     printf("FOUND TARGET!\n")
     runnable_reg := true.B
   }

   when (runnable_reg) {
     stallCnt := stallCnt + 1.U
   }

   io.runnable := runnable_reg
   io.fetch_inst := fetch_inst_reg
   io.fetch_valid := fetch_valid_reg

   val fetch_a0_start = RegInit(false.B)
   val fetch_a1_start = RegInit(false.B)
   val fetch_a2_start = RegInit(false.B)
   val fetch_a3_start = RegInit(false.B)
   val fetch_a4_start = RegInit(false.B)
   val fetch_a5_start = RegInit(false.B)
   val fetch_a6_start = RegInit(false.B)

   val fetch_a0_wait = RegInit(false.B)
   val fetch_a1_wait = RegInit(false.B)
   val fetch_a2_wait = RegInit(false.B)
   val fetch_a3_wait = RegInit(false.B)
   val fetch_a4_wait = RegInit(false.B)
   val fetch_a5_wait = RegInit(false.B)
   val fetch_a6_wait = RegInit(false.B)

   val fetch_a0_done = RegInit(false.B)
   val fetch_a1_done = RegInit(false.B)
   val fetch_a2_done = RegInit(false.B)
   val fetch_a3_done = RegInit(false.B)
   val fetch_a4_done = RegInit(false.B)
   val fetch_a5_done = RegInit(false.B)
   val fetch_a6_done = RegInit(false.B)

   //TODO: we can issue the instructions in successive cycles to effectively
   // save #cycles

   // Send instruction to BOOM at cycle 30
   when (stallCnt === 30.U) {
     fetch_a0_start := true.B
     fetch_a0_wait := true.B
   }

   // stall for 200 cycles
   when (stallCnt === 1000.U) {
     stallCnt := 0.U
     runnable_reg := false.B
   }

   when (fetch_valid_reg) {
     fetch_valid_reg := false.B
   }

   // Read a0
   when (fetch_a0_start & !fetch_a0_done) {
     fetch_inst_reg := mem_read_reg_insts(0)
     fetch_valid_reg := true.B
     fetch_a0_start := false.B
   }

   when (fetch_a0_wait & io.rob_valid & !fetch_a0_done) {
     RegA0 := io.rob_data
     fetch_a0_done := true.B
     fetch_a1_start := true.B
     fetch_a0_wait := false.B
     fetch_a1_wait := true.B
   }

   // Read a1
   when (fetch_a1_start & !fetch_a1_done) {
     fetch_inst_reg := mem_read_reg_insts(1)
     fetch_valid_reg := true.B
     fetch_a1_start := false.B
   }

   when (fetch_a1_wait & io.rob_valid & !fetch_a1_done) {
     RegA1 := io.rob_data
     fetch_a1_done := true.B
     fetch_a2_start := true.B
     fetch_a1_wait := false.B
     fetch_a2_wait := true.B
   }

   // Read a2
   when (fetch_a2_start & !fetch_a2_done) {
     fetch_inst_reg := mem_read_reg_insts(2)
     fetch_valid_reg := true.B
     fetch_a2_start := false.B
   }

   when (fetch_a2_wait & io.rob_valid & !fetch_a2_done) {
     RegA2 := io.rob_data
     fetch_a2_done := true.B
     fetch_a3_start := true.B
     fetch_a2_wait := false.B
     fetch_a3_wait := true.B
   }

   // Read a3
   when (fetch_a3_start & !fetch_a3_done) {
     fetch_inst_reg := mem_read_reg_insts(3)
     fetch_valid_reg := true.B
     fetch_a3_start := false.B
   }

   when (fetch_a3_wait & io.rob_valid & !fetch_a3_done) {
     RegA3 := io.rob_data
     fetch_a3_done := true.B
     fetch_a4_start := true.B
     fetch_a3_wait := false.B
     fetch_a4_wait := true.B
   }

   // Read a4
   when (fetch_a4_start & !fetch_a4_done) {
     fetch_inst_reg := mem_read_reg_insts(4)
     fetch_valid_reg := true.B
     fetch_a4_start := false.B
   }

   when (fetch_a4_wait & io.rob_valid & !fetch_a4_done) {
     RegA4 := io.rob_data
     fetch_a4_done := true.B
     fetch_a5_start := true.B
     fetch_a4_wait := false.B
     fetch_a5_wait := true.B
   }

   // Read a5
   when (fetch_a5_start & !fetch_a5_done) {
     fetch_inst_reg := mem_read_reg_insts(5)
     fetch_valid_reg := true.B
     fetch_a5_start := false.B
   }

   when (fetch_a5_wait & io.rob_valid & !fetch_a5_done) {
     RegA5 := io.rob_data
     fetch_a5_done := true.B
     fetch_a6_start := true.B
     fetch_a5_wait := false.B
     fetch_a6_wait := true.B
   }


   // Read a6
   when (fetch_a6_start & !fetch_a6_done) {
     fetch_inst_reg := mem_read_reg_insts(6)
     fetch_valid_reg := true.B
     fetch_a6_start := false.B
   }

   when (fetch_a6_wait & io.rob_valid & !fetch_a6_done) {
     RegA6 := io.rob_data
     fetch_a6_done := true.B
     fetch_a6_wait := false.B
   }


   val simple = Module(new simple(vaddrBitsExtended, xLen))
   simple.io.RegA0 := RegA0
   simple.io.RegA1 := RegA1
   simple.io.RegA2 := RegA2
   simple.io.RegA3 := RegA3
   simple.io.RegA4 := RegA4
   simple.io.RegA5 := RegA5
   simple.io.RegA6 := RegA6

   val simple_start = RegInit(false.B)

   when (stallCnt === 100.U) {
     simple_start := true.B
   }

   simple.io.start := simple_start

   val memreq_queue = Module(new Queue(new FpgaMemReq(), 10))
   io.memreq.bits := memreq_queue.io.deq.bits
   io.memreq.valid := memreq_queue.io.deq.valid & memreq_queue.io.deq.ready
   memreq_queue.io.deq.ready := !io.laq_full & !io.stq_full

   when (simple.io.mem_p0_addr.valid) {
     memreq_queue.io.enq.bits.addr := simple.io.mem_p0_addr.bits
     memreq_queue.io.enq.bits.is_load := true.B
     memreq_queue.io.enq.bits.is_store := false.B
     memreq_queue.io.enq.bits.tag := 10.U
     memreq_queue.io.enq.bits.data := 0.U
     memreq_queue.io.enq.bits.mem_cmd := M_XRD
   } .otherwise {
     memreq_queue.io.enq.bits.addr := simple.io.mem_p1_addr.bits
     memreq_queue.io.enq.bits.is_load := false.B
     memreq_queue.io.enq.bits.is_store := true.B
     memreq_queue.io.enq.bits.tag := 20.U
     memreq_queue.io.enq.bits.data := simple.io.mem_p1_data_out.bits
     memreq_queue.io.enq.bits.mem_cmd := M_XWR
   }

   memreq_queue.io.enq.valid := simple.io.mem_p0_addr.valid | simple.io.mem_p1_addr.valid
   simple.io.mem_p0_addr.ready := memreq_queue.io.enq.ready
   simple.io.mem_p1_addr.ready := memreq_queue.io.enq.ready
   simple.io.mem_p1_data_out.ready := memreq_queue.io.enq.ready

   simple.io.mem_p0_data_in.valid := (io.memresp.bits.tag === 10.U) & io.memresp.valid
   simple.io.mem_p0_data_in.bits := io.memresp.bits.data

   //simple.io.mem_p1_data_out.valid := (io.memresp.bits.tag === 20.U) & io.memresp.valid

   printf("\n")
   printf("""[FPGA]... runnable: %d, stallCnt: %d,
           fetch_inst=0x%x, fetch_valid=%d,
           rob_valid: %d, rob_data: 0x%x, currentPC: 0x%x,
           RegA0=0x%x, RegA1=0x%x, RegA2=0x%x,
           RegA3=0x%x, RegA4=0x%x, RegA5=0x%x, RegA6=0x%x,
           simple.io.start=%d, simple.io.done=%d,
           io.memreq.bits.addr=0x%x, io.memreq.bits.is_load=%d, io.memreq.bits.is_store=%d,
           io.memreq.bits.data=0x%x, io.memreq.bits.tag=%d,
           io.memreq.valid=%d,
           io.memresp.data=0x%x, io.memresp.tag=%d,
           io.memresp.valid=%d,
           memreq_queue.io.enq.valid=%d,
           memreq_queue.io.enq.ready=%d,
           simple.io.mem_p0_addr.valid=%d,
           simple.io.mem_p1_addr.valid=%d,
           memreq_queue.io.deq.valid=%d,
           memreq_queue.io.deq.ready=%d,
           io.laq_full=%d, io.stq_full=%d
     """,
     io.runnable, stallCnt,
     io.fetch_inst, io.fetch_valid,
     io.rob_valid, io.rob_data, io.currentPC,
     RegA0, RegA1, RegA2, RegA3, RegA4, RegA5, RegA6,
     simple.io.start, simple.io.done,
     io.memreq.bits.addr, io.memreq.bits.is_load, io.memreq.bits.is_store,
     io.memreq.bits.data, io.memreq.bits.tag,
     io.memreq.valid,
     io.memresp.bits.data, io.memresp.bits.tag,
     io.memresp.valid,
	   memreq_queue.io.enq.valid,
	   memreq_queue.io.enq.ready,
	   simple.io.mem_p0_addr.valid,
	   simple.io.mem_p1_addr.valid,
     memreq_queue.io.deq.valid,
     memreq_queue.io.deq.ready,
     io.laq_full, io.stq_full
   )
   printf("\n")

//   when (cnt0 === 5.U) {
//     // if we have read 5 CPU registers, stop fetching
//     fetch_valid_reg := false.B
//     fetch_inst_reg := UInt(0)
//     fetch_done := true.B
//     cnt0 := UInt(0)
//   } .elsewhen (fetch_start && !fetch_done) {
//     // successively read CPU registers
//     fetch_inst_reg := mem_read_reg_insts(cnt0)
//     fetch_valid_reg := true.B
//     cnt0 := cnt0 + 1.U
//   }
//
//   when (fetch_start && io.rob_valid) {
//     sum := sum + io.rob_data
//     cnt1 := cnt1 + 1.U
//   }
//
//   // All instructions are committed
//   when (cnt1 === 5.U) {
//     fetch_start := false.B
//     fetch_done := false.B
//     cnt1 := UInt(0)
//   }
//
//   // Send memory store request at cycle 50
//   when (stallCnt === 50.U) {
//     start_memreq_store := true.B
//   }
//
//   // Send memory load request at cycle 55
//   when (stallCnt === 55.U) {
//     start_memreq_load := true.B
//   }
//
//   // Stop after 10 memory requests
//   when (memreq_store_cnt === 10.U && !io.stq_full && store_en) {
//     start_memreq_store := false.B
//     memreq_store_cnt := 0.U
//     memreq_is_store_reg := false.B
//     memreq_store_tag_reg := 0.U
//   }
//   .elsewhen (start_memreq_store && !io.stq_full && store_en) {
//     memreq_store_cnt := memreq_store_cnt + 1.U
//     memreq_data_reg := test3 + memreq_store_cnt
//     memreq_is_store_reg := true.B
//     memreq_store_tag_reg := memreq_store_cnt + 10.U
//     memreq_store_addr_reg := test2 + (memreq_store_cnt << 2)
//   }
//
//   // Stop after 10 memory requests
//   when (memreq_load_cnt === 10.U && !io.laq_full && load_en) {
//     start_memreq_load := false.B
//     memreq_load_cnt := 0.U
//     memreq_is_load_reg := false.B
//     memreq_load_tag_reg := 0.U
//   }
//   .elsewhen (start_memreq_load && !io.laq_full && load_en) {
//     memreq_load_cnt := memreq_load_cnt + 1.U
//     memreq_is_load_reg := true.B
//     memreq_load_tag_reg := memreq_load_cnt + 30.U
//     memreq_load_addr_reg := test2 + (memreq_load_cnt << 2)
//   }
//
//   // stall for 150 cycles
//   when (stallCnt === 150.U) {
//     stallCnt := 0.U
//     runnable_reg := false.B
//   }
//
//   assert (!(io.memreq.bits.is_load && io.memreq.bits.is_store),
//     "Error! Both load and store are active!")
//
//   printf("\n")
//   printf("""[FPGA]... runnable: %d, stallCnt: %d, cnt0: %d, cnt1: %d,
//           fetch_start: %d, fetch_done: %d,
//           rob_valid: %d, rob_data: 0x%x, currentPC: 0x%x, sum = %d,
//           is_load: %d, is_store: %d,
//           memreq_valid: %d, memreq_addr: 0x%x,
//           memresp_valid: %d, memresp_data: 0x%x,
//           memreq_store_cnt: %d, memreq_loadcnt: %d, laq_full: %d, std_full: %d,
//           memreq_tag: %d, memresp_tag: %d,
//           memreq_store_addr_reg=0x%x, memreq_load_addr_reg=0x%x,
//           store_en=%d, load_en=%d""",
//     io.runnable, stallCnt, cnt0, cnt1,
//     fetch_start, fetch_done,
//     io.rob_valid, io.rob_data, io.currentPC, sum,
//     io.memreq.bits.is_load, io.memreq.bits.is_store,
//     io.memreq.valid, io.memreq.bits.addr,
//     io.memresp.valid, io.memresp.bits.data,
//     memreq_store_cnt, memreq_load_cnt, io.laq_full, io.stq_full,
//     io.memreq.bits.tag, io.memresp.bits.tag,
//     memreq_store_addr_reg, memreq_load_addr_reg,
//     store_en, load_en
//   )
//   printf("\n")

//  //printf(p"FpgaInterface with xLen: $xLen addrWidth: $addrWidth regAddrWidth: $regAddrWidth")
//
//  // Hardcoded by generated FPGA accelerator.
//  val numRegisters = 5;
//  val ourPC = "h42".U;
//  val ourReach = 2000;
//
//  io.reach := ourReach.U;
//
//  // In the FPGA logic we synthesise, every architectural register is referred
//  // to by its new index. This mapping is known at synthesis.
//  val archRegsRequired = Reg(Vec(numRegisters, UInt(regAddrWidth.W)))
//  archRegsRequired(0) := 23.U
//  archRegsRequired(1) := 0.U
//  archRegsRequired(2) := 12.U
//  archRegsRequired(3) := 18.U
//  archRegsRequired(4) := 9.U
//
//  val sPCWait :: sStartWait :: sRegCopy :: sRunning :: sDone :: sError :: Nil = Enum(6)
//  val state = Reg(init = sPCWait)
//
//  io.done := RegInit(false.B)
//  io.runnable := io.currentPC === ourPC
//  io.regCopyDone := RegInit(false.B)
//
//  // TODO(aryap): We need to be careful with the contract we present to the CPU
//  // core about when signals are valid and what they mean.
//  switch (state) {
//    is (sPCWait) {
//      when (io.runnable) {
//        state := sStartWait
//      }
//    }
//    is (sStartWait) {
//      when (io.start) {
//        state := sRegCopy
//      } .elsewhen (io.currentPC =/= ourPC) {
//        state := sPCWait
//      }
//    }
//    is (sRegCopy) {
//      when (io.regCopyDone) {
//        state := sRunning
//      }
//    }
//    is (sRunning) {
//      when (io.done) {
//        state := sDone
//      }
//    }
//    is (sDone) {
//      // TODO(aryap): How to reset?
//    }
//    is (sError) {
//    }
//  }
//
//  // The init value seems important here.
//  val regIndex = RegInit(UInt(log2Up(numRegisters).W), 0.U)
//
//  // TODO(aryap): So something outside of this interface needs to connect the
//  // physical register address from the Register Renaming table to the Regfile
//  // for us, and read out the value. Maybe we should just put it in this
//  // interface to minimise changes to the BOOM?
//  val registers = Reg(Vec(numRegisters, UInt(xLen.W)))
//
//  io.archRegAddr := RegInit(0.U)
//  when (state === sRegCopy && regIndex < numRegisters.U) {
//    io.archRegAddr := archRegsRequired(regIndex)
//    registers(regIndex) := io.regData
//    regIndex := regIndex + 1.U
//  }.elsewhen(state === sRegCopy && regIndex === numRegisters.U) {
//    io.regCopyDone := true.B
//  }
//
//  // This is the FPGA's implemented application logic.
//  val sumIdx = Reg(UInt(log2Up(numRegisters).W), 0.U)
//  val sum = Reg(UInt(32.W), 0.U)
//  when (state === sRunning) {
//    when (sumIdx < numRegisters.U) {
//      sum := sum + registers(sumIdx)
//      sumIdx := sumIdx + 1.U
//    }.elsewhen(sumIdx === numRegisters.U) {
//      io.done := true.B
//    }
//  }
//  io.result := sum

}
