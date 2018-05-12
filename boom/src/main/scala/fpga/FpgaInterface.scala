package boom

import Chisel._

import benchmarks.matmul._
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
   val is_sta  = Bool()
   val is_std  = Bool()
   val tag      = UInt(width = 32)
   val lsu_idx  = UInt(width = 32)
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
      val curr_rob_mem_tag = UInt(INPUT, 32)

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

   val internalReset = Reg(init = Bool(false))

   // We want to successively repalce 'src1' in the ADD instruction:
   //  imm           src1  fn3 rd    opcode
   // "b000000000000_yyyyy_00000_000_00000_0110011"
   val regFetchInstrTemplate1 = "b000000000000".U(12.W)
   val regFetchInstrTemplate0 = "b000_00000_0010011".U(15.W)

   // Hardcoded by user logic.
   val regAddrWidth = 5;
   val numRegisters = 8;

   val archRegsRequired = Reg(Vec(numRegisters, UInt(regAddrWidth.W)))
   archRegsRequired(0) := 0x0a.U    // a0 01010
   archRegsRequired(1) := 0x0b.U    // a1 01011
   archRegsRequired(2) := 0x0c.U    // a2 01100
   archRegsRequired(3) := 0x0d.U    // a3 01101
   archRegsRequired(4) := 0x0e.U    // a4 01110
   archRegsRequired(5) := 0x0f.U    // a5 01111
   archRegsRequired(6) := 0x10.U    // a6 10000
   archRegsRequired(7) := 0x11.U    // a7 10001

   val archRegsValid = Reg(init = UInt(0, numRegisters))
   val archRegsDone = Reg(init = UInt(0, numRegisters))

   // The return instruction from the function we're replacing.
   //
   // TODO(aryap): It might be more useful to have the return method (an offset
   // to jump in the PC, an explicit virtual/physical address, or this)
   // configurable by the user logic.
   //
   // 0x00008067 is from .riscv.dump and decodes to
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

   val fetchStart = Reg(init = Bool(false))
   val fetchReqDone = Reg(init = Bool(false))
   val fetchRespDone = Reg(init = Bool(false))
   val fetch_pc_reg = Reg(init = UInt(0, vaddrBitsExtended))
   val orig_pc_reg = Reg(init = UInt(0, vaddrBitsExtended))

   val regReqIdx = Reg(init = UInt(0, log2Up(numRegisters + 1)))
   val regRespIdx = Reg(init = UInt(0, log2Up(numRegisters + 1)))

   // Control the overall user logic state.
   val userStart = Reg(init = Bool(false))
   val userDone = Reg(init = Bool(false)) // Wire(Bool())

   val stallCnt = Reg(init = UInt(0, 32))
   val runnable_reg = Reg(init = Bool(false))
   val fetch_inst_reg = Reg(init = UInt(0, xLen))
   val fetch_valid_reg = Reg(init = Bool(false))

   val resetInternal = Reg(init = Bool(false))

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

   // This is for pho-mult-add.riscv
   when (io.currentPC(15, 0) === UInt(0x1b94)) {
     printf("FOUND TARGET!\n")
     runnable_reg := true.B
     // TODO(aryap): Constant?
     // TODO(aryap): This has to come from the accelerated block, one way or
     // another.
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
   val returnIdx = Reg(init = UInt(0, log2Up(numReturnInstrs + 1)))
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

   val userModule = Module(new matmul(vaddrBitsExtended, xLen))
   userModule.io.RegA0 := registers(0)
   userModule.io.RegA1 := registers(1)
   userModule.io.RegA2 := registers(2)
   userModule.io.RegA3 := registers(3)

   val userModule_done_reg = Reg(init = Bool(false))
   when (userModule.io.done) {
      userModule_done_reg := true.B
   }

   when (io.rob_empty && userModule_done_reg) {
     fetch_mem_inst_reg  := false.B
     fetch_mem_inst_start := false.B
     userDone := true.B
   }

   val userStart_delayed = Reg(init = Bool(false))
   userStart_delayed := userStart

   val fetchRespDone_delayed = Reg(init = false.B)
   fetchRespDone_delayed := fetchRespDone

   // start executing kernel after we finish with fetching registers
   when (!userStart_delayed && userStart) {
      userModule.io.start := true.B
      fetch_mem_inst_start := true.B
      orig_rob_tail_reg := io.orig_rob_tail
      orig_ldq_tail_reg := io.orig_ldq_tail
      orig_stq_tail_reg := io.orig_stq_tail
      userModule.io.start := true.B
      memreq_stq_idx_reg := io.orig_stq_tail
   }
   .otherwise {
      userModule.io.start := false.B
   }

   val memInstrs = Mem(3, UInt(xLen.W))
   val memInstrIdx = Reg(init = UInt(0, 32.W))
   val memInstrCnt = Reg(init = UInt(0, 32))
   memInstrs(0) := "h0005a783".U    // Load
   memInstrs(1) := "h00062503".U    // Load
   memInstrs(2) := "h00f6a023".U

   // Replicate the matrix multiplication loop.
   val loopI = Reg(init = UInt(0, 32.W))
   val loopJ = Reg(init = UInt(0, 32.W))
   val loopK = Reg(init = UInt(0, 32.W))

   val mem_p0_reset_tag_valid = Reg(init = Bool(false))
   val mem_p1_reset_tag_valid = Reg(init = Bool(false))
   val mem_p2_reset_tag_valid = Reg(init = Bool(false))
   val mem_p0_reset_tag_value = Reg(init = UInt(0, 32))
   val mem_p1_reset_tag_value = Reg(init = UInt(0, 32))
   val mem_p2_reset_tag_value = Reg(init = UInt(0, 32))

   when (mem_p0_reset_tag_valid) {
      mem_p0_reset_tag_valid := false.B
   }

   when (mem_p1_reset_tag_valid) {
      mem_p1_reset_tag_valid := false.B
   }

   when (mem_p2_reset_tag_valid) {
      mem_p2_reset_tag_valid := false.B
   }

   when (fetch_mem_inst_start) {
      when (loopI < registers(0)) {
         when (loopJ < registers(0)) {
            when (loopK < registers(0)) {
               when (io.fetch_ready) {

                  fetch_inst_reg := memInstrs(memInstrIdx)
                  fetch_mem_inst_reg := true.B
                  fetch_pc_reg := memInstrCnt
                  fetch_valid_reg := true.B

                  // Only issue the first two loads.
                  when (memInstrIdx === 1.U) {
                     memInstrIdx := 0.U
                     loopK := loopK + 1.U
                  } .otherwise {
                     // The memory requests for the inner loop are done.
                     memInstrIdx := memInstrIdx + 1.U
                  }
                  memInstrCnt := memInstrCnt + 1.U
               }
            } .elsewhen (io.rob_empty && io.fetch_ready) {

               // FIXME: Dirty hack to make it work for now
               mem_p0_reset_tag_valid := true.B
               mem_p0_reset_tag_value := memInstrCnt + 1.U
               mem_p1_reset_tag_valid := true.B
               mem_p1_reset_tag_value := memInstrCnt + 1.U
               mem_p2_reset_tag_valid := true.B
               mem_p2_reset_tag_value := memInstrCnt
               fetch_mem_inst_reg := true.B
               //memInstrCnt := 0.U
               fetch_inst_reg := memInstrs(2.U) // Install the store instruction.
               fetch_valid_reg := true.B
               fetch_pc_reg := memInstrCnt
               memInstrCnt := memInstrCnt + 1.U
               memInstrIdx := 0.U
               loopK := 0.U
               loopJ := loopJ + 1.U
            } .elsewhen (io.fetch_ready) {
               fetch_valid_reg := false.B
            }
         }
         .elsewhen (io.fetch_ready) {
            loopJ := 0.U
            loopI := loopI + 1.U
            fetch_valid_reg := false.B
         }
         // The outer loop only goes as far as N; we don't restart the counter.
      }
      .elsewhen (io.fetch_ready) {
         fetch_valid_reg := false.B
      }
      // Should be done here.
   }

   io.fetch_mem_inst := fetch_mem_inst_reg

   val memreq_arb = Module(new Arbiter(new FpgaMemReq(), 3))
   val load0_memreq_queue = Module(new Queue(new FpgaMemReq(), 10))
   val load1_memreq_queue = Module(new Queue(new FpgaMemReq(), 10))
   val store_addr_memreq_queue = Module(new Queue(new FpgaMemReq(), 10))
   val store_data_memreq_queue = Module(new Queue(new FpgaMemReq(), 10))

   io.memreq.bits := memreq_arb.io.out.bits
   io.memreq.valid := memreq_arb.io.out.valid && (memreq_arb.io.out.bits.tag <= io.curr_rob_mem_tag)
   memreq_arb.io.out.ready := true.B

   io.memreq_rob_idx := memreq_arb.io.out.bits.tag + orig_rob_tail_reg
   io.memreq_ldq_idx := memreq_arb.io.out.bits.lsu_idx + orig_ldq_tail_reg
   io.memreq_stq_idx := memreq_arb.io.out.bits.lsu_idx + orig_stq_tail_reg

   memreq_arb.io.in(0).bits := load0_memreq_queue.io.deq.bits
   memreq_arb.io.in(0).valid := load0_memreq_queue.io.deq.valid && (load0_memreq_queue.io.deq.bits.tag <= io.curr_rob_mem_tag)
   load0_memreq_queue.io.deq.ready := (load0_memreq_queue.io.deq.bits.tag <= io.curr_rob_mem_tag) && memreq_arb.io.in(0).valid && memreq_arb.io.in(0).ready

   memreq_arb.io.in(1).bits := load1_memreq_queue.io.deq.bits
   memreq_arb.io.in(1).valid := load1_memreq_queue.io.deq.valid && (load1_memreq_queue.io.deq.bits.tag <= io.curr_rob_mem_tag)
   load1_memreq_queue.io.deq.ready := (load1_memreq_queue.io.deq.bits.tag <= io.curr_rob_mem_tag) && memreq_arb.io.in(1).valid && memreq_arb.io.in(1).ready

   memreq_arb.io.in(2).bits := store_addr_memreq_queue.io.deq.bits
   memreq_arb.io.in(2).valid := store_addr_memreq_queue.io.deq.valid && (store_addr_memreq_queue.io.deq.bits.tag <= io.curr_rob_mem_tag)
   store_addr_memreq_queue.io.deq.ready := (store_addr_memreq_queue.io.deq.bits.tag <= io.curr_rob_mem_tag) &&
                                           memreq_arb.io.in(2).valid && memreq_arb.io.in(2).ready

//   memreq_arb.io.in(3).bits := store_data_memreq_queue.io.deq.bits
//   memreq_arb.io.in(3).valid := store_data_memreq_queue.io.deq.valid && (store_data_memreq_queue.io.deq.bits.tag <= io.curr_rob_mem_tag)
//   store_data_memreq_queue.io.deq.ready := (store_addr_memreq_queue.io.deq.bits.tag <= io.curr_rob_mem_tag) &&
//                                           memreq_arb.io.in(3).valid && memreq_arb.io.in(3).ready

   load0_memreq_queue.io.enq.bits.addr := userModule.io.mem_p0_addr.bits
   load0_memreq_queue.io.enq.bits.is_load := true.B
   load0_memreq_queue.io.enq.bits.is_sta := false.B
   load0_memreq_queue.io.enq.bits.is_std := false.B
   load0_memreq_queue.io.enq.bits.tag := userModule.io.mem_p0_addr_tag
   load0_memreq_queue.io.enq.bits.lsu_idx := userModule.io.mem_p0_load_idx
   load0_memreq_queue.io.enq.bits.data := 0.U // does not matter for load
   load0_memreq_queue.io.enq.bits.mem_cmd := M_XRD

   load1_memreq_queue.io.enq.bits.addr := userModule.io.mem_p1_addr.bits
   load1_memreq_queue.io.enq.bits.is_load := true.B
   load1_memreq_queue.io.enq.bits.is_sta := false.B
   load1_memreq_queue.io.enq.bits.is_std := false.B
   load1_memreq_queue.io.enq.bits.tag := userModule.io.mem_p1_addr_tag
   load1_memreq_queue.io.enq.bits.lsu_idx := userModule.io.mem_p1_load_idx
   load1_memreq_queue.io.enq.bits.data := 0.U // does not matter for load 
   load1_memreq_queue.io.enq.bits.mem_cmd := M_XRD

   store_addr_memreq_queue.io.enq.bits.addr := userModule.io.mem_p2_addr.bits
   store_addr_memreq_queue.io.enq.bits.is_load := false.B
   store_addr_memreq_queue.io.enq.bits.is_sta := true.B
   store_addr_memreq_queue.io.enq.bits.is_std := true.B
   store_addr_memreq_queue.io.enq.bits.tag := userModule.io.mem_p2_addr_tag
   store_addr_memreq_queue.io.enq.bits.lsu_idx := userModule.io.mem_p2_sta_idx
   store_addr_memreq_queue.io.enq.bits.data := userModule.io.mem_p2_data_out.bits // does not matter for store address
   store_addr_memreq_queue.io.enq.bits.mem_cmd := M_XWR

//   store_data_memreq_queue.io.enq.bits.addr := 0.U // does not matter for store data
//   store_data_memreq_queue.io.enq.bits.is_load := false.B
//   store_data_memreq_queue.io.enq.bits.is_sta := true.B
//   store_data_memreq_queue.io.enq.bits.is_std := false.B
//   store_data_memreq_queue.io.enq.bits.tag := userModule.io.mem_p2_data_out_tag
//   store_data_memreq_queue.io.enq.bits.lsu_idx := userModule.io.mem_p2_std_idx
//   store_data_memreq_queue.io.enq.bits.data := userModule.io.mem_p2_data_out.bits
//   store_data_memreq_queue.io.enq.bits.mem_cmd := M_XWR

   load0_memreq_queue.io.enq.valid := userModule.io.mem_p0_addr.valid
   load1_memreq_queue.io.enq.valid := userModule.io.mem_p1_addr.valid
   store_addr_memreq_queue.io.enq.valid := userModule.io.mem_p2_addr.valid
   store_data_memreq_queue.io.enq.valid := userModule.io.mem_p2_data_out.valid

   userModule.io.mem_p0_addr.ready := load0_memreq_queue.io.enq.ready
   userModule.io.mem_p1_addr.ready := load1_memreq_queue.io.enq.ready
   userModule.io.mem_p2_addr.ready := store_addr_memreq_queue.io.enq.ready
//   userModule.io.mem_p2_data_out.ready := store_data_memreq_queue.io.enq.ready
   userModule.io.mem_p2_data_out.ready := store_addr_memreq_queue.io.enq.ready

   userModule.io.mem_p0_data_in.valid := (io.memresp.bits.tag === userModule.io.mem_p0_data_in_tag) & io.memresp.valid
   userModule.io.mem_p0_data_in.bits := io.memresp.bits.data

   userModule.io.mem_p1_data_in.valid := (io.memresp.bits.tag === userModule.io.mem_p1_data_in_tag) & io.memresp.valid
   userModule.io.mem_p1_data_in.bits := io.memresp.bits.data

   userModule.io.mem_p0_reset_tag_valid := mem_p0_reset_tag_valid
   userModule.io.mem_p1_reset_tag_valid := mem_p1_reset_tag_valid
   userModule.io.mem_p2_reset_tag_valid := mem_p2_reset_tag_valid
   userModule.io.mem_p0_reset_tag_value := mem_p0_reset_tag_value
   userModule.io.mem_p1_reset_tag_value := mem_p1_reset_tag_value
   userModule.io.mem_p2_reset_tag_value := mem_p2_reset_tag_value

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
           [SIMPLE]userModule.io.start=%d, userModule.io.done=%d,
           [RETURN] returnIdx=%d, internalReset=%d,
           io.memreq.bits.addr=0x%x, io.memreq.bits.is_load=%d, io.memreq.bits.is_sta=%d, io.memreq.bits.is_std=%d,
           io.memreq.bits.data=0x%x, io.memreq.bits.tag=%d,
           io.memreq.valid=%d,
           io.memresp.data=0x%x, io.memresp.tag=%d,
           io.memresp.valid=%d,
           io.laq_full=%d, io.stq_full=%d,

           io.memreq_rob_idx=%d, io.memreq_ldq_idx=%d, io.memreq_stq_idx=%d memInstrCnt=%d, memInstrIdx=%d
           loopI: %d loopJ: %d loopK: %d
           userModule.io.mem_p0_addr.valid=%d,
           userModule.io.mem_p1_addr.valid=%d,
           userModule.io.mem_p2_addr.valid=%d,
           userModule.io.mem_p0_data_in.valid=%d,
           userModule.io.mem_p1_data_in.valid=%d,
           userModule.io.mem_p0_addr_tag=%d, userModule.io.mem_p0_data_in_tag=%d,
           userModule.io.mem_p1_addr_tag=%d, userModule.io.mem_p1_data_in_tag=%d,
           userModule.io.mem_p2_addr_tag=%d

           memreq_arb.io.in(0).valid=%d, memreq_arb.io.in(1).valid=%d, memreq_arb.io.in(2).valid=%d
           memreq_arb.io.in(0).ready=%d, memreq_arb.io.in(1).ready=%d, memreq_arb.io.in(2).ready=%d
           memreq_arb.io.out.valid=%d, memreq_arb.io.out.ready=%d,

           load0_memreq_queue.io.enq.bits.tag=%d,
           load0_memreq_queue.io.enq.bits.lsu_idx=%d,
           load0_memreq_queue.io.enq.valid=%d,
           load0_memreq_queue.io.enq.ready=%d,

           load1_memreq_queue.io.enq.bits.tag=%d,
           load1_memreq_queue.io.enq.bits.lsu_idx=%d,
           load1_memreq_queue.io.enq.valid=%d,
           load1_memreq_queue.io.enq.ready=%d,

           store_addr_memreq_queue.io.enq.bits.tag=%d,
           store_addr_memreq_queue.io.enq.bits.lsu_idx=%d,
           store_addr_memreq_queue.io.enq.valid=%d,
           store_addr_memreq_queue.io.enq.ready=%d,

           store_data_memreq_queue.io.enq.bits.tag=%d,
           store_data_memreq_queue.io.enq.bits.lsu_idx=%d,
           store_data_memreq_queue.io.enq.valid=%d,
           store_data_memreq_queue.io.enq.ready=%d,

           load0_memreq_queue.io.deq.bits.tag=%d,
           load0_memreq_queue.io.deq.valid=%d,
           load0_memreq_queue.io.deq.ready=%d,

           load1_memreq_queue.io.enq.bits.tag=%d,
           load1_memreq_queue.io.enq.valid=%d,
           load1_memreq_queue.io.enq.ready=%d,

           store_addr_memreq_queue.io.deq.bits.tag=%d,
           store_addr_memreq_queue.io.deq.valid=%d,
           store_addr_memreq_queue.io.deq.ready=%d,

           store_data_memreq_queue.io.deq.bits.tag=%d,
           store_data_memreq_queue.io.deq.valid=%d,
           store_data_memreq_queue.io.deq.ready=%d,

           mem_p0_reset_tag_valid=%d, mem_p1_reset_tag_valid=%d, mem_p2_reset_tag_valid=%d,
           mem_p0_reset_tag_value=%d, mem_p1_reset_tag_value=%d, mem_p2_reset_tag_value=%d,

           io.curr_rob_mem_tag=%d
     """,
     io.runnable, stallCnt,
     regReqIdx, regRespIdx, fetchStart,
     fetchReqDone, fetchRespDone, fetch_inst_reg, io.fetch_valid, io.fetch_ready,
     io.rob_valid, io.rob_data, io.currentPC, fetch_pc_reg,
     userStart, userDone,
     userModule.io.start, userModule.io.done,
     returnIdx, internalReset,
     io.memreq.bits.addr, io.memreq.bits.is_load, io.memreq.bits.is_sta, io.memreq.bits.is_std,
     io.memreq.bits.data, io.memreq.bits.tag,
     io.memreq.valid,
     io.memresp.bits.data, io.memresp.bits.tag,
     io.memresp.valid,
     io.laq_full, io.stq_full,
     io.memreq_rob_idx, io.memreq_ldq_idx, io.memreq_stq_idx,
     memInstrCnt, memInstrIdx,
     loopI, loopJ, loopK,
     userModule.io.mem_p0_addr.valid,
     userModule.io.mem_p1_addr.valid,
     userModule.io.mem_p2_addr.valid,
     userModule.io.mem_p0_data_in.valid,
     userModule.io.mem_p1_data_in.valid,
     userModule.io.mem_p0_addr_tag, userModule.io.mem_p0_data_in_tag,
     userModule.io.mem_p1_addr_tag, userModule.io.mem_p1_data_in_tag,
     userModule.io.mem_p2_addr_tag,

     memreq_arb.io.in(0).valid, memreq_arb.io.in(1).valid, memreq_arb.io.in(2).valid,
     memreq_arb.io.in(0).ready, memreq_arb.io.in(1).ready, memreq_arb.io.in(2).ready,
     memreq_arb.io.out.valid, memreq_arb.io.out.ready,

     load0_memreq_queue.io.enq.bits.tag,
     load0_memreq_queue.io.enq.bits.lsu_idx,
     load0_memreq_queue.io.enq.valid,
     load0_memreq_queue.io.enq.ready,

     load1_memreq_queue.io.enq.bits.tag,
     load1_memreq_queue.io.enq.bits.lsu_idx,
     load1_memreq_queue.io.enq.valid,
     load1_memreq_queue.io.enq.ready,

     store_addr_memreq_queue.io.enq.bits.tag,
     store_addr_memreq_queue.io.enq.bits.lsu_idx,
     store_addr_memreq_queue.io.enq.valid,
     store_addr_memreq_queue.io.enq.ready,

     store_data_memreq_queue.io.enq.bits.tag,
     store_data_memreq_queue.io.enq.bits.lsu_idx,
     store_data_memreq_queue.io.enq.valid,
     store_data_memreq_queue.io.enq.ready,

     load0_memreq_queue.io.deq.bits.tag,
     load0_memreq_queue.io.deq.valid,
     load0_memreq_queue.io.deq.ready,

     load1_memreq_queue.io.enq.bits.tag,
     load1_memreq_queue.io.enq.valid,
     load1_memreq_queue.io.enq.ready,

     store_addr_memreq_queue.io.deq.bits.tag,
     store_addr_memreq_queue.io.deq.valid,
     store_addr_memreq_queue.io.deq.ready,

     store_data_memreq_queue.io.deq.bits.tag,
     store_data_memreq_queue.io.deq.valid,
     store_data_memreq_queue.io.deq.ready,

     mem_p0_reset_tag_valid, mem_p1_reset_tag_valid, mem_p2_reset_tag_valid,
     mem_p0_reset_tag_value, mem_p1_reset_tag_value, mem_p2_reset_tag_value,

     io.curr_rob_mem_tag
   )
   printf("\n")

}

// vim: ts=8 expandtab softtabstop=3 shiftwidth=3
