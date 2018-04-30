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

      val fetch_mem_inst   = Bool(OUTPUT)
      val execute_mem_inst = Bool(OUTPUT)

      val fetch_inst       = UInt(OUTPUT, xLen)
      val fetch_pc         = UInt(OUTPUT, vaddrBitsExtended)
      val fetch_valid      = Bool(OUTPUT)
      val fetch_ready      = Bool(INPUT)

      val orig_rob_tail    = UInt(INPUT, 4)
      val orig_ldq_tail    = UInt(INPUT, 2)
      val orig_stq_tail    = UInt(INPUT, 2)

      val rob_valid        = Bool(INPUT)
      val rob_data         = UInt(INPUT, xLen)
      val rob_empty        = Bool(INPUT)
      val rob_flush        = Bool(OUTPUT)

      val laq_full         = Bool(INPUT)
      val stq_full         = Bool(INPUT)

      val memreq           = new DecoupledIO(new FpgaMemReq())
      val memresp          = new DecoupledIO(new FpgaMemResp()).flip()

      val memreq_rob_idx   = UInt(OUTPUT, 4)
      val memreq_ldq_idx   = UInt(OUTPUT, 2)
      val memreq_stq_idx   = UInt(OUTPUT, 2)

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

   val archRegsValid = Reg(init = UInt(0, numRegisters))
   val archRegsDone = Reg(init = UInt(0, numRegisters))

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
   val fetch_pc_reg = RegInit(0.U(vaddrBitsExtended.W))
   val orig_pc_reg = RegInit(0.U(vaddrBitsExtended.W))

   val regReqIdx = Reg(init = UInt(0, log2Up(numRegisters)))
   val regRespIdx = Reg(init = UInt(0, log2Up(numRegisters)))

   // Control the overall user logic state.
   val userStart = RegInit(false.B)
   val userDone = RegInit(false.B) // Wire(Bool())

   val stallCnt = RegInit(0.U(32.W))
   val runnable_reg = RegInit(false.B)
   val fetch_inst_reg = RegInit(0.U(xLen.W))
   val fetch_valid_reg = RegInit(false.B)

   val resetInternal = RegInit(false.B)

   val fetch_mem_inst_reg = Reg(init = Bool(false))
   val fetch_mem_inst_start = Reg(init = Bool(false))

   val orig_rob_tail_reg = Reg(init = UInt(0, 4))
   val orig_ldq_tail_reg = Reg(init = UInt(0, 2))
   val orig_stq_tail_reg = Reg(init = UInt(0, 2))

   val memreq_bits_reg = Reg(new FpgaMemReq())
   val memreq_valid_reg = Reg(init=false.B)

   val memreq_rob_idx_reg = Reg(init = UInt(0, 4))
   val memreq_ldq_idx_reg = Reg(init = UInt(0, 2))
   val memreq_stq_idx_reg = Reg(init = UInt(0, 2))

   // PC value of the jump_to_kernel instruction: 0x0080001bb0
   // check: $TOPDIR/install/riscv-bmarks/simple.riscv.dump
   when (io.currentPC(15, 0) === UInt(0x1bb0)) {
     printf("FOUND TARGET!\n")
     runnable_reg := true.B
     // TODO(aryap): Constant?
     fetch_pc_reg := io.currentPC
     orig_pc_reg := io.currentPC
   }

   when (runnable_reg) {
     stallCnt := stallCnt + 1.U
   }

   io.runnable := runnable_reg
   io.fetch_inst := fetch_inst_reg
   io.fetch_valid := fetch_valid_reg
   io.fetch_pc := fetch_pc_reg

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
      regReqIdx := UInt(0)
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
      regRespIdx := UInt(0)
   }

   // Once after we're done and the CPU is ready for an instruction, we feed in
   // the return jump to continue execution.
   val returnIdx = RegInit(0.U(log2Up(numReturnInstrs).W))
   when (userDone && io.fetch_ready) {
      when (returnIdx < numReturnInstrs.U) {
         fetch_valid_reg := true.B
         fetch_inst_reg := returnInstrs(returnIdx)
         returnIdx := returnIdx + 1.U
         fetch_pc_reg := orig_pc_reg
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

   val simple = Module(new simple(vaddrBitsExtended, xLen))
   simple.io.RegA0 := registers(0)
   simple.io.RegA1 := registers(1)
   simple.io.RegA2 := registers(2)
   simple.io.RegA3 := registers(3)
   simple.io.RegA4 := registers(4)
   simple.io.RegA5 := registers(5)
   simple.io.RegA6 := registers(6)

   val rob_flush_start = Reg(init = Bool(false))
   val rob_flush_start_delayed = Reg(init = Bool(false))

   rob_flush_start_delayed := rob_flush_start

   // TODO: should use the done signal from the kernel here ... Have to figure
   // out when is safe to set the done signal (e.g. wait until all outstanding
   // memory transactions complete)
   //when (simple.io.done) {
   when (stallCnt === 800.U) { // fake done signal
     rob_flush_start := true.B
   }

   when (!rob_flush_start_delayed && rob_flush_start) {
     io.rob_flush := true.B
     fetch_mem_inst_reg := false.B
     fetch_mem_inst_start := false.B
     fetch_inst_reg := 0.U
     fetch_valid_reg := false.B
   }
   .otherwise {
     io.rob_flush := false.B
   }

   // after flushing rob, we can issue return insts to get back to BOOM
   when (rob_flush_start && io.rob_empty) {
     userDone := true.B
     rob_flush_start := false.B
   }

   val userStart_delayed = RegInit(false.B)
   userStart_delayed := userStart

   val fetchRespDone_delayed = Reg(init = false.B)
   fetchRespDone_delayed := fetchRespDone

   // start executing kernel after we finish with fetching registers
   when (!userStart_delayed && userStart) {
      simple.io.start := true.B
      fetch_mem_inst_start := true.B
      orig_rob_tail_reg := io.orig_rob_tail
      orig_ldq_tail_reg := io.orig_ldq_tail
      orig_stq_tail_reg := io.orig_stq_tail
      simple.io.start := true.B
      memreq_ldq_idx_reg := io.orig_ldq_tail
      memreq_stq_idx_reg := io.orig_stq_tail
   }
   .otherwise {
      simple.io.start := false.B
   }

   val memInstrs = Mem(2, UInt(xLen.W))
   val memInstrIdx = Reg(init = UInt(0, 32.W))
   val memInstrCnt = Reg(init = UInt(0, 32))
   memInstrs(0) := "h00052883".U
   memInstrs(1) := "h0115a023".U

   // Keep fetching memory instructions until the kernel finishes
   when (fetch_mem_inst_start && io.fetch_ready) {
     fetch_mem_inst_reg := true.B
     memInstrCnt := memInstrCnt + 1.U
     fetch_inst_reg := memInstrs(memInstrIdx)
     fetch_valid_reg := true.B
     fetch_pc_reg := memInstrCnt
   }

   when (memInstrIdx === 1.U && io.fetch_ready) {
     memInstrIdx := 0.U
   } .elsewhen (fetch_mem_inst_start && io.fetch_ready) {
     memInstrIdx := memInstrIdx + 1.U
   }

   io.fetch_mem_inst := fetch_mem_inst_reg

   //val memreq_arb = Module(new Arbiter(new FpgaMemReq(), 2))
   val load_memreq_queue = Module(new Queue(new FpgaMemReq(), 2))
   val store_memreq_queue = Module(new Queue(new FpgaMemReq(), 2))

   //val mem_order = Reg(init=Vec.fill(2){Bool(false)})
   // Reset mem_order when all memory transactions are fired
   //when (mem_order(0) && mem_order(1)) {
   //  mem_order(0) := false.B
   //  mem_order(1) := false.B
   //}

   when (load_memreq_queue.io.deq.valid && load_memreq_queue.io.deq.ready) {
     //mem_order(0) := true.B
     memreq_bits_reg := load_memreq_queue.io.deq.bits
     memreq_valid_reg := load_memreq_queue.io.deq.valid
     memreq_rob_idx_reg := load_memreq_queue.io.deq.bits.tag + orig_rob_tail_reg
     memreq_ldq_idx_reg := load_memreq_queue.io.deq.bits.tag >> 1.U
   }
   .elsewhen (store_memreq_queue.io.deq.valid && store_memreq_queue.io.deq.ready) {
     //mem_order(1) := true.B
     memreq_bits_reg := store_memreq_queue.io.deq.bits
     memreq_valid_reg := store_memreq_queue.io.deq.valid
     memreq_rob_idx_reg := store_memreq_queue.io.deq.bits.tag + orig_rob_tail_reg
     memreq_stq_idx_reg := store_memreq_queue.io.deq.bits.tag >> 1.U
   }
   .otherwise {
     memreq_valid_reg := false.B
   }
   //io.memreq.bits := memreq_arb.io.out.bits
   //io.memreq.valid := memreq_arb.io.out.valid

   io.memreq.bits := memreq_bits_reg
   io.memreq.valid := memreq_valid_reg
   io.memreq_rob_idx := memreq_rob_idx_reg
   io.memreq_ldq_idx := memreq_ldq_idx_reg
   io.memreq_stq_idx := memreq_stq_idx_reg

   //memreq_arb.io.in(0).bits := load_memreq_queue.io.deq.bits
   //memreq_arb.io.in(0).valid := load_memreq_queue.io.deq.valid
   //load_memreq_queue.io.deq.ready := !io.laq_full & !mem_order(0) & !mem_order(1)
   load_memreq_queue.io.deq.ready := (load_memreq_queue.io.deq.bits.tag < memInstrCnt) && (stallCnt(0) === 0.U)

   //memreq_arb.io.in(1).bits := store_memreq_queue.io.deq.bits
   //memreq_arb.io.in(1).valid := store_memreq_queue.io.deq.valid
   //store_memreq_queue.io.deq.ready := !io.stq_full & mem_order(0) & !mem_order(1)
   store_memreq_queue.io.deq.ready := (store_memreq_queue.io.deq.bits.tag < memInstrCnt) && (stallCnt(0) === 1.U)

   load_memreq_queue.io.enq.bits.addr := simple.io.mem_p0_addr.bits
   load_memreq_queue.io.enq.bits.is_load := true.B
   load_memreq_queue.io.enq.bits.is_store := false.B
   load_memreq_queue.io.enq.bits.tag := simple.io.mem_p0_addr_tag
   load_memreq_queue.io.enq.bits.data := 0.U
   load_memreq_queue.io.enq.bits.mem_cmd := M_XRD

   store_memreq_queue.io.enq.bits.addr := simple.io.mem_p1_addr.bits
   store_memreq_queue.io.enq.bits.is_load := false.B
   store_memreq_queue.io.enq.bits.is_store := true.B
   store_memreq_queue.io.enq.bits.tag := simple.io.mem_p1_addr_tag
   store_memreq_queue.io.enq.bits.data := simple.io.mem_p1_data_out.bits
   store_memreq_queue.io.enq.bits.mem_cmd := M_XWR

   load_memreq_queue.io.enq.valid := simple.io.mem_p0_addr.valid
   store_memreq_queue.io.enq.valid := simple.io.mem_p1_addr.valid
   simple.io.mem_p0_addr.ready := load_memreq_queue.io.enq.ready
   simple.io.mem_p1_addr.ready := store_memreq_queue.io.enq.ready
   simple.io.mem_p1_data_out.ready := store_memreq_queue.io.enq.ready

   simple.io.mem_p0_data_in.valid := (io.memresp.bits.tag === simple.io.mem_p0_data_in_tag) & io.memresp.valid
   simple.io.mem_p0_data_in.bits := io.memresp.bits.data

   //simple.io.mem_p1_data_out.valid := (io.memresp.bits.tag === 20.U) & io.memresp.valid

   printf("\n")
   for (i <- 0 to numRegisters - 1) {
     printf("[FPGA] REGISTER[%d]: ARCH:%d VALUE 0x%x\n", i.U, archRegsRequired(i.U), registers(i.U));
   }

   printf("\n")
   printf("""[FPGA]... runnable: %d, stallCnt: %d,
           [REG FETCH] regReqIdx: %d, regRespIdx: %d, fetchStart: %d,
           [REG FETCH] fetchReqDone: %d, fetchRespDone: %d, fetch_inst_reg: 0x%x, fetch_valid: %d, fetch_ready: %d,
           rob_valid: %d, rob_data: 0x%x, currentPC: 0x%x, fetch_pc_reg: 0x%x,
           [USER LOGIC] userStart: %d, userDone: %d,
           [SIMPLE]simple.io.start=%d, simple.io.done=%d,
           [RETURN] returnIdx=%d, internalReset=%d,
           io.memreq.bits.addr=0x%x, io.memreq.bits.is_load=%d, io.memreq.bits.is_store=%d,
           io.memreq.bits.data=0x%x, io.memreq.bits.tag=%d,
           io.memreq.valid=%d,
           io.memresp.data=0x%x, io.memresp.tag=%d,
           io.memresp.valid=%d,
           simple.io.mem_p0_addr.valid=%d,
           simple.io.mem_p1_addr.valid=%d,
           io.laq_full=%d, io.stq_full=%d,
           io.memreq_rob_idx=%d, io.memreq_ldq_idx=%d, io.memreq_stq_idx=%d
           memInstrCnt=%d,
           simple.io.mem_p0_addr_tag=%d, simple.io.mem_p0_data_in_tag=%d,
           simple.io.mem_p1_addr_tag=%d,
           io.rob_flush=%d, io.rob_empty=%d, rob_flush_start=%d
     """,
     io.runnable, stallCnt,
     regReqIdx, regRespIdx, fetchStart,
     fetchReqDone, fetchRespDone, fetch_inst_reg, io.fetch_valid, io.fetch_ready,
     io.rob_valid, io.rob_data, io.currentPC, fetch_pc_reg,
     userStart, userDone,
     simple.io.start, simple.io.done,
     returnIdx, internalReset,
     io.memreq.bits.addr, io.memreq.bits.is_load, io.memreq.bits.is_store,
     io.memreq.bits.data, io.memreq.bits.tag,
     io.memreq.valid,
     io.memresp.bits.data, io.memresp.bits.tag,
     io.memresp.valid,
     simple.io.mem_p0_addr.valid,
     simple.io.mem_p1_addr.valid,
     io.laq_full, io.stq_full,
     io.memreq_rob_idx, io.memreq_ldq_idx, io.memreq_stq_idx,
     memInstrCnt,
     simple.io.mem_p0_addr_tag, simple.io.mem_p0_data_in_tag,
     simple.io.mem_p1_addr_tag,
     io.rob_flush, io.rob_empty, rob_flush_start
   )
   printf("\n")

}

// vim: ts=8 expandtab softtabstop=3 shiftwidth=3
