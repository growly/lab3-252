package elastic

import Chisel._

class Store(addrWidth: Int, dataWidth: Int, initTag: Int, strideTag: Int) extends Module {
  val io = IO(new Bundle {
    val in               = Vec(2, new DecoupledIO(UInt(addrWidth.W)).flip())

    val mem_addr         = new DecoupledIO(UInt(addrWidth.W))
    val mem_addr_tag     = UInt(OUTPUT, 32)
    val mem_data_out     = new DecoupledIO(UInt(addrWidth.W))
    val mem_data_out_tag = UInt(OUTPUT, 32)

  })

  io.in(0).ready := io.mem_addr.ready
  io.mem_addr.bits := io.in(0).bits
  io.mem_addr.valid := io.in(0).valid

  io.in(1).ready := io.mem_data_out.ready
  io.mem_data_out.bits := io.in(1).bits
  io.mem_data_out.valid := io.in(1).valid

  val mem_addr_tag_reg = Reg(init = UInt(initTag, 32))
  val mem_data_out_tag_reg = Reg(init = UInt(initTag, 32))

  when (io.mem_addr.valid && io.mem_addr.ready) {
    mem_addr_tag_reg := mem_addr_tag_reg + UInt(strideTag)
  }

  when (io.mem_data_out.valid && io.mem_data_out.ready) {
    mem_data_out_tag_reg := mem_data_out_tag_reg + UInt(strideTag)
  }

  io.mem_addr_tag := mem_addr_tag_reg
  io.mem_data_out_tag := mem_data_out_tag_reg

}
