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

   val stallCnt = RegInit(0.U(32.W))
   val runnable_reg = RegInit(false.B)

   io.runnable := runnable_reg

   // PC value of the jump_to_kernel instruction: 0x0080001b68
   // check: $TOPDIR/install/riscv-bmarks/simple.riscv.dump
   when (io.currentPC(15, 0) === UInt(0x1b68)) {
     printf("FOUND TARGET!\n")
     runnable_reg := true.B
   }

   when (runnable_reg) {
     stallCnt := stallCnt + 1.U
   }

   // stall for 100 cycles
   when (stallCnt === 100.U) {
     stallCnt := 0.U
     runnable_reg := false.B
   }
   printf("test... %d %d 0x%x\n", io.runnable, stallCnt, io.currentPC)

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
