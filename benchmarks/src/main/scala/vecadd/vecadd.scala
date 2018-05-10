package benchmarks.vecadd

import Chisel._
import elastic._

class vecadd(addrWidth: Int = 32, dataWidth: Int = 32) extends Module {
  val io = IO(new Bundle {
    val start = Bool(INPUT)
    val done = Bool(OUTPUT)

    val RegA0 = UInt(INPUT, dataWidth) // n
    val RegA1 = UInt(INPUT, dataWidth) // &arr1[0]
    val RegA2 = UInt(INPUT, dataWidth) // &arr2[0]
    val RegA3 = UInt(INPUT, dataWidth) // &arr3[0]

    val mem_p0_addr = new DecoupledIO(UInt(addrWidth.W))
    val mem_p0_addr_tag = UInt(OUTPUT, 32)
    val mem_p0_data_in = new DecoupledIO(UInt(dataWidth.W)).flip()
    val mem_p0_data_in_tag = UInt(OUTPUT, 32)
    val mem_p0_load_idx = UInt(OUTPUT, 32)

    val mem_p1_addr = new DecoupledIO(UInt(addrWidth.W))
    val mem_p1_addr_tag = UInt(OUTPUT, 32)
    val mem_p1_data_in = new DecoupledIO(UInt(dataWidth.W)).flip()
    val mem_p1_data_in_tag = UInt(OUTPUT, 32)
    val mem_p1_load_idx = UInt(OUTPUT, 32)

    val mem_p2_addr = new DecoupledIO(UInt(addrWidth.W))
    val mem_p2_addr_tag = UInt(OUTPUT, 32)
    val mem_p2_data_out = new DecoupledIO(UInt(addrWidth.W))
    val mem_p2_data_out_tag = UInt(OUTPUT, 32)
    val mem_p2_sta_idx = UInt(OUTPUT, 32)
    val mem_p2_std_idx = UInt(OUTPUT, 32)

  })

  val m0 = Module(new Merge(2, dataWidth))
  val fifo0 = Module(new FIFO_wrapper(dataWidth, 2))
  val fifo1 = Module(new FIFO_wrapper(dataWidth, 2))
  val fifo2 = Module(new FIFO_wrapper(dataWidth, 2))
  val ef0 = Module(new Eager_Fork(4, dataWidth))
  val ef1 = Module(new Eager_Fork(2, dataWidth))
  val ld0 = Module(new Load(addrWidth, dataWidth, 0, 3, 0, 2))
  val ld1 = Module(new Load(addrWidth, dataWidth, 1, 3, 1, 2))
  val jo0 = Module(new Join(2, dataWidth))
  val st0 = Module(new Store(addrWidth, dataWidth, 2, 3, 0, 1))
  val b0 = Module(new Branch(dataWidth))

