package peripheral

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class TimerPeriodTest extends AnyFlatSpec with ChiselScalatestTester {
  it should "repeat every limit plus one clocks and suppress interrupts while disabled" in {
    test(new Timer).withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
      c.io.bundle.write_strobe.foreach(_.poke(true.B))
      c.io.bundle.address.poke(4.U)
      c.io.bundle.write_data.poke(7.U)
      c.io.bundle.write_enable.poke(true.B)
      c.clock.step()
      c.io.bundle.write_enable.poke(false.B)
      var previous = false
      val edges = scala.collection.mutable.ArrayBuffer.empty[Int]
      for (i <- 0 until 40) {
        val current = c.io.signal_interrupt.peek().litToBoolean
        if (current && !previous) edges += i
        previous = current
        c.clock.step()
      }
      assert(edges.size >= 4)
      assert(edges.sliding(2).forall(p => p(1) - p(0) == 8))
      c.io.bundle.address.poke(8.U)
      c.io.bundle.write_data.poke(0.U)
      c.io.bundle.write_enable.poke(true.B)
      c.clock.step()
      c.io.bundle.write_enable.poke(false.B)
      for (_ <- 0 until 24) { c.io.signal_interrupt.expect(false.B); c.clock.step() }
      c.io.bundle.write_data.poke(1.U)
      c.io.bundle.write_enable.poke(true.B)
      c.clock.step()
      c.io.bundle.write_enable.poke(false.B)
      var seen = false
      for (_ <- 0 until 8) { seen ||= c.io.signal_interrupt.peek().litToBoolean; c.clock.step() }
      assert(seen)
    }
  }
}
