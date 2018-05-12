package elastic

import Chisel._

class Store(addrWidth: Int, dataWidth: Int, initTag: Int, strideTag: Int,
  initStoreIdx: Int, strideStore: Int) extends Module {

  val io = IO(new Bundle {
    val in               = Vec(2, new DecoupledIO(UInt(addrWidth.W)).flip())

    val mem_addr         = new DecoupledIO(UInt(addrWidth.W))
    val mem_addr_tag     = UInt(OUTPUT, 32)
    val mem_data_out     = new DecoupledIO(UInt(addrWidth.W))
    val mem_data_out_tag = UInt(OUTPUT, 32)

    val mem_sta_idx    = UInt(OUTPUT, 32)
    val mem_std_idx    = UInt(OUTPUT, 32)

    val reset_tag_valid = Bool(INPUT)
    val reset_tag_value = UInt(INPUT, 32)

  })

  io.in(0).ready := io.mem_addr.ready
  io.mem_addr.bits := io.in(0).bits
  io.mem_addr.valid := io.in(0).valid

  io.in(1).ready := io.mem_data_out.ready
  io.mem_data_out.bits := io.in(1).bits
  io.mem_data_out.valid := io.in(1).valid

  val mem_addr_tag_reg = Reg(init = UInt(initTag, 32))
  val mem_data_out_tag_reg = Reg(init = UInt(initTag, 32))
  val mem_sta_idx_reg = Reg(init = UInt(initStoreIdx, 32))
  val mem_std_idx_reg = Reg(init = UInt(initStoreIdx, 32))

  when (io.reset_tag_valid) {
    mem_addr_tag_reg := UInt(initTag) + io.reset_tag_value
  }
  .elsewhen (io.mem_addr.valid && io.mem_addr.ready) {
    mem_addr_tag_reg := mem_addr_tag_reg + UInt(strideTag)
    mem_sta_idx_reg := mem_sta_idx_reg + UInt(strideStore)
  }

  when (io.reset_tag_valid) {
    mem_data_out_tag_reg := UInt(initTag) + io.reset_tag_value
  }
  .elsewhen (io.mem_data_out.valid && io.mem_data_out.ready) {
    mem_data_out_tag_reg := mem_data_out_tag_reg + UInt(strideTag)
    mem_std_idx_reg := mem_std_idx_reg + UInt(strideStore)
  }

  io.mem_addr_tag := mem_addr_tag_reg
  io.mem_data_out_tag := mem_data_out_tag_reg
  io.mem_sta_idx := mem_sta_idx_reg
  io.mem_std_idx := mem_std_idx_reg

}
