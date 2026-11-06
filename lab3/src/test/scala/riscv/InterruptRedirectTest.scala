package riscv

import chisel3._
import chisel3.util.MuxLookup
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class InterruptRedirectHarness extends Module {
  val io = IO(new Bundle {
    val interrupt = Input(UInt(Parameters.InterruptFlagWidth))
    val pc = Output(UInt(Parameters.AddrWidth))
    val register = Input(UInt(Parameters.PhysicalRegisterAddrWidth))
    val value = Output(UInt(Parameters.DataWidth))
  })
  val cpu = Module(new core.fivestage_final.CPU)
  // A load-use hazard immediately followed by a taken branch. The handler
  // increments s0; the instruction after mret must never increment s1.
  val program = Seq(
    0x1000L -> 0x00000413L, // li s0,0
    0x1004L -> 0x00000493L, // li s1,0
    0x1008L -> 0x000012b7L, // lui t0,1
    0x100cL -> 0x10028293L, // addi t0,t0,0x100
    0x1010L -> 0x30529073L, // csrw mtvec,t0
    0x1014L -> 0x08800293L, // li t0,0x88 (MIE and MPIE)
    0x1018L -> 0x30029073L, // csrw mstatus,t0
    0x101cL -> 0x00002303L, // lw t1,0(zero)
    0x1020L -> 0x00130393L, // addi t2,t1,1
    0x1024L -> 0xfe000ce3L, // beq zero,zero,0x101c
    0x1100L -> 0x00140413L, // addi s0,s0,1
    0x1104L -> 0x30200073L, // mret
    0x1108L -> 0x00148493L  // addi s1,s1,1 (wrong path)
  )
  cpu.io.instruction := MuxLookup(cpu.io.instruction_address, 0x13.U(32.W))(
    program.map { case (address, instruction) => address.U(32.W) -> instruction.U(32.W) })
  cpu.io.instruction_valid := true.B
  cpu.io.memory_bundle.read_data := 7.U
  cpu.io.interrupt_flag := io.interrupt
  cpu.io.debug_read_address := io.register
  io.value := cpu.io.debug_read_data
  io.pc := cpu.io.instruction_address
}

class InterruptRedirectTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "five-stage interrupt redirects"

  it should "defer a trap during load-use stall, flush the pending branch, and return twice" in {
    test(new InterruptRedirectHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
      c.io.interrupt.poke(0.U)
      c.io.register.poke(8.U)
      for (expected <- 1 to 2) {
        var waited = 0
        while (c.io.pc.peek().litValue != 0x1020 && waited < 200) {
          c.clock.step()
          waited += 1
        }
        assert(waited < 200, "CPU did not return to the foreground load-use sequence")
        // Latch a one-cycle timer request with the dependent ADDI in IF2ID.
        c.io.interrupt.poke(1.U)
        c.clock.step()
        c.io.interrupt.poke(0.U)
        c.io.pc.expect(0x1024.U)
        c.clock.step()
        c.io.pc.expect(0x1024.U) // load-use stall must retain the request
        c.clock.step()
        c.io.pc.expect(0x1100.U)
        c.clock.step(80)
        c.io.register.poke(8.U)
        c.io.value.expect(expected.U)
        c.io.register.poke(9.U)
        c.io.value.expect(0.U) // mret flushed its following instruction
        c.io.register.poke(7.U)
        c.io.value.expect(8.U) // the interrupted ADDI completed
      }
    }
  }
}
