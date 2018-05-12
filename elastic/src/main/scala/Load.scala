package elastic

import Chisel._

class Load(addrWidth: Int, dataWidth: Int, initTag: Int, strideTag: Int,
  initLoadIdx: Int, strideLoad: Int) extends Module {

  val io = IO(new Bundle {
    val in              = new DecoupledIO(UInt(addrWidth.W)).flip()
    val out             = new DecoupledIO(UInt(dataWidth.W))

    val mem_addr        = new DecoupledIO(UInt(addrWidth.W))
    val mem_addr_tag    = UInt(OUTPUT, 32)

    val mem_data_in     = new DecoupledIO(UInt(dataWidth.W)).flip()
    val mem_data_in_tag = UInt(OUTPUT, 32)

    val mem_load_idx    = UInt(OUTPUT, 32)

    val reset_tag_valid = Bool(INPUT)
    val reset_tag_value = UInt(INPUT, 32)

  })

  io.in.ready := io.mem_addr.ready //&& io.out.ready
  io.mem_addr.bits := io.in.bits
  io.mem_addr.valid := io.in.valid //&& io.in.ready

  io.out.bits := io.mem_data_in.bits
  io.out.valid := io.mem_data_in.valid
  io.mem_data_in.ready := io.out.ready

  val mem_addr_tag_reg = Reg(init = UInt(initTag, 32))
  val mem_data_in_tag_reg = Reg(init = UInt(initTag, 32))
  val mem_load_idx_reg = Reg(init = UInt(initLoadIdx, 32))

  when (io.reset_tag_valid) {
    mem_addr_tag_reg := UInt(initTag) + io.reset_tag_value
  }
  .elsewhen (io.mem_addr.valid && io.mem_addr.ready) {
    mem_addr_tag_reg := mem_addr_tag_reg + UInt(strideTag)
    mem_load_idx_reg := mem_load_idx_reg + UInt(strideLoad)
  }

  when (io.reset_tag_valid) {
    mem_data_in_tag_reg := UInt(initTag) + io.reset_tag_value
  }
  .elsewhen (io.mem_data_in.valid && io.mem_data_in.ready) {
    mem_data_in_tag_reg := mem_data_in_tag_reg + UInt(strideTag)
  }

  io.mem_addr_tag := mem_addr_tag_reg
  io.mem_data_in_tag := mem_data_in_tag_reg
  io.mem_load_idx := mem_load_idx_reg
}
