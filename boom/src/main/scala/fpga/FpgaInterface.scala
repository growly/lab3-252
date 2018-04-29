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
   val ldq_idx  = UInt(width = 2)
   val stq_idx  = UInt(width = 2)
   val rob_idx  = UInt(width = 4)
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
   val memCnt = Reg(init = UInt(0, 32))

   // PC value of the jump_to_kernel instruction: 0x0080001bb0
   // check: $TOPDIR/install/riscv-bmarks/simple.riscv.dump
   when (io.currentPC(15, 0) === UInt(0x1bb0)) {
     printf("FOUND TARGET!\n")
     runnable_reg := true.B
     // TODO(aryap): Constant?
     fetch_pc_reg := io.currentPC
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
      //userDone := simple.io.done
      runnable_reg := false.B
      fetch_mem_inst_reg := false.B
   }

   val userStart_delayed = RegInit(false.B)
   userStart_delayed := userStart

   val fetchRespDone_delayed = Reg(init = false.B)
   fetchRespDone_delayed := fetchRespDone

   // start executing kernel after we finish with fetching registers
   when (!userStart_delayed && userStart) {
      simple.io.start := false.B
      fetch_mem_inst_start := true.B
   }.otherwise {
      simple.io.start := false.B
   }

   when (memCnt === 4.U) {
     memCnt := 0.U
     fetch_mem_inst_start := false.B
     fetch_inst_reg := 0.U
     fetch_valid_reg := false.B
   } .elsewhen (fetch_mem_inst_start) {
     fetch_mem_inst_reg := true.B
     memCnt := memCnt + 1.U
     fetch_inst_reg := 0x00052883.U
     fetch_valid_reg := true.B
     fetch_pc_reg := 0x1000.U + (memCnt << 4)
   }

   io.fetch_mem_inst := fetch_mem_inst_reg

   val execute_mem_inst_start = Reg(init = Bool(false))
   val execute_mem_inst_reg = Reg(init = Bool(false))
   val memCnt1 = Reg(init = UInt(0, 32))

   // 0x8001fe28 exceeds UInt size ... have to break it down as follows
   val test1 = Reg(UInt(vaddrBitsExtended.W))
   val test2 = Reg(UInt(vaddrBitsExtended.W))
   val test3 = RegInit(0x2345.U(xLen.W))
   test1 := 0x8.U << 28
   test2 := test1 + 0x21a18.U

   when (stallCnt === 80.U) {
     execute_mem_inst_start := true.B
   }

   val addr_reg = Reg(init = UInt(0, 32))
   val tag_reg = Reg(init = UInt(0, 32))
   val rob_idx_reg = Reg(init = UInt(0, 4))
   val ldq_idx_reg = Reg(init = UInt(0, 2))
   val stq_idx_reg = Reg(init = UInt(0, 2))

   when (memCnt1 === 4.U) {
     execute_mem_inst_start := false.B
     execute_mem_inst_reg := false.B
     memCnt1 := 0.U
   } .elsewhen (execute_mem_inst_start) {
     execute_mem_inst_reg := true.B
     memCnt1 := memCnt1 + 1.U
     addr_reg := test2 + (memCnt1 << 2)
     tag_reg := 0x1000.U + (memCnt << 4)
     rob_idx_reg := 7.U + memCnt1
   }

   when (execute_mem_inst_reg) {
     ldq_idx_reg := ldq_idx_reg + 1.U
   }

   io.memreq.bits.addr := addr_reg
   io.memreq.bits.tag := tag_reg
   io.memreq.bits.rob_idx := rob_idx_reg
   io.memreq.bits.ldq_idx := ldq_idx_reg
   io.memreq.bits.stq_idx := stq_idx_reg
   io.memreq.bits.is_load := true.B
   io.memreq.bits.is_store := false.B
   io.memreq.bits.mem_cmd := M_XRD
   io.execute_mem_inst := execute_mem_inst_reg

   //val memreq_arb = Module(new Arbiter(new FpgaMemReq(), 2))
   val load_memreq_queue = Module(new Queue(new FpgaMemReq(), 2))
   val store_memreq_queue = Module(new Queue(new FpgaMemReq(), 2))

   val mem_order = Reg(init=Vec.fill(2){Bool(false)})
   // Reset mem_order when all memory transactions are fired
   when (mem_order(0) && mem_order(1)) {
     mem_order(0) := false.B
     mem_order(1) := false.B
   }

   val memreq_bits_reg = Reg(new FpgaMemReq())
   val memreq_valid_reg = Reg(init=false.B)
