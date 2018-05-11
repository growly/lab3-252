package elastic

import Chisel._

class Eager_Fork(num_ports: Int, width: Int) extends Module {
  val io = IO(new Bundle {
    val in = new DecoupledIO(UInt(width.W)).flip()
    val out = Vec(num_ports, new DecoupledIO(UInt(width.W)))

  })

  // TODO(aryap): This would be nice.
  //override def toString: String = {
  //  val v = Mux(io.in.valid, 'V'.U, '-'.U)
  //  val r = Mux(io.in.ready, 'R'.U, '-'.U)
  //  var message = f"in: $v%s$r%s 0x${io.in.bits}%x "
  //  for (p <- 0 until num_ports) {
  //    val v = Mux(io.out(p).valid, 'V'.U, '-'.U)
  //    val r = Mux(io.out(p).ready, 'R'.U, '-'.U)
  //    message += f"out: $v%s$r%s 0x${io.out(p).bits}%x "
  //  }
  //  message += "\n"
  //  return message
  //}

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