  printf("""[simple]
    m0.io.in(0).bits=0x%x, m0.io.in(0).valid=%d, m0.io.in(0).ready=%d,
    m0.io.in(1).bits=0%x, m0.io.in(1).valid=%d, m0.io.in(1).ready=%d,
    m0.io.out.bits=0%x, m0.io.out.valid=%d, m0.io.out.ready=%d,

    fifo0.io.in.bits=0x%x, fifo0.io.in.valid=%d, fifo0.io.in.ready=%d,
    fifo0.io.out.bits=0x%x, fifo0.io.out.valid=%d, fifo0.io.out.ready=%d,

    ef0.io.in.bits=0x%x, ef0.io.in.valid=%d, ef0.io.in.ready=%d,
    ef0.io.out(0).bits=0x%x, ef0.io.out(0).valid=%d, ef0.io.out(0).ready=%d,
    ef0.io.out(1).bits=0x%x, ef0.io.out(1).valid=%d, ef0.io.out(1).ready=%d,
    ef0.io.out(2).bits=0x%x, ef0.io.out(2).valid=%d, ef0.io.out(2).ready=%d,
    ef0.io.out(3).bits=0x%x, ef0.io.out(3).valid=%d, ef0.io.out(3).ready=%d,

    ld0.io.in.bits=0x%x, ld0.io.in.valid=%d,  ld0.io.in.ready=%d,
    ld0.io.out.bits=0x%x, ld0.io.out.valid=%d, ld0.io.out.ready=%d,

    fifo1.io.in.bits=0x%x, fifo1.io.in.valid=%d, fifo1.io.in.ready=%d,
    fifo1.io.out.bits=0x%x, fifo1.io.out.valid=%d, fifo1.io.out.ready=%d,

    ld1.io.in.bits=0x%x, ld1.io.in.valid=%d,  ld1.io.in.ready=%d,
    ld1.io.out.bits=0x%x, ld1.io.out.valid=%d, ld1.io.out.ready=%d,

    fifo2.io.in.bits=0x%x, fifo2.io.in.valid=%d, fifo2.io.in.ready=%d,
    fifo2.io.out.bits=0x%x, fifo2.io.out.valid=%d, fifo2.io.out.ready=%d,

    jo0.io.in(0).bits=0x%x, jo0.io.in(0).valid=%d, jo0.io.in(0).ready=%d,
    jo0.io.in(1).bits=0x%x, jo0.io.in(1).valid=%d, jo0.io.in(1).ready=%d,
    jo0.io.out.bits(0)=0x%x, jo0.io.out.bits(1)=0x%x, jo0.io.out.valid=%d, jo0.io.out.ready=%d,

    st0.io.in(0).bits=0x%x,  st0.io.in(1).bits=0x%x,
    st0.io.in(0).valid=%d, st0.io.in(1).valid=%d,
    st0.io.in(0).ready=%d, st0.io.in(1).ready=%d,

    ef1.io.in.bits=0x%x, ef1.io.in.valid=%d, ef1.io.in.ready=%d,
    ef1.io.out(0).bits=0x%x, ef1.io.out(0).valid=%d, ef1.io.out(0).ready=%d,
    ef1.io.out(1).bits=0x%x, ef1.io.out(1).valid=%d, ef1.io.out(1).ready=%d,

    b0.io.in.bits=0x%x, b0.io.in.valid=%d, b0.io.in.ready=%d,
    b0.io.cnd=%d,
    b0.io.out(0).bits=0x%x, b0.io.out(0).valid=%d, b0.io.out(0).ready=%d,
    b0.io.out(1).bits=0x%x, b0.io.out(1).valid=%d, b0.io.out(1).ready=%d

    """,
    m0.io.in(0).bits, m0.io.in(0).valid, m0.io.in(0).ready,
    m0.io.in(1).bits, m0.io.in(1).valid, m0.io.in(1).ready,
    m0.io.out.bits, m0.io.out.valid, m0.io.out.ready,

    fifo0.io.in.bits, fifo0.io.in.valid, fifo0.io.in.ready,
    fifo0.io.out.bits, fifo0.io.out.valid, fifo0.io.out.ready,

    ef0.io.in.bits, ef0.io.in.valid, ef0.io.in.ready,
    ef0.io.out(0).bits, ef0.io.out(0).valid, ef0.io.out(0).ready,
    ef0.io.out(1).bits, ef0.io.out(1).valid, ef0.io.out(1).ready,
    ef0.io.out(2).bits, ef0.io.out(2).valid, ef0.io.out(2).ready,
    ef0.io.out(3).bits, ef0.io.out(3).valid, ef0.io.out(3).ready,

    ld0.io.in.bits, ld0.io.in.valid, ld0.io.in.ready,
    ld0.io.out.bits, ld0.io.out.valid, ld0.io.out.ready,

    fifo1.io.in.bits, fifo1.io.in.valid, fifo1.io.in.ready,
    fifo1.io.out.bits, fifo1.io.out.valid, fifo1.io.out.ready,

    ld1.io.in.bits, ld1.io.in.valid, ld1.io.in.ready,
    ld1.io.out.bits, ld1.io.out.valid, ld1.io.out.ready,

    fifo2.io.in.bits, fifo2.io.in.valid, fifo2.io.in.ready,
    fifo2.io.out.bits, fifo2.io.out.valid, fifo2.io.out.ready,

    jo0.io.in(0).bits, jo0.io.in(0).valid, jo0.io.in(0).ready,
    jo0.io.in(1).bits, jo0.io.in(1).valid, jo0.io.in(1).ready,
    jo0.io.out.bits(0), jo0.io.out.bits(1), jo0.io.out.valid, jo0.io.out.ready,

    st0.io.in(0).bits,  st0.io.in(1).bits, st0.io.in(0).valid, st0.io.in(1).valid,
    st0.io.in(0).ready, st0.io.in(1).ready,

    ef1.io.in.bits, ef1.io.in.valid, ef1.io.in.ready,
    ef1.io.out(0).bits, ef1.io.out(0).valid, ef1.io.out(0).ready,
    ef1.io.out(1).bits, ef1.io.out(1).valid, ef1.io.out(1).ready,

    b0.io.in.bits, b0.io.in.valid, b0.io.in.ready,
    b0.io.cond,
    b0.io.out(0).bits, b0.io.out(0).valid, b0.io.out(0).ready,
    b0.io.out(1).bits, b0.io.out(1).valid, b0.io.out(1).ready
  )

  // One cycle start signal for the circuit
  val start_reg = Reg(init = Bool(false))
  when (start_reg) {
    start_reg := false.B
  } .elsewhen (io.start) {
    start_reg := true.B
  }

