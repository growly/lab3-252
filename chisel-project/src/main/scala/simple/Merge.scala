package simple

import chisel3._
import chisel3.util._

class Merge(num_ports: Int, width: Int) extends Module {
  val io = IO(new Bundle {
    val in = Vec(num_ports, new DecoupledIO(UInt(width.W)).flip())
    val out = new DecoupledIO(UInt(width.W))
  })

  // Datapath
  io.out.bits := UInt(0)
  for (p <- 0 until num_ports) {
    when (io.in(p).valid) {
      io.out.bits := io.in(p).bits
    }
  }

  // Control
  var tmp = Bool(false)
  for (p <- 0 until num_ports) {
    tmp = io.in(p).valid |  tmp
    io.in(p).ready := io.out.ready
  }
  io.out.valid := tmp

}
