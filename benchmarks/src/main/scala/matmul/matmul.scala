package benchmarks.matmul

import Chisel._
import elastic._

class matmul(addrWidth: Int = 32, dataWidth: Int = 32) extends Module {
  val io = IO(new Bundle {
    val start = Bool(INPUT)
    val done = Bool(OUTPUT)

    val RegA0 = UInt(INPUT, dataWidth) // N
    val RegA1 = UInt(INPUT, dataWidth) // data_t *A;
    val RegA2 = UInt(INPUT, dataWidth) // data_t *B;
    val RegA3 = UInt(INPUT, dataWidth) // data_t *C;

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

  // For the outer loop.
  val i = 0

  val merge_j = Module(new Merge(2, dataWidth))
  val fifo_j = Module(new FIFO_wrapper(dataWidth, 2))
  val fifo_j_next = Module(new FIFO_wrapper(dataWidth, 2))

  val merge_k = Module(new Merge(2, dataWidth))
  val fifo_k = Module(new FIFO_wrapper(dataWidth, 2))   // Elastic buffer.

  val join_cond = Module(new Join(3, dataWidth))
  val branch_inner = Module(new Branch(dataWidth))

  val eager_fork_body = Module(new Eager_Fork(3, dataWidth))
  val fifo_k_next = Module(new FIFO_wrapper(dataWidth, 2))

  val merge_c = Module(new Merge(2, dataWidth))
  val fifo_c = Module(new FIFO_wrapper(dataWidth, 2))
  val fifo_c_next = Module(new FIFO_wrapper(dataWidth, 2))

  val load_a = Module(new Load(addrWidth, dataWidth, 0, 2, 0, 2))
  val load_b = Module(new Load(addrWidth, dataWidth, 1, 2, 1, 2))

  val fifo_a = Module(new FIFO_wrapper(dataWidth, 2))
  val fifo_b = Module(new FIFO_wrapper(dataWidth, 2))

  val join_load_a_b = Module(new Join(2, dataWidth))

  val join_end = Module(new Join(2, dataWidth))
  val eager_fork_end = Module(new Eager_Fork(2, dataWidth))

  // For the middle loop.
  val branch_middle = Module(new Branch(dataWidth))
  val fifo_c_store = Module(new FIFO_wrapper(dataWidth, 2))
  val eager_fork_middle = Module(new Eager_Fork(3, dataWidth))
  //val join_middle_cond = Module(new Join(2, dataWidth))

  val store_c = Module(new Store(addrWidth, dataWidth, 0, 1, 0, 1))

  // One cycle start signal for the circuit
  val start_reg = Reg(init = Bool(false))
  when (start_reg) {
    start_reg := false.B
  } .elsewhen (io.start) {
    start_reg := true.B
  }

  // --------------------------------------------------------------------------
  // Datapath and control
  // --------------------------------------------------------------------------

  // Nothing to connect .ready to there ^
  // k input into loop.
  merge_k.io.in(0).bits := 0.U  // k is initially 0.
  merge_k.io.in(0).valid := merge_j.io.out.valid
  merge_k.io.in(1).bits := fifo_k_next.io.out.bits
  merge_k.io.in(1).valid := eager_fork_end.io.out(0).valid & fifo_k_next.io.out.valid // Hmmm.
  fifo_k_next.io.out.ready := eager_fork_end.io.out(0).valid & merge_k.io.in(1).ready

  fifo_k.io.in <> merge_k.io.out

  // c input into loop.
  merge_c.io.in(0).bits := 0.U // c is initially 0.
  merge_c.io.in(0).valid := merge_j.io.out.valid
  merge_c.io.in(1).bits := fifo_c_next.io.out.bits
  merge_c.io.in(1).valid := eager_fork_end.io.out(1).valid & fifo_c_next.io.out.valid
  // C is consumed by the next loop iteration or by the failing branch condition.
  fifo_c_next.io.out.ready := 
    (eager_fork_end.io.out(1).valid & merge_c.io.in(1).ready) |
    (eager_fork_middle.io.out(1).valid & fifo_c_store.io.in.ready)

  fifo_c.io.in <> merge_c.io.out

  // Wait for two inputs: k and the previous c value.
  join_cond.io.in(0).valid := fifo_k.io.out.valid
  // join_cond should not signal 'ready' to fifo_k to consume; join_end should.
  // TODO(aryap): if not branching, something should consume fifo_k?
  join_cond.io.in(1).valid := fifo_c.io.out.valid // Wait on fifo_c but don't consume it.
  join_cond.io.in(2).valid := fifo_j.io.out.valid // Need fifo_j to have a j value too.

  branch_inner.io.in.valid := join_cond.io.out.valid
  join_cond.io.out.ready := branch_inner.io.in.ready

  // Branch condition.
  branch_inner.io.cond := fifo_k.io.out.bits < io.RegA0

  eager_fork_body.io.in <> branch_inner.io.out(1)
  // branch_inner.io.out(0) is the done signal.

  // k+1 computes when branch condition is true (output 1)
  fifo_k_next.io.in <> eager_fork_body.io.out(0)
  //fifo_c.io.in <> eager_fork_body.io.out(1)

  // Increment loop counter. TODO(aryap): Looks like we can skip this bit?
  fifo_k_next.io.in.bits := fifo_k.io.out.bits + 1.U

  // A[j*N + k]
  load_a.io.in <> eager_fork_body.io.out(1)
  load_a.io.in.bits := io.RegA1 + ((fifo_j.io.out.bits * io.RegA0 + fifo_k.io.out.bits) << 2)
  fifo_a.io.in <> load_a.io.out
  // B[k*N + i]
  load_b.io.in <> eager_fork_body.io.out(2)
  load_b.io.in.bits := io.RegA2 + ((fifo_k.io.out.bits * io.RegA0 + i.U) << 2)
  fifo_b.io.in <> load_b.io.out

  fifo_c_next.io.in.valid := join_load_a_b.io.out.valid
  join_load_a_b.io.out.ready := fifo_c_next.io.in.ready

  // Only consume fifo_c when the c_next fifo is ready and join has synced.
  fifo_c.io.out.ready := fifo_c_next.io.in.ready & join_load_a_b.io.out.valid
  // Do the inner sum.
  fifo_c_next.io.in.bits := fifo_c.io.out.bits + fifo_a.io.out.bits * fifo_b.io.out.bits

  join_load_a_b.io.out.ready := fifo_c_next.io.in.ready
  join_load_a_b.io.in(0).valid := fifo_a.io.out.valid
  fifo_a.io.out.ready := join_load_a_b.io.out.valid & fifo_c_next.io.in.ready
  join_load_a_b.io.in(1).valid := fifo_b.io.out.valid
  fifo_b.io.out.ready := join_load_a_b.io.out.valid & fifo_c_next.io.in.ready

  join_end.io.in(0).valid := fifo_c_next.io.out.valid
  join_end.io.in(1).valid := fifo_k_next.io.out.valid
  //fifo_k.io.out.ready := fifo_k_next.io.out.ready & join_end.io.out.valid
  fifo_k.io.out.ready :=
    (fifo_k_next.io.in.ready & eager_fork_body.io.out(0).valid) | merge_j.io.out.valid

  eager_fork_end.io.in.valid := join_end.io.out.valid
  // I don't think this does anything...
  join_end.io.out.ready := eager_fork_end.io.in.ready

  // The FIFOs are storage; they replace registers.
  //val c = Reg(0.U(dataWidth.W))
// Load memory connections.
  io.mem_p0_addr.bits := load_a.io.mem_addr.bits
  load_a.io.mem_data_in.bits := io.mem_p0_data_in.bits

  io.mem_p0_addr_tag := load_a.io.mem_addr_tag
  io.mem_p0_data_in_tag := load_a.io.mem_data_in_tag
  io.mem_p0_load_idx := load_a.io.mem_load_idx

  io.mem_p1_addr.bits := load_b.io.mem_addr.bits
  load_b.io.mem_data_in.bits := io.mem_p1_data_in.bits

  io.mem_p1_addr_tag := load_b.io.mem_addr_tag
  io.mem_p1_data_in_tag := load_b.io.mem_data_in_tag
  io.mem_p1_load_idx := load_b.io.mem_load_idx

  io.mem_p0_addr.valid := load_a.io.mem_addr.valid
  load_a.io.mem_addr.ready := io.mem_p0_addr.ready
  load_a.io.mem_data_in.valid := io.mem_p0_data_in.valid
  io.mem_p0_data_in.ready := load_a.io.mem_data_in.ready

  io.mem_p1_addr.valid := load_b.io.mem_addr.valid
  load_b.io.mem_addr.ready := io.mem_p1_addr.ready
  load_b.io.mem_data_in.valid := io.mem_p1_data_in.valid
  io.mem_p1_data_in.ready := load_b.io.mem_data_in.ready

  // In the middle loop, once the inner loop exits, we store c, figure out the
  // next j, and check if we have to branch again.

  eager_fork_middle.io.in.valid := branch_inner.io.out(0).valid
  branch_inner.io.out(0).ready := eager_fork_middle.io.in.ready

  fifo_c_store.io.in.valid := eager_fork_middle.io.out(0).valid
  eager_fork_middle.io.out(0).ready := fifo_c_store.io.in.ready
  fifo_c_store.io.in.bits := fifo_c_next.io.out.bits

  fifo_c_store.io.out.ready := store_c.io.in(1).ready
  store_c.io.in(0).valid := fifo_c_store.io.out.valid
  store_c.io.in(0).bits := io.RegA3 + ((fifo_j.io.out.bits * io.RegA0 + i.U) << 2)
  store_c.io.in(1).valid := fifo_c_store.io.out.valid
  store_c.io.in(1).bits := fifo_c_store.io.out.bits

  // Only proceed once the next value of j has been computed

  branch_middle.io.in.valid := eager_fork_middle.io.out(1).valid
  eager_fork_middle.io.out(1).ready := branch_middle.io.in.ready
  branch_middle.io.cond := fifo_j.io.out.bits < io.RegA0    // j < N

  merge_j.io.in(0).bits := 0.U  // j is initially 0.
  merge_j.io.in(0).valid := /*branch_middle.io.out(0).valid |*/ start_reg // j gets reset by i

  merge_j.io.in(1).bits := fifo_j_next.io.out.bits
  merge_j.io.in(1).valid := fifo_j_next.io.out.valid
  fifo_j_next.io.out.ready := merge_j.io.in(1).ready

  // Only want to load once.
  fifo_j_next.io.in.valid :=
    branch_middle.io.out(1).valid & fifo_j.io.out.valid & ~fifo_j_next.io.out.valid
  fifo_j_next.io.in.bits := fifo_j.io.out.bits + 1.U        // j++
  // fifo_j should be cleared after all things depending on it have consumed it
  // - but we don't know when store_c consume it, other than 1 cycle after it
  // reads it
  fifo_j.io.out.ready := fifo_c_store.io.out.valid
  //fifo_j.io.out.ready := branch_middle.io.out(1).valid & fifo_j_next.io.in.ready

  //fifo_j_next.io.in.valid := eager_fork_middle.io.out(2).valid & fifo_j.io.out.valid
  //fifo_j.io.out.ready := eager_fork_middle.io.out(2).valid & fifo_j_next.io.in.ready

  fifo_j.io.in <> merge_j.io.out

  // Store memory connections.
  io.mem_p2_addr.bits := store_c.io.mem_addr.bits
  io.mem_p2_data_out.bits := store_c.io.mem_data_out.bits

  io.mem_p2_addr_tag := store_c.io.mem_addr_tag
  io.mem_p2_data_out_tag := store_c.io.mem_data_out_tag
  io.mem_p2_sta_idx := store_c.io.mem_sta_idx
  io.mem_p2_std_idx := store_c.io.mem_std_idx

  io.mem_p2_addr.valid := store_c.io.mem_addr.valid
  store_c.io.mem_addr.ready := io.mem_p2_addr.ready
  io.mem_p2_data_out.valid := store_c.io.mem_data_out.valid
  store_c.io.mem_data_out.ready := io.mem_p2_data_out.ready

  // Outer loop.

  printf("""[arya matmul] io.RegAO: 0x%x io.RegA1: 0x%x io.RegA2: 0x%x io.RegA3: 0x%x
    merge_k           [in0] v:%d r:%d b:0x%x  [in1]  v:%d r:%d b:0x%x  [out]  v:%d r:%d b:0x%x 
    fifo_k            [in]  v:%d r:%d b:0x%x                           [out]  v:%d r:%d b:0x%x

    merge_c           [in0] v:%d r:%d b:0x%x  [in1]  v:%d r:%d b:0x%x  [out]  v:%d r:%d b:0x%x 
    fifo_c            [in]  v:%d r:%d b:0x%x                           [out]  v:%d r:%d b:0x%x

    join_cond         [in0] v:%d r:%d         [in1]  v:%d r:%d         [in2]  v:%d r:%d
                                                                       [out]  v:%d r:%d       
    branch_inner      [in]  v:%d r:%d c:%d    [out0] v:%d r:%d         [out1] v:%d r:%d       

    load_a            [in]  v:%d r:%d b:0x%x                           [out]  v:%d r:%d b:0x%x
    load_b            [in]  v:%d r:%d b:0x%x                           [out]  v:%d r:%d b:0x%x

    fifo_a            [in]  v:%d r:%d b:0x%x                           [out]  v:%d r:%d b:0x%x
    fifo_b            [in]  v:%d r:%d b:0x%x                           [out]  v:%d r:%d b:0x%x

    join_load_a_b     [in0] v:%d r:%d         [in1]  v:%d r:%d         [out]  v:%d r:%d       
    join_end          [in0] v:%d r:%d         [in1]  v:%d r:%d         [out]  v:%d r:%d       

    fifo_k_next       [in]  v:%d r:%d b:0x%x                           [out]  v:%d r:%d b:0x%x
    fifo_c_next       [in]  v:%d r:%d b:0x%x                           [out]  v:%d r:%d b:0x%x

    branch_middle     [in]  v:%d r:%d c:%d    [out0] v:%d r:%d         [out1] v:%d r:%d       
    fifo_c_store      [in]  v:%d r:%d b:0x%x                           [out]  v:%d r:%d b:0x%x
    store_c           [in0] v:%d r:%d b:0x%x                           [in1]  v:%d r:%d b:0x%x
    fifo_j_next       [in]  v:%d r:%d b:0x%x                           [out]  v:%d r:%d b:0x%x

    merge_j           [in0] v:%d r:%d b:0x%x  [in1]  v:%d r:%d b:0x%x  [out]  v:%d r:%d b:0x%x 
    fifo_j            [in]  v:%d r:%d b:0x%x                           [out]  v:%d r:%d b:0x%x
    """,
    io.RegA0, io.RegA1, io.RegA2, io.RegA3,
    merge_k.io.in(0).valid, merge_k.io.in(0).ready, merge_k.io.in(0).bits,
      merge_k.io.in(1).valid, merge_k.io.in(1).ready, merge_k.io.in(1).bits,
      merge_k.io.out.valid, merge_k.io.out.ready, merge_k.io.out.bits,

    fifo_k.io.in.valid, fifo_k.io.in.ready, fifo_k.io.in.bits,
      fifo_k.io.out.valid, fifo_k.io.out.ready, fifo_k.io.out.bits,

    merge_c.io.in(0).valid, merge_c.io.in(0).ready, merge_c.io.in(0).bits,
      merge_c.io.in(1).valid, merge_c.io.in(1).ready, merge_c.io.in(1).bits,
      merge_c.io.out.valid, merge_c.io.out.ready, merge_c.io.out.bits,

    fifo_c.io.in.valid, fifo_c.io.in.ready, fifo_c.io.in.bits,
      fifo_c.io.out.valid, fifo_c.io.out.ready, fifo_c.io.out.bits,

    join_cond.io.in(0).valid, join_cond.io.in(0).ready,
      join_cond.io.in(1).valid, join_cond.io.in(1).ready,
      join_cond.io.in(2).valid, join_cond.io.in(2).ready,
      join_cond.io.out.valid, join_cond.io.out.ready,

    branch_inner.io.in.valid, branch_inner.io.in.ready, branch_inner.io.cond,
      branch_inner.io.out(0).valid, branch_inner.io.out(0).ready,
      branch_inner.io.out(1).valid, branch_inner.io.out(1).ready,

    load_a.io.in.valid, load_a.io.in.ready, load_a.io.in.bits,
      load_a.io.out.valid, load_a.io.out.ready, load_a.io.out.bits,

    load_b.io.in.valid, load_b.io.in.ready, load_b.io.in.bits,
      load_b.io.out.valid, load_b.io.out.ready, load_b.io.out.bits,

    fifo_a.io.in.valid, fifo_a.io.in.ready, fifo_a.io.in.bits,
      fifo_a.io.out.valid, fifo_a.io.out.ready, fifo_a.io.out.bits,

    fifo_b.io.in.valid, fifo_b.io.in.ready, fifo_b.io.in.bits,
      fifo_b.io.out.valid, fifo_b.io.out.ready, fifo_b.io.out.bits,

    join_load_a_b.io.in(0).valid, join_load_a_b.io.in(0).ready,
      join_load_a_b.io.in(1).valid, join_load_a_b.io.in(1).ready,
      join_load_a_b.io.out.valid, join_load_a_b.io.out.ready,

    join_end.io.in(0).valid, join_end.io.in(0).ready,
      join_end.io.in(1).valid, join_end.io.in(1).ready,
      join_end.io.out.valid, join_end.io.out.ready,

    fifo_k_next.io.in.valid, fifo_k_next.io.in.ready, fifo_k_next.io.in.bits,
      fifo_k_next.io.out.valid, fifo_k_next.io.out.ready, fifo_k_next.io.out.bits,

    fifo_c_next.io.in.valid, fifo_c_next.io.in.ready, fifo_c_next.io.in.bits,
      fifo_c_next.io.out.valid, fifo_c_next.io.out.ready, fifo_c_next.io.out.bits,

    branch_middle.io.in.valid, branch_middle.io.in.ready, branch_middle.io.cond,
      branch_middle.io.out(0).valid, branch_middle.io.out(0).ready,
      branch_middle.io.out(1).valid, branch_middle.io.out(1).ready,

    fifo_c_store.io.in.valid, fifo_c_store.io.in.ready, fifo_c_store.io.in.bits,
      fifo_c_store.io.out.valid, fifo_c_store.io.out.ready, fifo_c_store.io.out.bits,

    store_c.io.in(0).valid, store_c.io.in(0).ready, store_c.io.in(0).bits,
      store_c.io.in(1).valid, store_c.io.in(1).ready, store_c.io.in(1).bits,

    fifo_j_next.io.in.valid, fifo_j_next.io.in.ready, fifo_j_next.io.in.bits,
      fifo_j_next.io.out.valid, fifo_j_next.io.out.ready, fifo_j_next.io.out.bits,

    merge_j.io.in(0).valid, merge_j.io.in(0).ready, merge_j.io.in(0).bits,
      merge_j.io.in(1).valid, merge_j.io.in(1).ready, merge_j.io.in(1).bits,
      merge_j.io.out.valid, merge_j.io.out.ready, merge_j.io.out.bits,

    fifo_j.io.in.valid, fifo_j.io.in.ready, fifo_j.io.in.bits,
      fifo_j.io.out.valid, fifo_j.io.out.ready, fifo_j.io.out.bits
  )
}
