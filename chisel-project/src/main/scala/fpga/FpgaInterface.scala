package fpga

import chisel3._
import chisel3.util._

class FpgaInterface(
  dataWidth: Int = 32,
  addrWidth: Int = 32,
  regAddrWidth: Int = 5) extends Module {
  val io = IO(new Bundle {
    val currentPC     = Input(UInt(dataWidth.W))
    // High when this interface can replace an instruction sequeunce starting at
    // the given PC.
    val runnable      = Output(Bool())
    // The number of bytes of instructions that can be skipped by activating
    // this module.
    val reach         = Output(UInt(addrWidth.W))
    // High when the backing logic has finished computation.
    val done          = Output(Bool())
    // High when the module should start copying data in.
    val start         = Input(Bool())
    // Register copy interface.
    val archRegAddr   = Output(UInt(regAddrWidth.W))
    val regData       = Input(UInt(dataWidth.W))
    val regCopyDone   = Output(Bool())

    // This is just for temp debugging.
    val result        = Output(UInt(dataWidth.W))

    /* Verilog for the memory interface:
    // Interface to the memory load queue.
    output [DATA_WIDTH-1:0] load_addr,
    output load_addr_valid,
    input load_addr_ready,

    input [DATA_WIDTH-1:0] load_data,
    input load_data_valid,
    output load_data_ready,

    // Interface to the memory store queue.
    output [ADDR_LEN-1:0] store_addr,
    output store_addr_valid,
    input store_addr_ready,

    output [DATA_WIDTH-1:0] store_data,
    output store_data_valid,
    input store_data_ready
    */
  })

  //printf(p"FpgaInterface with dataWidth: $dataWidth addrWidth: $addrWidth regAddrWidth: $regAddrWidth")

  // Hardcoded by generated FPGA accelerator.
  val numRegisters = 5;
  val ourPC = "h42".U;
  val ourReach = 2000;

  io.reach := ourReach.U;

  // In the FPGA logic we synthesise, every architectural register is referred
  // to by its new index. This mapping is known at synthesis.
  val archRegsRequired = Reg(Vec(numRegisters, UInt(regAddrWidth.W)))
  archRegsRequired(0) := 23.U
  archRegsRequired(1) := 0.U
  archRegsRequired(2) := 12.U
  archRegsRequired(3) := 18.U
  archRegsRequired(4) := 9.U

  val sPCWait :: sStartWait :: sRegCopy :: sRunning :: sDone :: sError :: Nil = Enum(6)
  val state = Reg(init = sPCWait)

  io.done := RegInit(false.B)
  io.runnable := io.currentPC === ourPC
  io.regCopyDone := RegInit(false.B)

  // TODO(aryap): We need to be careful with the contract we present to the CPU
  // core about when signals are valid and what they mean.
  switch (state) {
    is (sPCWait) {
      when (io.runnable) {
        state := sStartWait
      }
    }
    is (sStartWait) {
      when (io.start) {
        state := sRegCopy
      } .elsewhen (io.currentPC =/= ourPC) {
        state := sPCWait
      }
    }
    is (sRegCopy) {
      when (io.regCopyDone) {
        state := sRunning
      }
    }
    is (sRunning) {
      when (io.done) {
        state := sDone
      }
    }
    is (sDone) {
      // TODO(aryap): How to reset?
    }
    is (sError) {
    }
  }

  // The init value seems important here.
  val regIndex = RegInit(UInt(log2Up(numRegisters).W), 0.U)

  // TODO(aryap): So something outside of this interface needs to connect the
  // physical register address from the Register Renaming table to the Regfile
  // for us, and read out the value. Maybe we should just put it in this
  // interface to minimise changes to the BOOM?
  val registers = Reg(Vec(numRegisters, UInt(dataWidth.W)))

  io.archRegAddr := RegInit(0.U)
  when (state === sRegCopy && regIndex < numRegisters.U) {
    io.archRegAddr := archRegsRequired(regIndex)
    registers(regIndex) := io.regData
    regIndex := regIndex + 1.U
  }.elsewhen(state === sRegCopy && regIndex === numRegisters.U) {
    io.regCopyDone := true.B
  }

  // This is the FPGA's implemented application logic.
  val sumIdx = Reg(UInt(log2Up(numRegisters).W), 0.U)
  val sum = Reg(UInt(32.W), 0.U)
  when (state === sRunning) {
    when (sumIdx < numRegisters.U) {
      sum := sum + registers(sumIdx)
      sumIdx := sumIdx + 1.U
    }.elsewhen(sumIdx === numRegisters.U) {
      io.done := true.B
    }
  }
  io.result := sum
}
