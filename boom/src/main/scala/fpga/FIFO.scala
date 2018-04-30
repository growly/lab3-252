package boom

import Chisel._

class FIFO_wrapper(width: Int, depth: Int) extends Module {
  val io = IO(new Bundle {
    val in = new DecoupledIO(UInt(width.W)).flip()
    val out = new DecoupledIO(UInt(width.W))

  })

  val queue = Module(new Queue(UInt(width.W), depth))

  queue.io.enq.bits <> io.in.bits
  queue.io.enq.valid <> io.in.valid
  io.in.ready <> queue.io.enq.ready

  io.out.bits <> queue.io.deq.bits
  io.out.valid <> queue.io.deq.valid
  queue.io.deq.ready <> io.out.ready

}
