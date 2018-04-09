package simple

import chisel3._
import chisel3.util._

class simple(addrWidth: Int = 32, dataWidth: Int = 32) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val done = Output(Bool())

    val Reg0 = Input(UInt(32.W))
    val Reg1 = Input(UInt(32.W))
    val Reg2 = Input(UInt(32.W))
    val Reg3 = Input(UInt(32.W))
    val Reg4 = Input(UInt(32.W))

    val mem_p0_addr = new DecoupledIO(UInt(addrWidth.W))
    val mem_p0_data_in = new DecoupledIO(UInt(dataWidth.W)).flip()

    val mem_p1_addr = new DecoupledIO(UInt(addrWidth.W))
    val mem_p1_data_out = new DecoupledIO(UInt(addrWidth.W))
  })

  val m0 = Module(new Merge(2, dataWidth))
  val fifo0 = Module(new FIFO_wrapper(dataWidth, 2))
  val ef0 = Module(new Eager_Fork(3, dataWidth))
  val ef1 = Module(new Eager_Fork(2, dataWidth))
  val ld0 = Module(new Load(addrWidth, dataWidth))
  val j0 = Module(new Join(2, dataWidth))
  val st0 = Module(new Store(addrWidth, dataWidth))
  val b0 = Module(new Branch(dataWidth))

  // One cycle start signal for the circuit
  val start_reg = RegInit(Bool(), false.B)
  when (start_reg) {
    start_reg := false.B
  } .elsewhen (io.start) {
    start_reg := true.B
  }

  // Datapath
  m0.io.in(0).bits := io.Reg1 // initial value of the loop index
  m0.io.in(1).bits := b0.io.out(1).bits // update value of the loop index
  fifo0.io.in.bits := m0.io.out.bits
  ef0.io.in.bits := fifo0.io.out.bits
  ef1.io.in.bits := ef0.io.out(2).bits + 1.U // increment the loop index
  b0.io.cond := ef1.io.out(0).bits < io.Reg2 // compare loop index against N
  b0.io.in.bits := ef1.io.out(1).bits
  j0.io.in(1).bits := ld0.io.out.bits * io.Reg0 + 1.U
  ld0.io.in.bits := (io.Reg3 + ef0.io.out(0).bits) << 2
  j0.io.in(0).bits := (io.Reg4 + ef0.io.out(1).bits) << 2
  st0.io.in(0).bits := j0.io.out.bits(0) // store addr
  st0.io.in(1).bits := j0.io.out.bits(1) // store data

  io.mem_p0_addr.bits := ld0.io.mem_addr.bits
  ld0.io.mem_data_in.bits := io.mem_p0_data_in.bits

  io.mem_p1_addr.bits := st0.io.mem_addr.bits
  io.mem_p1_data_out.bits := st0.io.mem_data_out.bits
        
  // Control
  m0.io.in(0).valid := start_reg
  m0.io.in(1).valid := b0.io.out(1).valid
  m0.io.out.ready := fifo0.io.in.ready

  fifo0.io.in.valid := m0.io.out.valid
  fifo0.io.out.ready := ef0.io.in.ready

  ef0.io.in.valid := fifo0.io.out.valid
  ef0.io.out(0).ready := ld0.io.in.ready
  ef0.io.out(1).ready := j0.io.in(0).ready
  ef0.io.out(2).ready := ef1.io.in.ready

  ef1.io.in.valid := ef0.io.out(2).valid
  ef1.io.out(0).ready := Bool(true)
  ef1.io.out(1).ready := b0.io.in.ready

  b0.io.in.valid := ef1.io.out(1).valid
  // End of branch
  b0.io.out(0).ready := Bool(false)
  // Loop-back edge
  b0.io.out(1).ready := m0.io.in(1).ready

  ld0.io.in.valid := ef0.io.out(0).valid
  ld0.io.out.ready := j0.io.in(1).ready

  // Sync store addr and store data
  j0.io.in(0).valid := ef0.io.out(1).valid
  j0.io.in(1).valid := ld0.io.out.valid
  j0.io.out.ready := st0.io.in(0).ready & st0.io.in(1).ready
  st0.io.in(0).valid := j0.io.out.valid
  st0.io.in(1).valid := j0.io.out.valid

  io.mem_p0_addr.valid := ld0.io.mem_addr.valid
  ld0.io.mem_addr.ready := io.mem_p0_addr.ready
  ld0.io.mem_data_in.valid := io.mem_p0_data_in.valid
  io.mem_p0_data_in.ready := ld0.io.mem_data_in.ready

  io.mem_p1_addr.valid := st0.io.mem_addr.valid
  st0.io.mem_addr.ready := io.mem_p1_addr.ready
  io.mem_p1_data_out.valid := st0.io.mem_data_out.valid
  st0.io.mem_data_out.ready := io.mem_p1_data_out.ready
    
  io.done := b0.io.out(0).valid

}
