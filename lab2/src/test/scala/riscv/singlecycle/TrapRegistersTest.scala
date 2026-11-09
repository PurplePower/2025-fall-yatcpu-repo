package riscv.singlecycle

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class TrapRegistersTest extends AnyFlatSpec with ChiselScalatestTester {
  it should "restore the interrupted register context including s0 and tp" in {
    test(new TestTopModule("trap_registers.asmbin"))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
        c.clock.setTimeout(0)
        c.io.interrupt_flag.poke(0.U)
        c.io.mem_debug_read_address.poke(4.U)
        c.io.csr_regs_debug_read_address.poke(0.U)
        c.io.regs_debug_read_address.poke(0.U)
        c.clock.step(2000)
        c.io.mem_debug_read_data.expect(0xDEADBEEFL.U)
        c.io.interrupt_flag.poke(1.U)
        c.clock.step(8)
        c.io.interrupt_flag.poke(0.U)
        c.clock.step(2000)
        c.io.mem_debug_read_address.poke(8.U)
        c.clock.step()
        c.io.mem_debug_read_data.expect(0x2022.U)
        for ((reg, value) <- Seq(8 -> 0x11223344L, 4 -> 0x55667788L,
          7 -> 0x12345678L, 15 -> 0x76543210L, 27 -> 0x33445566L, 31 -> 0x778899aaL)) {
          c.io.regs_debug_read_address.poke(reg.U)
          c.io.regs_debug_read_data.expect(value.U)
        }
      }
  }
}
