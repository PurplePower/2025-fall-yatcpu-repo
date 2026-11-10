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
  // The board derives cpu_clock from clk_wiz; the harness uses a /4 divider.
  val cpuDiv = RegInit(0.U(2.W))
  cpuDiv := cpuDiv + 1.U
  top.clock := clock
  top.cpu_clock := cpuDiv(1).asClock
  top.reset_n := !reset.asBool
  top.btn := "hff".U
  top.sw1 := "hffffffff".U
  top.sw2 := "hffffffff".U
  top.uart_rx := io.rx
  io.tx := top.uart_tx
  io.debug := top.debug.get
}

class CloudPynqTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "cloud_pynq board"

  it should "run led_control_demo and initialize display and timer" in {
    test(new CloudHarness("led_control_demo_fast.asmbin"))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
        c.clock.setTimeout(0)
        c.io.rx.poke(true.B)
        c.clock.step(240000)
        c.io.debug.timerLimit.expect(19999.U)
        c.io.debug.timerEnabled.expect(true.B)
        val codes = Seq(0xF9, 0xA4, 0xB0, 0x99)
        val seg0 = c.io.debug.seg0.peek().litValue.toInt
        assert(codes.contains(seg0), s"demo did not initialize the display: seg0=0x$seg0%02x")
      }
  }

}
