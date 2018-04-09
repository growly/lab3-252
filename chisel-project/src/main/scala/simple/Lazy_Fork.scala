package simple

import chisel3._
import chisel3.util._

class Lazy_Fork(num_ports: Int, width: Int) extends Module {
  val io = IO(new Bundle {
    val in = new DecoupledIO(UInt(width.W)).flip()
    val out = Vec(num_ports, new DecoupledIO(UInt(width.W)))
  })

  // Datapath
  for (p <- 0 until num_ports) {
    io.out(p).bits := io.in.bits
  }

  // Control
  var tmp = Bool(true)
  for (p <- 0 until num_ports) {
    io.out(p).valid := io.in.valid & io.in.ready
    io.in.ready := io.out(p).ready & tmp
  }

}
