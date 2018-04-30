package boom

import Chisel._

class Load(addrWidth: Int, dataWidth: Int, initTag: Int, strideTag: Int) extends Module {
  val io = IO(new Bundle {
    val in              = new DecoupledIO(UInt(addrWidth.W)).flip()
    val out             = new DecoupledIO(UInt(dataWidth.W))

    val mem_addr        = new DecoupledIO(UInt(addrWidth.W))
    val mem_addr_tag    = UInt(OUTPUT, 32)
    val mem_data_in     = new DecoupledIO(UInt(dataWidth.W)).flip()
    val mem_data_in_tag = UInt(OUTPUT, 32)

  })

  io.in.ready := io.mem_addr.ready
  io.mem_addr.bits := io.in.bits
  io.mem_addr.valid := io.in.valid

  io.out.bits := io.mem_data_in.bits
  io.out.valid := io.mem_data_in.valid
  io.mem_data_in.ready := io.out.ready

  val mem_addr_tag_reg = Reg(init = UInt(initTag, 32))
  val mem_data_in_tag_reg = Reg(init = UInt(initTag, 32))

  when (io.mem_addr.valid && io.mem_addr.ready) {
    mem_addr_tag_reg := mem_addr_tag_reg + UInt(strideTag)
  }

  when (io.mem_data_in.valid && io.mem_data_in.ready) {
    mem_data_in_tag_reg := mem_data_in_tag_reg + UInt(strideTag)
  }

  io.mem_addr_tag := mem_addr_tag_reg
  io.mem_data_in_tag := mem_data_in_tag_reg

}
