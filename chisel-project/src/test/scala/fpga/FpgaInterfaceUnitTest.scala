// See LICENSE for license details.

package fpga

import org.scalatest._
import org.scalatest.prop._

import chisel3._
import chisel3.util._
import chisel3.iotesters
import chisel3.iotesters.{ChiselFlatSpec, Driver, PeekPokeTester}

import scala.collection.mutable.HashMap
import scala.util.Random

class FpgaInterfaceUnitTester(c: FpgaInterface) extends PeekPokeTester(c) {

  // Generate random register content mappings for each of 32 int registers.
  val regfile = new HashMap[Int, Int];
  val r = scala.util.Random
  for (j <- 0 to 31) {
    regfile(j) = r.nextInt(Int.MaxValue/64)
  }

  private var pc = 0;

  private val fpga = c

  expect(fpga.io.reach, 2000.U)
  expect(fpga.io.done, false.B)
  for (i <- 0 to 0x42 - 1) {
    poke(fpga.io.currentPC, pc)
    expect(fpga.io.runnable, false.B)
    expect(fpga.io.regCopyDone, false.B)
    expect(fpga.io.done, false.B)
    step(1)
    pc = pc + 1
  }
  poke(fpga.io.currentPC, pc)
  expect(fpga.io.regCopyDone, false.B)
  expect(fpga.io.done, false.B)

  expect(fpga.io.runnable, true.B)
  // So there's a cycle between runnable being true and us being able to
  // initiate the run - because it takes a cycle to enter the sStartWait state.
  step(1)

  poke(fpga.io.start, true.B)
  step(1)
  // Should still be true since current PC hasn't changed.
  expect(fpga.io.runnable, true.B)

  val expectedArchRegs = Array(23, 0, 12, 18, 9)
  for (i <- 0 to expectedArchRegs.length - 1) {
    expect(fpga.io.regCopyDone, false.B)
    expect(fpga.io.archRegAddr, expectedArchRegs(i))
    // Does this take 0 cycles? 1 cycle?
    poke(fpga.io.regData, UInt(regfile(expectedArchRegs(i))))
    step(1)
  }
  expect(fpga.io.regCopyDone, true.B)

  // The "FPGA" should now be "running", and doing its thing. We know this
  // should take 1 cycle per register to sum:
  ///for (i <- 0 to 5) {
  //  expect(fpga.io.done, false.B)
  //  step(1)
  //}
  //expect(fpga.io.done, true.B)
  
  def sumCheck(): Int = {
    var sum: Int = 0
    for (i <- expectedArchRegs) {
      sum += regfile(i)
    }
    sum
  }

  // Wait until the done signal is asserted
  while (peek(fpga.io.done) != 1) {
    step(1)
  }

  expect(fpga.io.done, true.B)
  expect(fpga.io.result, sumCheck.U)

}

/**
  * This is a trivial example of how to run this Specification
  * From within sbt use:
  * {{{
  * testOnly example.test.FpgaInterfaceTester
  * }}}
  * From a terminal shell use:
  * {{{
  * sbt 'testOnly example.test.FpgaInterfaceTester'
  * }}}
  */
class FpgaInterfaceTester extends ChiselFlatSpec {
  private val backendNames = if(firrtl.FileUtils.isCommandAvailable("verilator")) {
    Array("firrtl", "verilator")
  }
  else {
    Array("firrtl")
  }
  for ( backendName <- backendNames ) {
    "FpgaInterface" should s"calculate proper greatest common denominator (with $backendName)" in {
      Driver(() => new FpgaInterface, backendName) {
        c => new FpgaInterfaceUnitTester(c)
      } should be (true)
    }
  }

  "Basic test using Driver.execute" should "be used as an alternative way to run specification" in {
    iotesters.Driver.execute(Array(), () => new FpgaInterface) {
      c => new FpgaInterfaceUnitTester(c)
    } should be (true)
  }

  "using --backend-name verilator" should "be an alternative way to run using verilator" in {
    if(backendNames.contains("verilator")) {
      iotesters.Driver.execute(Array("--backend-name", "verilator"), () => new FpgaInterface) {
        c => new FpgaInterfaceUnitTester(c)
      } should be(true)
    }
  }

  "running with --is-verbose" should "show more about what's going on in your tester" in {
    iotesters.Driver.execute(Array("--is-verbose"), () => new FpgaInterface) {
      c => new FpgaInterfaceUnitTester(c)
    } should be(true)
  }

  "running with --fint-write-vcd" should "create a vcd file from your test" in {
    iotesters.Driver.execute(Array("--fint-write-vcd"), () => new FpgaInterface) {
      c => new FpgaInterfaceUnitTester(c)
    } should be(true)
  }

  "using --help" should s"show the many options available" in {
    iotesters.Driver.execute(Array("--help"), () => new FpgaInterface) {
      c => new FpgaInterfaceUnitTester(c)
    } should be (true)
  }
}
