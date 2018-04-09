package simple

import chisel3._
import chisel3.util._

class Join(num_ports: Int, width: Int) extends Module {
  val io = IO(new Bundle {
    val in = Vec(num_ports, new DecoupledIO(UInt(width.W)).flip())
    val out = new DecoupledIO(Vec(num_ports, UInt(width.W)))
  })

  // Datapath
  for (p <- 0 until num_ports) {
    io.out.bits(p) := io.in(p).bits
  }

  // Control
  var tmp = Bool(true)
  for (p <- 0 until num_ports) {
    tmp = io.in(p).valid & tmp
    io.in(p).ready := io.out.valid & io.out.ready
  }
  io.out.valid := tmp

}
