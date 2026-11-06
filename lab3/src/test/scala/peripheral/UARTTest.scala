package peripheral

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class UARTTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "UART MMIO"

  it should "retain RX until an explicit receive read and transmit one 8-N-1 frame" in {
    test(new Uart(1000000, 100000)).withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
      c.io.rxd.poke(1.U)
      c.io.receive_read.poke(false.B)
      c.io.bundle.address.poke(0.U)
      c.io.bundle.write_enable.poke(false.B)
      c.io.bundle.write_data.poke(0.U)
      c.io.bundle.write_strobe.foreach(_.poke(true.B))
      c.clock.step(20)
      def rxBit(v: Int): Unit = { c.io.rxd.poke(v.U); c.clock.step(10) }
      rxBit(0)
      for (i <- 0 until 8) rxBit((0xA5 >> i) & 1)
      rxBit(1)
      c.clock.step(20)
      c.io.signal_interrupt.expect(true.B)
      c.io.bundle.address.poke(12.U)
      c.clock.step(12) // An unselected access with the same offset is not an ACK.
      c.io.bundle.read_data.expect(0xA5.U)
      c.io.signal_interrupt.expect(true.B)
      c.io.bundle.address.poke(20.U)
      c.io.bundle.read_data.expect(3.U)
      c.io.receive_read.poke(true.B)
      c.clock.step() // Reading status must not consume RX either.
      c.io.signal_interrupt.expect(true.B)
      c.io.bundle.address.poke(12.U)
      c.clock.step()
      c.io.receive_read.poke(false.B)
      c.io.signal_interrupt.expect(false.B)
      c.io.bundle.address.poke(20.U)
      c.io.bundle.read_data.expect(1.U)

      c.io.bundle.address.poke(16.U)
      c.io.bundle.write_data.poke(0x4E.U)
      c.io.bundle.write_enable.poke(true.B)
      c.clock.step()
      c.io.bundle.write_enable.poke(false.B)
      var wait = 0
      while (c.io.txd.peek().litValue == 1 && wait < 30) { c.clock.step(); wait += 1 }
      assert(wait < 30)
      c.clock.step(5)
      c.io.txd.expect(0.U)
      for (i <- 0 until 8) { c.clock.step(10); c.io.txd.expect(((0x4E >> i) & 1).U) }
      c.clock.step(10)
      c.io.txd.expect(1.U)
      for (_ <- 0 until 120) { c.clock.step(); c.io.txd.expect(1.U) }
    }
  }
}
