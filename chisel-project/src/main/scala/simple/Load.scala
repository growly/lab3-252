package simple

import chisel3._
import chisel3.util._


class Load(addrWidth: Int, dataWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in = new DecoupledIO(UInt(addrWidth.W)).flip()
    val out = new DecoupledIO(UInt(dataWidth.W))

    val mem_addr = new DecoupledIO(UInt(addrWidth.W))
    val mem_data_in = new DecoupledIO(UInt(dataWidth.W)).flip()
  })

  io.in.ready := io.mem_addr.ready
  io.mem_addr.bits := io.in.bits
  io.mem_addr.valid := io.in.valid

  io.out.bits := io.mem_data_in.bits
  io.out.valid := io.mem_data_in.valid
  io.mem_data_in.ready := io.out.ready

}
