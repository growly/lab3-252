// See LICENSE for license details.

package simple

import org.scalatest._
import org.scalatest.prop._

import chisel3._
import chisel3.util._
import chisel3.iotesters
import chisel3.iotesters.{ChiselFlatSpec, Driver, PeekPokeTester}

import scala.collection.mutable.HashMap
import scala.util.Random

class simpleUnitTester(c: simpleTop) extends PeekPokeTester(c) {

  // simple circuit computes the following loop:
  // for (int i = 0; i < N; i++)
  //   b[i] = k * a[i] + 1
  //

  private var simpleTop = c

  val k = 2
  val init_loop_idx = 0
  val N = 100
  val a_base_addr = 0x00000100
  val b_base_addr = 0x00000200

  poke(simpleTop.io.Reg0, k)
  poke(simpleTop.io.Reg1, init_loop_idx)
  poke(simpleTop.io.Reg2, N)
  poke(simpleTop.io.Reg3, a_base_addr)
  poke(simpleTop.io.Reg4, b_base_addr)

  reset(100)

  // start executing simpleTop circuit
  poke(simpleTop.io.start, true.B)
  step(1)
  poke(simpleTop.io.start, false.B)

  // As long as it is not hang, I'm happy
  while (peek(simpleTop.io.done) != 1)
    step(1)
  expect(simpleTop.io.done, true.B)

  // Give it some more cycles to finish reading/writing
  // FIXME: 'done' is asserted when the loop counter reaches
  // the loop bound; it does not guarantee all the remaining
  // operations have finished
  step(100)
}

/**
  * This is a trivial example of how to run this Specification
  * From within sbt use:
  * {{{
  * testOnly example.test.simpleTester
  * }}}
  * From a terminal shell use:
  * {{{
  * sbt 'testOnly example.test.simpleTester'
  * }}}
  */
class simpleTester extends ChiselFlatSpec {
  private val backendNames = if(firrtl.FileUtils.isCommandAvailable("verilator")) {
    Array("firrtl", "verilator")
  }
  else {
    Array("firrtl")
  }
  for ( backendName <- backendNames ) {
    "simpleTop" should s"calculate proper greatest common denominator (with $backendName)" in {
      Driver(() => new simpleTop, backendName) {
        c => new simpleUnitTester(c)
      } should be (true)
    }
  }

  "Basic test using Driver.execute" should "be used as an alternative way to run specification" in {
    iotesters.Driver.execute(Array(), () => new simpleTop) {
      c => new simpleUnitTester(c)
    } should be (true)
  }

  "using --backend-name verilator" should "be an alternative way to run using verilator" in {
    if(backendNames.contains("verilator")) {
      iotesters.Driver.execute(Array("--backend-name", "verilator"), () => new simpleTop) {
        c => new simpleUnitTester(c)
      } should be(true)
    }
  }

  "running with --is-verbose" should "show more about what's going on in your tester" in {
    iotesters.Driver.execute(Array("--is-verbose"), () => new simpleTop) {
      c => new simpleUnitTester(c)
    } should be(true)
  }

  "running with --fint-write-vcd" should "create a vcd file from your test" in {
    iotesters.Driver.execute(Array("--fint-write-vcd"), () => new simpleTop) {
      c => new simpleUnitTester(c)
    } should be(true)
  }

  "using --help" should s"show the many options available" in {
    iotesters.Driver.execute(Array("--help"), () => new simpleTop) {
      c => new simpleUnitTester(c)
    } should be (true)
  }
}
