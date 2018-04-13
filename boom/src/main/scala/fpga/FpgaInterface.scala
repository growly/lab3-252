package boom

import chisel3._
import chisel3.util._

import freechips.rocketchip.config._
import freechips.rocketchip.tile._
import freechips.rocketchip.util._

class FpgaInterface(
  dataWidth: Int = 32,
  addrWidth: Int = 32,
  regAddrWidth: Int = 5) (implicit p: Parameters) extends Module() {
  val io = IO(new CoreBundle()(p) {
    val currentPC     = Input(UInt(vaddrBitsExtended.W))

    // High when this interface can replace an instruction sequence starting
    // at the given PC.
    val runnable      = Output(Bool())

    val fetch_inst  = Output(UInt(dataWidth.W))
    val fetch_valid = Output(Bool())

    val rob_valid     = Input(Bool())
    val rob_data      = Input(UInt(dataWidth.W))

    // The number of bytes of instructions that can be skipped by activating
    // this module.
    //val reach         = Output(UInt(addrWidth.W))

    // High when the backing logic has finished computation.
    //val done          = Output(Bool())

    // High when the module should start copying data in.
    //val start         = Input(Bool())

    // Register copy interface.
    //val archRegAddr   = Output(UInt(regAddrWidth.W))
    //val regData       = Input(UInt(dataWidth.W))
    //val regCopyDone   = Output(Bool())

    // This is just for temp debugging.
    //val result        = Output(UInt(dataWidth.W))

  })

   val mem_read_reg_insts = Mem(5, UInt(32.W))
   mem_read_reg_insts(0) := UInt(0x00060633) // add a2, a2, x0
   mem_read_reg_insts(1) := UInt(0x000686b3) // add a3, a3, x0
   mem_read_reg_insts(2) := UInt(0x00070733) // add a4, a4, x0
   mem_read_reg_insts(3) := UInt(0x000787b3) // add a5, a5, x0
   mem_read_reg_insts(4) := UInt(0x00080833) // add a6, a6, x0


   val stallCnt = RegInit(0.U(32.W))
   val runnable_reg = RegInit(false.B)
   val fetch_inst_reg = RegInit(0.U(32.W))
   val fetch_valid_reg = RegInit(false.B)
   val fetch_start = RegInit(false.B)
   val fetch_done = RegInit(false.B)
   val sum = RegInit(0.U(32.W))
   val cnt0 = RegInit(0.U(32.W)) // count fetch
   val cnt1 = RegInit(0.U(32.W)) // count commit

   io.runnable := runnable_reg
   io.fetch_inst := fetch_inst_reg
   io.fetch_valid := fetch_valid_reg

   // PC value of the jump_to_kernel instruction: 0x0080001c04
   // check: $TOPDIR/install/riscv-bmarks/simple.riscv.dump
   when (io.currentPC(15, 0) === UInt(0x1c04)) {
     printf("FOUND TARGET!\n")
     runnable_reg := true.B
   }

   when (runnable_reg) {
     stallCnt := stallCnt + 1.U
   }

   // Send instruction to BOOM at cycle 40
   when (stallCnt === 40.U) {
     fetch_start := true.B
   }

   when (cnt0 === 5.U) {
     // if we have read 5 CPU registers, stop fetching
     fetch_valid_reg := false.B
     fetch_inst_reg := UInt(0)
     fetch_done := true.B
     cnt0 := UInt(0)
   } .elsewhen (fetch_start && !fetch_done) {
     // successively read CPU registers
     fetch_inst_reg := mem_read_reg_insts(cnt0)
     fetch_valid_reg := true.B
     cnt0 := cnt0 + 1.U
   }

   when (fetch_start && io.rob_valid) {
     sum := sum + io.rob_data
     cnt1 := cnt1 + 1.U
   }

   // All instructions are committed
   when (cnt1 === 5.U) {
     fetch_start := false.B
     fetch_done := false.B
     cnt1 := UInt(0)
   }

   // stall for 100 cycles
   when (stallCnt === 100.U) {
     stallCnt := 0.U
     runnable_reg := false.B
   }

   printf("""test... runnable: %d, stallCnt: %d, cnt0: %d, cnt1: %d,
           fetch_start: %d, fetch_done: %d,
           rob_valid: %d, rob_data: 0x%x, currentPC: 0x%x, sum = %d\n""",
     io.runnable, stallCnt, cnt0, cnt1,
     fetch_start, fetch_done,
     io.rob_valid, io.rob_data, io.currentPC, sum)

//  //printf(p"FpgaInterface with dataWidth: $dataWidth addrWidth: $addrWidth regAddrWidth: $regAddrWidth")
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
//  val registers = Reg(Vec(numRegisters, UInt(dataWidth.W)))
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
