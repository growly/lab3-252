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
      val currentPC        = UInt(INPUT, vaddrBitsExtended)

      // High when this interface can replace an instruction sequence starting
      // at the given PC.
      val runnable         = Bool(OUTPUT)

      val fetch_inst       = UInt(OUTPUT, xLen)
      val fetch_pc         = UInt(OUTPUT, vaddrBitsExtended)
      val fetch_valid      = Bool(OUTPUT)
      val fetch_ready      = Bool(INPUT)

      val rob_valid        = Bool(INPUT)
      val rob_data         = UInt(INPUT, xLen)

      val laq_full         = Bool(INPUT)
      val stq_full         = Bool(INPUT)

      val memreq           = new DecoupledIO(new FpgaMemReq())
      val memresp          = new DecoupledIO(new FpgaMemResp()).flip()

      // High when the backing logic has finished computation.
      val done             = Bool(OUTPUT)

      // High when the module should start copying data in.
      //val start            = Bool(INPUT)
   })

   val internalReset = RegInit(false.B)

   // We want to successively repalce 'src1' in the ADD instruction:
   //  imm           src1  fn3 rd    opcode
   // "b000000000000_yyyyy_00000_000_00000_0110011"
   val regFetchInstrTemplate1 = "b000000000000".U(12.W)
   val regFetchInstrTemplate0 = "b000_00000_0010011".U(15.W)

   // Hardcoded by user logic.
   val regAddrWidth = 5;
   val numRegisters = 7;

   val archRegsRequired = Reg(Vec(numRegisters, UInt(regAddrWidth.W)))
   archRegsRequired(0) := 0x0a.U    // a0 01010
   archRegsRequired(1) := 0x0b.U    // a1 01011
   archRegsRequired(2) := 0x0c.U    // a2 01100
   archRegsRequired(3) := 0x0d.U    // a3 01101
   archRegsRequired(4) := 0x0e.U    // a4 01110
   archRegsRequired(5) := 0x0f.U    // a5 01111
   archRegsRequired(6) := 0x10.U    // a6 10000

   val archRegsValid = RegInit(0.U(numRegisters.W))
   val archRegsDone = RegInit(0.U(numRegisters.W))

   // The return instruction from the function we're replacing.
   //
   // TODO(aryap): It might be more useful to have the return method (an offset
   // to jump in the PC, an explicit virtual/physical address, or this)
   // configurable by the user logic.
   //
   // 0x00008067 is from simple.riscv.dump and decodes to
   //   000000000000 00001 000 00000 1100111
   //   imm          rs1   fn3 rd    opcode  
   //   0            x1        x0    jalr     (standard calling convention)
   //
   // The return address is set up by the jump to the function we're replacing,
   // e.g.
   // 0xd08ff0ef
   //   11010000100011111111 00001 1101111
   //   imm                  rd    opcode
   //                        x1    jal
   // JAL stores the address of the instruction following the jump in x1, per
   // the standard calling convention.
   //
   // TODO(aryap): *It also* places this address on the Return Address Stack in
   // some cases, which we have to replicate. And we should zero the JAL out
   // into a no-op.
   //
   // Can we fake it with:
   //   00000000000000000000   00001 0010111   auipc ra, 0     // ra = x1
   //   000000000100 00001 000 00001 0010011   addi ra, ra, 4
   val numReturnInstrs = 3
   val returnInstrs = Reg(Vec(numReturnInstrs, UInt(xLen.W)))
   returnInstrs(0) := "h00000097".U
   returnInstrs(1) := "h00408093".U
   returnInstrs(2) := "h00008067".U

   // Register data.
   val registers = Reg(Vec(numRegisters, UInt(xLen.W)))

   val fetchStart = RegInit(false.B)
   val fetchReqDone = RegInit(false.B)
   val fetchRespDone = RegInit(false.B)
   val fetch_pc = RegInit(0.U(vaddrBitsExtended.W))

   val regReqIdx = RegInit(UInt(log2Up(numRegisters).W), 0.U)
   val regRespIdx = RegInit(UInt(log2Up(numRegisters).W), 0.U)

   // Control the overall user logic state.
   val userStart = RegInit(false.B)
   val userDone = RegInit(false.B) // Wire(Bool())

   val stallCnt = RegInit(0.U(32.W))
   val runnable_reg = RegInit(false.B)
   val fetch_inst_reg = RegInit(0.U(xLen.W))
   val fetch_valid_reg = RegInit(false.B)

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
   val resetInternal = RegInit(false.B)

   // PC value of the jump_to_kernel instruction: 0x0080001bb0
   // check: $TOPDIR/install/riscv-bmarks/simple.riscv.dump
   when (io.currentPC(15, 0) === UInt(0x1bb0)) {
     printf("FOUND TARGET!\n")
     runnable_reg := true.B
     // TODO(aryap): Constant?
     fetch_pc := io.currentPC
   }

   when (runnable_reg) {
     stallCnt := stallCnt + 1.U
   }

   io.runnable := runnable_reg
   io.fetch_inst := fetch_inst_reg
   io.fetch_valid := fetch_valid_reg

   // Send instruction to BOOM at cycle 30
   // TODO(aryap): This should be on the start signal from CPU
   when (stallCnt === 30.U) {
     fetchStart := true.B
   }

   when (regReqIdx === numRegisters.U) {
      // If we have read all arch CPU registers, stop fetching.
      fetch_valid_reg := false.B
      fetch_inst_reg := UInt(0)
      fetchReqDone := true.B
      // regReqIdx := UInt(0)
   } .elsewhen (fetchStart && !fetchReqDone && io.fetch_ready) {
      fetch_valid_reg := true.B
      // Successively read CPU registers using an addi instruction.
      fetch_inst_reg := Cat(
         regFetchInstrTemplate1,
         Cat(archRegsRequired(regReqIdx), regFetchInstrTemplate0))
      regReqIdx := regReqIdx + 1.U
   }
   
   // TODO(aryap): How to explicitly tie data from completing instructions to
   // their sources? For now just assume they come back in order (fine since
   // they're all the same instruction and they issue in separate bundles).
   when (fetchStart && !fetchRespDone && io.rob_valid) {
      registers(regRespIdx) := io.rob_data
      regRespIdx := regRespIdx + 1.U
   }

   // All register read instructions have returned values.
   when (regRespIdx === numRegisters.U) {
      userStart := true.B
      fetchRespDone := true.B
      fetchStart := false.B
   }

   // Once after we're done and the CPU is ready for an instruction, we feed in
   // the return jump to continue execution.
   val returnIdx = RegInit(0.U(log2Up(numReturnInstrs).W))
   when (userDone && io.fetch_ready) {
      when (returnIdx < numReturnInstrs.U) {
         fetch_valid_reg := true.B
         fetch_inst_reg := returnInstrs(returnIdx)
         returnIdx := returnIdx + 1.U
      } .elsewhen (returnIdx === numReturnInstrs.U) {
         fetch_valid_reg := false.B
         internalReset := true.B
      }
   }
   when (internalReset) {
      internalReset := false.B
      userDone := false.B
      userStart := false.B
      fetch_valid_reg := false.B
      fetch_inst_reg := UInt(0)
      fetchReqDone := false.B
      fetchRespDone := false.B
      fetchStart := false.B
      stallCnt := 0.U
      runnable_reg := false.B
   }

   //// Once the register fetch is done, the user logic can start.
   //val sum = RegInit(0.U(32.W))
   //val sumIdx = Reg(UInt(log2Up(numRegisters).W), 0.U)
   //when (userStart && !userDone) {
   //   when (sumIdx < numRegisters.U) {
   //      sum := sum + registers(sumIdx)
   //      sumIdx := sumIdx + 1.U
   //   } .elsewhen (sumIdx === numRegisters.U) {
   //      userDone := true.B
   //   }
   //}

   val simple = Module(new simple(vaddrBitsExtended, xLen))
   simple.io.RegA0 := registers(0)
   simple.io.RegA1 := registers(1)
   simple.io.RegA2 := registers(2)
   simple.io.RegA3 := registers(3)
   simple.io.RegA4 := registers(4)
   simple.io.RegA5 := registers(5)
   simple.io.RegA6 := registers(6)

   // TODO(aryap): Instead of stalling, check if there are any outstanding
   // memory requests.
   when (stallCnt === 1000.U) {
      userDone := simple.io.done
   }

   // TODO(aryap): This should start as soon as the register copy finishes.
   //when (stallCnt === 100.U) {
   //  userStart := true.B
   //}

   simple.io.start := userStart

   val memreq_arb = Module(new Arbiter(new FpgaMemReq(), 2))
   val load_memreq_queue = Module(new Queue(new FpgaMemReq(), 2))
   val store_memreq_queue = Module(new Queue(new FpgaMemReq(), 2))

   io.memreq.bits := memreq_arb.io.out.bits
   io.memreq.valid := memreq_arb.io.out.valid

   memreq_arb.io.in(0).bits := load_memreq_queue.io.deq.bits
   memreq_arb.io.in(0).valid := load_memreq_queue.io.deq.valid
   load_memreq_queue.io.deq.ready := !io.laq_full

   memreq_arb.io.in(1).bits := store_memreq_queue.io.deq.bits
   memreq_arb.io.in(1).valid := store_memreq_queue.io.deq.valid
   store_memreq_queue.io.deq.ready := !io.stq_full

   load_memreq_queue.io.enq.bits.addr := simple.io.mem_p0_addr.bits
   load_memreq_queue.io.enq.bits.is_load := true.B
   load_memreq_queue.io.enq.bits.is_store := false.B
   load_memreq_queue.io.enq.bits.tag := 10.U
   load_memreq_queue.io.enq.bits.data := 0.U
   load_memreq_queue.io.enq.bits.mem_cmd := M_XRD

   store_memreq_queue.io.enq.bits.addr := simple.io.mem_p1_addr.bits
   store_memreq_queue.io.enq.bits.is_load := false.B
   store_memreq_queue.io.enq.bits.is_store := true.B
   store_memreq_queue.io.enq.bits.tag := 20.U
   store_memreq_queue.io.enq.bits.data := simple.io.mem_p1_data_out.bits
   store_memreq_queue.io.enq.bits.mem_cmd := M_XWR

   load_memreq_queue.io.enq.valid := simple.io.mem_p0_addr.valid
   store_memreq_queue.io.enq.valid := simple.io.mem_p1_addr.valid
   simple.io.mem_p0_addr.ready := load_memreq_queue.io.enq.ready
   simple.io.mem_p1_addr.ready := store_memreq_queue.io.enq.ready
   simple.io.mem_p1_data_out.ready := store_memreq_queue.io.enq.ready

   simple.io.mem_p0_data_in.valid := (io.memresp.bits.tag === 10.U) & io.memresp.valid
   simple.io.mem_p0_data_in.bits := io.memresp.bits.data

   //simple.io.mem_p1_data_out.valid := (io.memresp.bits.tag === 20.U) & io.memresp.valid

   // stall for 200 cycles
   // TODO(aryap): Remove, in favour of actual return instruction when done.
   //when (stallCnt === 1000.U) {
   //}

   printf("\n")
   for (i <- 0 to numRegisters - 1) {
     printf("[FPGA] REGISTER[%d]: ARCH:%d VALUE 0x%x\n", i.U, archRegsRequired(i.U), registers(i.U));
   }

   printf("\n")
   printf("""[FPGA]... runnable: %d, stallCnt: %d,
           [REG FETCH] regReqIdx: %d, regRespIdx: %d, fetchStart: %d,
           [REG FETCH] fetchReqDone: %d, fetchRespDone: %d, fetch_inst_reg: 0x%x, fetch_valid: %d, fetch_ready: %d,
           rob_valid: %d, rob_data: 0x%x, currentPC: 0x%x,
           [USER LOGIC] userStart: %d, userDone: %d,
           [SIMPLE]simple.io.start=%d, simple.io.done=%d,
           [RETURN] returnIdx=%d, internalReset=%d,
           io.memreq.bits.addr=0x%x, io.memreq.bits.is_load=%d, io.memreq.bits.is_store=%d,
           io.memreq.bits.data=0x%x, io.memreq.bits.tag=%d,
           io.memreq.valid=%d,
           io.memresp.data=0x%x, io.memresp.tag=%d,
           io.memresp.valid=%d""",
     io.runnable, stallCnt,
     regReqIdx, regRespIdx, fetchStart,
     fetchReqDone, fetchRespDone, fetch_inst_reg, io.fetch_valid, io.fetch_ready,
     io.rob_valid, io.rob_data, io.currentPC,
     userStart, userDone,
     simple.io.start, simple.io.done,
     returnIdx, internalReset,
     io.memreq.bits.addr, io.memreq.bits.is_load, io.memreq.bits.is_store,
     io.memreq.bits.data, io.memreq.bits.tag,
     io.memreq.valid,
     io.memresp.bits.data, io.memresp.bits.tag,
     io.memresp.valid
   )
   printf("\n")

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

}

// vim: ts=8 expandtab softtabstop=3 shiftwidth=3
