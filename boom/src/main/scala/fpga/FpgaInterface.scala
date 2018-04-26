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
      val fetch_ready     = Bool(INPUT)

      val rob_valid       = Bool(INPUT)
      val rob_data        = UInt(INPUT, xLen)

      val laq_full        = Bool(INPUT)
      val stq_full        = Bool(INPUT)

      val memreq          = new DecoupledIO(new FpgaMemReq())
      val memresp         = new DecoupledIO(new FpgaMemResp()).flip()

   })

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

   // Register data.
   val registers = Reg(Vec(numRegisters, UInt(xLen.W)))

   val fetchStart = Reg(init = false.B)
   val fetchReqDone = Reg(init = false.B)
   val fetchRespDone = Reg(init = false.B)

   val regReqIdx = Reg(init = UInt(0, log2Up(numRegisters)))
   val regRespIdx = Reg(init = UInt(0, log2Up(numRegisters)))

   // Control the overall user logic state.
   val userStart = Reg(init = false.B)
   val userDone = Reg(init = false.B)

   val stallCnt = Reg(init = UInt(0, 32))
   val runnable_reg = Reg(init = false.B)
   val fetch_inst_reg = Reg(init = UInt(0, xLen))
   val fetch_valid_reg = Reg(init = false.B)

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

   val simple = Module(new simple(vaddrBitsExtended, xLen))
   simple.io.RegA0 := registers(0)
   simple.io.RegA1 := registers(1)
   simple.io.RegA2 := registers(2)
   simple.io.RegA3 := registers(3)
   simple.io.RegA4 := registers(4)
   simple.io.RegA5 := registers(5)
   simple.io.RegA6 := registers(6)

   val simple_start = Reg(init = false.B)

   // start executing kernel after we finish with fetching registers
   when (fetchRespDone) {
     simple_start := true.B
   }

   simple.io.start := simple_start

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
   when (load_memreq_queue.io.deq.valid && load_memreq_queue.io.deq.ready) {
     mem_order(0) := true.B
     memreq_bits_reg := load_memreq_queue.io.deq.bits
     memreq_valid_reg := load_memreq_queue.io.deq.valid
   }
   .elsewhen (store_memreq_queue.io.deq.valid && store_memreq_queue.io.deq.ready) {
     mem_order(1) := true.B
     memreq_bits_reg := store_memreq_queue.io.deq.bits
     memreq_valid_reg := store_memreq_queue.io.deq.valid
   }
   .otherwise {
     memreq_valid_reg := false.B
   }
   //io.memreq.bits := memreq_arb.io.out.bits
   //io.memreq.valid := memreq_arb.io.out.valid

   io.memreq.bits := memreq_bits_reg
   io.memreq.valid := memreq_valid_reg

   //memreq_arb.io.in(0).bits := load_memreq_queue.io.deq.bits
   //memreq_arb.io.in(0).valid := load_memreq_queue.io.deq.valid
   load_memreq_queue.io.deq.ready := !io.laq_full & !mem_order(0) & !mem_order(1)

   //memreq_arb.io.in(1).bits := store_memreq_queue.io.deq.bits
   //memreq_arb.io.in(1).valid := store_memreq_queue.io.deq.valid
   store_memreq_queue.io.deq.ready := !io.stq_full & mem_order(0) & !mem_order(1)

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
   when (stallCnt === 1000.U) {
     stallCnt := 0.U
     runnable_reg := false.B
   }

   printf("\n")
   for (i <- 0 to numRegisters - 1) {
     printf("[FPGA] REGISTER[%d]: ARCH:%d VALUE 0x%x\n", i.U, archRegsRequired(i.U), registers(i.U));
   }

   printf("\n")
   printf("""[FPGA]... runnable: %d, stallCnt: %d,
           [REG FETCH] regReqIdx: %d, regRespIdx: %d, fetchStart: %d,
           [REG FETCH] fetchReqDone: %d, fetchRespDone: %d, fetch_inst_reg: 0x%x, fetch_valid: %d, fetch_ready: %d,
           rob_valid: %d, rob_data: 0x%x, currentPC: 0x%x,
           [SIMPLE]simple.io.start=%d, simple.io.done=%d,
           io.memreq.bits.addr=0x%x, io.memreq.bits.is_load=%d, io.memreq.bits.is_store=%d,
           io.memreq.bits.data=0x%x, io.memreq.bits.tag=%d,
           io.memreq.valid=%d,
           io.memresp.data=0x%x, io.memresp.tag=%d,
           io.memresp.valid=%d,
           simple.io.mem_p0_addr.valid=%d,
           simple.io.mem_p1_addr.valid=%d,
           io.laq_full=%d, io.stq_full=%d,
           mem_order(0)=%d, mem_order(1)=%d
     """,
     io.runnable, stallCnt,
     regReqIdx, regRespIdx, fetchStart,
     fetchReqDone, fetchRespDone, fetch_inst_reg, io.fetch_valid, io.fetch_ready,
     io.rob_valid, io.rob_data, io.currentPC,
     //[USER LOGIC] sum = %d, sumIdx: %d, userStart: %d, userDone: %d,
     //sum, sumIdx, userStart, userDone,
     simple.io.start, simple.io.done,
     io.memreq.bits.addr, io.memreq.bits.is_load, io.memreq.bits.is_store,
     io.memreq.bits.data, io.memreq.bits.tag,
     io.memreq.valid,
     io.memresp.bits.data, io.memresp.bits.tag,
     io.memresp.valid,
     simple.io.mem_p0_addr.valid,
     simple.io.mem_p1_addr.valid,
     io.laq_full, io.stq_full,
     mem_order(0), mem_order(1)
   )
   printf("\n")

}

// vim: ts=8 expandtab softtabstop=3 shiftwidth=3
