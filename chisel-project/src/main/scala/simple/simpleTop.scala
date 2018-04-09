package simple

import chisel3._
import chisel3.util._

class simpleTop(addrWidth: Int = 32,
                dataWidth: Int = 32,
                readLatency: UInt = 50.U,
                writeLatency: UInt = 50.U
  ) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val done = Output(Bool())

    val Reg0 = Input(UInt(32.W))
    val Reg1 = Input(UInt(32.W))
    val Reg2 = Input(UInt(32.W))
    val Reg3 = Input(UInt(32.W))
    val Reg4 = Input(UInt(32.W))
  })

  val simple = Module(new simple(addrWidth, dataWidth))
  val mem = Module(new Memory(addrWidth, dataWidth,
                              readLatency, writeLatency))
  simple.io.start := io.start
  io.done := simple.io.done
  simple.io.Reg0 := io.Reg0
  simple.io.Reg1 := io.Reg1
  simple.io.Reg2 := io.Reg2
  simple.io.Reg3 := io.Reg3
  simple.io.Reg4 := io.Reg4

  // simply hook up memory interface
  mem.io.mem_p0_addr <> simple.io.mem_p0_addr
  simple.io.mem_p0_data_in <> mem.io.mem_p0_data_in

  mem.io.mem_p1_addr <> simple.io.mem_p1_addr
  mem.io.mem_p1_data_out <> simple.io.mem_p1_data_out

}
