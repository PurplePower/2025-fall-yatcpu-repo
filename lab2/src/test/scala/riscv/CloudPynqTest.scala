package riscv

import board.cloud_pynq.{CloudDebug, Top}
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class CloudHarness(binary: String) extends Module {
  val io = IO(new Bundle {
    val debug = Output(new CloudDebug)
    val rx = Input(Bool())
    val tx = Output(Bool())
  })
  val top = Module(new Top(binary, simulationDebug = true))
  top.clock := clock
  top.reset_n := !reset.asBool
  top.btn := "hff".U
  top.sw1 := "hffffffff".U
  top.sw2 := "hffffffff".U
  top.uart_rx := io.rx
  io.tx := top.uart_tx
  io.debug := top.debug.get
}

class CloudPynqTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "cloud_pynq timer animation"

  it should "rotate all four styles repeatedly through actual Timer interrupts" in {
    test(new CloudHarness("led_control_demo_fast.asmbin"))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
        c.clock.setTimeout(0)
        c.io.rx.poke(true.B)
        c.clock.step(240000)
        c.io.debug.timerLimit.expect(19999.U)
        c.io.debug.timerEnabled.expect(true.B)
        val codes = Seq(0xF9, 0xA4, 0xB0, 0x99)
        var last = codes.indexOf(c.io.debug.seg0.peek().litValue.toInt)
        assert(last >= 0, "demo did not initialize the display")
        for (_ <- 0 until 8) {
          c.clock.step(80000)
          last = (last + 1) & 3
          c.io.debug.seg0.expect(codes(last).U)
        }
        // One incoming byte is acknowledged by the ISR and echoed once by main.
        def rxBit(value: Boolean): Unit = { c.io.rx.poke(value.B); c.clock.step(4167) }
        rxBit(false)
        for (i <- 0 until 8) rxBit(((0xA5 >> i) & 1) != 0)
        rxBit(true)
        c.clock.step(100000)
        c.io.debug.txWrites.expect(1.U)
        c.clock.step(160000)
        c.io.debug.txWrites.expect(1.U)
      }
  }

}