  // Datapath
  m0.io.in(0).bits := 0.U // initial value of the loop index
  m0.io.in(1).bits := b0.io.out(1).bits // update value of the loop index
  fifo0.io.in.bits := m0.io.out.bits
  ef0.io.in.bits := fifo0.io.out.bits
  ef1.io.in.bits := ef0.io.out(3).bits + 1.U // increment the loop index
  b0.io.cond := ef1.io.out(0).bits < io.RegA0 // compare loop index against N
  b0.io.in.bits := ef1.io.out(1).bits
  ld0.io.in.bits := io.RegA1 + (ef0.io.out(0).bits << 2)
  ld1.io.in.bits := io.RegA2 + (ef0.io.out(1).bits << 2)
  fifo1.io.in.bits := ld0.io.out.bits
  fifo2.io.in.bits := ld1.io.out.bits
  jo0.io.in(0).bits := fifo1.io.out.bits
  jo0.io.in(1).bits := fifo2.io.out.bits
  st0.io.in(0).bits := io.RegA3 + (ef0.io.out(2).bits << 2)
  st0.io.in(1).bits := jo0.io.out.bits(0) + jo0.io.out.bits(1) // arr1[i] + arr2[i]

  io.mem_p0_addr.bits := ld0.io.mem_addr.bits
  ld0.io.mem_data_in.bits := io.mem_p0_data_in.bits

  io.mem_p0_addr_tag := ld0.io.mem_addr_tag
  io.mem_p0_data_in_tag := ld0.io.mem_data_in_tag
  io.mem_p0_load_idx := ld0.io.mem_load_idx

  io.mem_p1_addr.bits := ld1.io.mem_addr.bits
  ld1.io.mem_data_in.bits := io.mem_p1_data_in.bits

  io.mem_p1_addr_tag := ld1.io.mem_addr_tag
  io.mem_p1_data_in_tag := ld1.io.mem_data_in_tag
  io.mem_p1_load_idx := ld1.io.mem_load_idx

  io.mem_p2_addr.bits := st0.io.mem_addr.bits
  io.mem_p2_data_out.bits := st0.io.mem_data_out.bits

  io.mem_p2_addr_tag := st0.io.mem_addr_tag
  io.mem_p2_data_out_tag := st0.io.mem_data_out_tag
  io.mem_p2_sta_idx := st0.io.mem_sta_idx
  io.mem_p2_std_idx := st0.io.mem_std_idx

  // Control
  m0.io.in(0).valid := start_reg
  m0.io.in(1).valid := b0.io.out(1).valid
  m0.io.out.ready := fifo0.io.in.ready

  fifo0.io.in.valid := m0.io.out.valid
  fifo0.io.out.ready := ef0.io.in.ready

  ef0.io.in.valid := fifo0.io.out.valid
  ef0.io.out(0).ready := ld0.io.in.ready
  ef0.io.out(1).ready := ld1.io.in.ready
  ef0.io.out(2).ready := st0.io.in(0).ready
  ef0.io.out(3).ready := ef1.io.in.ready

  ef1.io.in.valid := ef0.io.out(3).valid
  ef1.io.out(0).ready := Bool(true)
  ef1.io.out(1).ready := b0.io.in.ready

  b0.io.in.valid := ef1.io.out(1).valid
  // End of branch
  b0.io.out(0).ready := Bool(false)
  // Loop-back edge
  b0.io.out(1).ready := m0.io.in(1).ready

  ld0.io.in.valid := ef0.io.out(0).valid
  ld0.io.out.ready := fifo1.io.in.ready

  ld1.io.in.valid := ef0.io.out(1).valid
  ld1.io.out.ready := fifo2.io.in.ready

  fifo1.io.in.valid := ld0.io.out.valid
  fifo1.io.out.ready := jo0.io.in(0).ready

  fifo2.io.in.valid := ld1.io.out.valid
  fifo2.io.out.ready := jo0.io.in(1).ready

  jo0.io.in(0).valid := fifo1.io.out.valid
  jo0.io.in(1).valid := fifo2.io.out.valid
  jo0.io.out.ready := st0.io.in(1).ready

  st0.io.in(0).valid := ef0.io.out(2).valid
  st0.io.in(1).valid := jo0.io.out.valid

  io.mem_p0_addr.valid := ld0.io.mem_addr.valid
  ld0.io.mem_addr.ready := io.mem_p0_addr.ready
  ld0.io.mem_data_in.valid := io.mem_p0_data_in.valid
  io.mem_p0_data_in.ready := ld0.io.mem_data_in.ready

  io.mem_p1_addr.valid := ld1.io.mem_addr.valid
  ld1.io.mem_addr.ready := io.mem_p1_addr.ready
  ld1.io.mem_data_in.valid := io.mem_p1_data_in.valid
  io.mem_p1_data_in.ready := ld1.io.mem_data_in.ready

  io.mem_p2_addr.valid := st0.io.mem_addr.valid
  st0.io.mem_addr.ready := io.mem_p2_addr.ready
  io.mem_p2_data_out.valid := st0.io.mem_data_out.valid
  st0.io.mem_data_out.ready := io.mem_p2_data_out.ready
    
  io.done := b0.io.out(0).valid

}
