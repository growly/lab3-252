package boom

import Chisel._

class Eager_Fork(num_ports: Int, width: Int) extends Module {
  val io = IO(new Bundle {
    val in = new DecoupledIO(UInt(width.W)).flip()
    val out = Vec(num_ports, new DecoupledIO(UInt(width.W)))

  })

  // Datapath
  for (p <- 0 until num_ports) {
    io.out(p).bits := io.in.bits
  }

  // Control
  val reg = Reg(init = Vec.fill(num_ports) { Bool(true) })
  var tmp_ready = Bool(true)
  for (p <- 0 until num_ports) {
    reg(p) := (~(io.in.valid & ~io.in.ready) | (reg(p) & ~io.out(p).ready))
    io.out(p).valid := io.in.valid & reg(p)
    tmp_ready = (~reg(p) | io.out(p).ready) & tmp_ready;
  }
  io.in.ready := tmp_ready

}
