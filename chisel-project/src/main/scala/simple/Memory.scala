package simple

import chisel3._
import chisel3.util._

class Memory(addrWidth: Int = 32,
             dataWidth: Int = 32,
             readLatency: UInt = 50.U,
             writeLatency: UInt = 50.U
  ) extends Module {
  val io = IO(new Bundle {
    // read interface
    val mem_p0_addr = new DecoupledIO(UInt(addrWidth.W)).flip()
    val mem_p0_data_in = new DecoupledIO(UInt(dataWidth.W))

    // write interface
    val mem_p1_addr = new DecoupledIO(UInt(addrWidth.W)).flip()
    val mem_p1_data_out = new DecoupledIO(UInt(addrWidth.W)).flip()
  })

  // A 1000-element memory
  val my_mem = Mem(1000, UInt(32.W))
  for (i <- 0 to 999) {
    my_mem(i) := UInt(i)
  }

  // Read logic
  val start_read = RegInit(Bool(), false.B)
  val read_counter = RegInit(UInt(32.W), 0.U)

  val mem_p0_addr_reg = RegInit(UInt(addrWidth.W), 0.U)
  val mem_p0_addr_ready_reg = RegInit(Bool(), true.B)
  val mem_p0_data_in_reg = RegInit(UInt(dataWidth.W), 0.U)
  val mem_p0_data_in_valid_reg = RegInit(Bool(), false.B)

  io.mem_p0_addr.ready := mem_p0_addr_ready_reg
  io.mem_p0_data_in.bits := mem_p0_data_in_reg
  io.mem_p0_data_in.valid := mem_p0_data_in_valid_reg

  when (read_counter === readLatency) {
    start_read := false.B
    mem_p0_addr_ready_reg := true.B
    mem_p0_data_in_valid_reg := true.B
    mem_p0_data_in_reg := my_mem(mem_p0_addr_reg(31, 2))
    printf("Finished reading\n")

  } .elsewhen (io.mem_p0_addr.valid && io.mem_p0_addr.ready) {
    start_read := true.B
    mem_p0_addr_reg := io.mem_p0_addr.bits
    mem_p0_addr_ready_reg := false.B
    printf(p"Reading from address: 0x${Hexadecimal(io.mem_p0_addr.bits)}\n")

  }

  // Stay valid until the recipient is ready to accept data
  when (mem_p0_data_in_valid_reg && io.mem_p0_data_in.ready) {
    mem_p0_data_in_valid_reg := false.B
  }

  when (start_read && read_counter < readLatency) {
    read_counter := read_counter + 1.U
  } .elsewhen (read_counter === readLatency) {
    read_counter := 0.U
  }

  // Write logic 
  val start_write = RegInit(Bool(), false.B)
  val write_counter = RegInit(UInt(32.W), 0.U)

  val mem_p1_addr_reg = RegInit(UInt(addrWidth.W), 0.U)
  val mem_p1_addr_ready_reg = RegInit(Bool(), true.B)
  val mem_p1_data_out_reg = RegInit(UInt(dataWidth.W), 0.U)
  val mem_p1_data_out_ready_reg = RegInit(Bool(), true.B)

  io.mem_p1_addr.ready := mem_p1_addr_ready_reg
  io.mem_p1_data_out.ready := mem_p1_data_out_ready_reg

  when (write_counter === writeLatency) {
    start_write := false.B
    mem_p1_addr_ready_reg := true.B // ready for the next write request
    mem_p1_data_out_ready_reg := true.B // ready for the next write request
    my_mem(mem_p1_addr_reg(31, 2)) := mem_p1_data_out_reg
    printf("Finished writing\n")

  } .elsewhen (io.mem_p1_addr.valid && io.mem_p1_data_out.valid &&
               io.mem_p1_addr.ready && io.mem_p1_data_out.ready) {
    start_write := true.B
    mem_p1_addr_reg := io.mem_p1_addr.bits
    mem_p1_data_out_reg := io.mem_p1_data_out.bits
    mem_p1_addr_ready_reg := false.B
    mem_p1_data_out_ready_reg := false.B

    printf(p"Writing to address: 0x${Hexadecimal(io.mem_p1_addr.bits)}\n")
    printf(p"Writing data: ${io.mem_p1_data_out.bits}\n")
  }

  when (start_write && write_counter < writeLatency) {
    write_counter := write_counter + 1.U
  } .elsewhen (write_counter === writeLatency) {
    write_counter := 0.U
  }
}
