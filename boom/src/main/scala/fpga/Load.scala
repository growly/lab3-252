package boom

import chisel3._
import chisel3.util._


class Load(addrWidth: Int, dataWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in = new DecoupledIO(UInt(addrWidth.W)).flip()
    val out = new DecoupledIO(UInt(dataWidth.W))

    val mem_addr = new DecoupledIO(UInt(addrWidth.W))
    val mem_addr_tag = Output(UInt(32.W))
    val mem_data_in = new DecoupledIO(UInt(dataWidth.W)).flip()
    val mem_data_in_tag = Output(UInt(32.W))

  })

  io.in.ready := io.mem_addr.ready
  io.mem_addr.bits := io.in.bits
  io.mem_addr.valid := io.in.valid

  io.out.bits := io.mem_data_in.bits
  io.out.valid := io.mem_data_in.valid
  io.mem_data_in.ready := io.out.ready

  val mem_addr_tag_reg = RegInit(0.U(32.W))
  val mem_data_in_tag_reg = RegInit(0.U(32.W))

  when (io.mem_addr.valid && io.mem_addr.ready) {
    mem_addr_tag_reg := mem_addr_tag_reg + 2.U
  }

  when (io.mem_data_in.valid && io.mem_data_in.ready) {
    mem_data_in_tag_reg := mem_data_in_tag_reg + 2.U
  }

  io.mem_addr_tag := mem_addr_tag_reg
  io.mem_data_in_tag := mem_data_in_tag_reg
}