//   when (load_memreq_queue.io.deq.valid && load_memreq_queue.io.deq.ready) {
//     mem_order(0) := true.B
//     memreq_bits_reg := load_memreq_queue.io.deq.bits
//     memreq_valid_reg := load_memreq_queue.io.deq.valid
//   }
//   .elsewhen (store_memreq_queue.io.deq.valid && store_memreq_queue.io.deq.ready) {
//     mem_order(1) := true.B
//     memreq_bits_reg := store_memreq_queue.io.deq.bits
//     memreq_valid_reg := store_memreq_queue.io.deq.valid
//   }
//   .otherwise {
//     memreq_valid_reg := false.B
//   }
//   //io.memreq.bits := memreq_arb.io.out.bits
//   //io.memreq.valid := memreq_arb.io.out.valid
//
//   io.memreq.bits := memreq_bits_reg
//   io.memreq.valid := memreq_valid_reg
//
//   //memreq_arb.io.in(0).bits := load_memreq_queue.io.deq.bits
//   //memreq_arb.io.in(0).valid := load_memreq_queue.io.deq.valid
//   load_memreq_queue.io.deq.ready := !io.laq_full & !mem_order(0) & !mem_order(1)
//
//   //memreq_arb.io.in(1).bits := store_memreq_queue.io.deq.bits
//   //memreq_arb.io.in(1).valid := store_memreq_queue.io.deq.valid
//   store_memreq_queue.io.deq.ready := !io.stq_full & mem_order(0) & !mem_order(1)
//
//   load_memreq_queue.io.enq.bits.addr := simple.io.mem_p0_addr.bits
//   load_memreq_queue.io.enq.bits.is_load := true.B
//   load_memreq_queue.io.enq.bits.is_store := false.B
//   load_memreq_queue.io.enq.bits.tag := 10.U
//   load_memreq_queue.io.enq.bits.data := 0.U
//   load_memreq_queue.io.enq.bits.mem_cmd := M_XRD
//
//   store_memreq_queue.io.enq.bits.addr := simple.io.mem_p1_addr.bits
//   store_memreq_queue.io.enq.bits.is_load := false.B
//   store_memreq_queue.io.enq.bits.is_store := true.B
//   store_memreq_queue.io.enq.bits.tag := 20.U
//   store_memreq_queue.io.enq.bits.data := simple.io.mem_p1_data_out.bits
//   store_memreq_queue.io.enq.bits.mem_cmd := M_XWR
//
//   load_memreq_queue.io.enq.valid := simple.io.mem_p0_addr.valid
//   store_memreq_queue.io.enq.valid := simple.io.mem_p1_addr.valid
//   simple.io.mem_p0_addr.ready := load_memreq_queue.io.enq.ready
//   simple.io.mem_p1_addr.ready := store_memreq_queue.io.enq.ready
//   simple.io.mem_p1_data_out.ready := store_memreq_queue.io.enq.ready
//
//   simple.io.mem_p0_data_in.valid := (io.memresp.bits.tag === 10.U) & io.memresp.valid
//   simple.io.mem_p0_data_in.bits := io.memresp.bits.data

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
           mem_order(0)=%d, mem_order(1)=%d,
           io.memreq.bits.ldq_idx=%d, io.memreq.bits.stq_idx=%d
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
     mem_order(0), mem_order(1),
     io.memreq.bits.ldq_idx, io.memreq.bits.stq_idx
   )
   printf("\n")

}

// vim: ts=8 expandtab softtabstop=3 shiftwidth=3
