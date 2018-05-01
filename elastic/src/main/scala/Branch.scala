package elastic

import Chisel._

class Branch(width: Int) extends Module {
  val io = IO(new Bundle {
    val in   = new DecoupledIO(UInt(width.W)).flip()
    val cond = Bool(INPUT) // 0: first input, 1: second input
    val out  = Vec(2, new DecoupledIO(UInt(width.W)))

  })

  // Datapath
  io.out(0).bits := io.in.bits;
  io.out(1).bits := io.in.bits;

  // Control
  io.out(0).valid := io.in.valid & ~io.cond
  io.out(1).valid := io.in.valid & io.cond
  io.in.ready := (io.cond | io.out(0).ready) &
                 (~io.cond | io.out(1).ready)

}
