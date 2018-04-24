package boom

import chisel3._
import chisel3.util._


class Store(addrWidth: Int, dataWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in = Vec(2, new DecoupledIO(UInt(addrWidth.W)).flip())

    val mem_addr = new DecoupledIO(UInt(addrWidth.W))
    val mem_data_out = new DecoupledIO(UInt(addrWidth.W))
  })

  io.in(0).ready := io.mem_addr.ready
  io.mem_addr.bits := io.in(0).bits
  io.mem_addr.valid := io.in(0).valid

  io.in(1).ready := io.mem_data_out.ready
  io.mem_data_out.bits := io.in(1).bits
  io.mem_data_out.valid := io.in(1).valid
}
