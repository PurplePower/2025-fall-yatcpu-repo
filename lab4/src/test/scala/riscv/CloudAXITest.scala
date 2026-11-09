package riscv

import bus.AXI4LiteChannels
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import peripheral.AXIMMIO
import riscv.core.fivestage_final.{CLINT, Control}

class CloudAXITest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "cloud_pynq AXI integration"

  it should "remember a timer pulse across a stall and accept it only once" in {
    test(new CLINT) { c =>
      c.io.interrupt_flag.poke(0.U)
      c.io.instruction_id.poke("h00000013".U)
      c.io.instruction_address_if.poke("h1234".U)
      c.io.jump_flag.poke(false.B)
      c.io.jump_address.poke(0.U)
      c.io.csr_bundle.mstatus.poke("h88".U)
      c.io.csr_bundle.mtvec.poke("h2000".U)
      c.io.csr_bundle.mepc.poke("h1234".U)
      c.io.csr_bundle.mcause.poke(0.U)
      c.io.stall_flag_ctrl.poke(true.B)
      c.io.interrupt_flag.poke(1.U)
      c.io.id_interrupt_assert.expect(false.B)
      c.io.csr_bundle.direct_write_enable.expect(false.B)
      c.clock.step()
      c.io.interrupt_flag.poke(0.U)
      c.clock.step(25)
      c.io.id_interrupt_assert.expect(false.B)
      c.io.stall_flag_ctrl.poke(false.B)
      c.io.id_interrupt_assert.expect(true.B)
      c.io.csr_bundle.mcause_write_data.expect("h80000007".U)
      c.io.csr_bundle.mepc_write_data.expect("h1234".U)
      c.io.id_interrupt_handler_address.expect("h2000".U)
      c.clock.step()
      c.io.id_interrupt_assert.expect(false.B)
      // A long timer level is one event, even if MIE is restored early.
      c.io.interrupt_flag.poke(1.U)
      c.clock.step()
      c.io.id_interrupt_assert.expect(false.B)
      c.clock.step(15)
      c.io.id_interrupt_assert.expect(false.B)
      // mret also waits for the older AXI memory access to finish.
      c.io.interrupt_flag.poke(0.U)
      c.io.instruction_id.poke("h30200073".U)
      c.io.stall_flag_ctrl.poke(true.B)
      c.io.csr_bundle.direct_write_enable.expect(false.B)
      c.clock.step(3)
      c.io.stall_flag_ctrl.poke(false.B)
      c.io.id_interrupt_assert.expect(true.B)
      c.io.id_interrupt_handler_address.expect("h1234".U)
    }
  }

  it should "flush trap redirects and defer branches while MEM is stalled" in {
    test(new Control) { c =>
      c.io.jump_flag.poke(true.B)
      c.io.jump_instruction_id.poke(false.B)
      c.io.rs1_id.poke(0.U)
      c.io.rs2_id.poke(0.U)
      c.io.rd_ex.poke(0.U)
      c.io.rd_mem.poke(0.U)
      c.io.memory_read_enable_ex.poke(false.B)
      c.io.memory_read_enable_mem.poke(false.B)
      c.io.stall_flag_if.poke(true.B)
      c.io.stall_flag_mem.poke(false.B)
      c.io.if2id_flush.expect(true.B)
      c.io.redirect_stall.expect(false.B)
      c.io.pc_stall.expect(false.B)
      c.io.stall_flag_mem.poke(true.B)
      c.io.if2id_flush.expect(false.B)
      c.io.redirect_stall.expect(true.B)
      c.io.jump_instruction_id.poke(true.B)
      c.io.pc_stall.expect(true.B)
      c.io.if2id_flush.expect(false.B)
      c.io.stall_flag_mem.poke(false.B)
      c.io.if2id_flush.expect(true.B)
    }
  }

  class MMIOBox extends Module {
    val io = IO(new Bundle {
      val channels = Flipped(new AXI4LiteChannels(32, 32))
      val writes = Output(UInt(32.W))
    })
    val adapter = Module(new AXIMMIO)
    adapter.io.channels <> io.channels
    val counter = RegInit(0.U(32.W))
    counter := counter + 1.U
    adapter.io.registers.read_data := counter
    val writes = RegInit(0.U(32.W))
    when(adapter.io.registers.write_enable) { writes := writes + 1.U }
    io.writes := writes
  }

  it should "hold read responses and commit one store under AXI backpressure" in {
    test(new MMIOBox) { c =>
      val a = c.io.channels
      a.read_address_channel.ARADDR.poke("hc0000000".U)
      a.read_address_channel.ARPROT.poke(0.U)
      a.read_address_channel.ARVALID.poke(false.B)
      a.read_data_channel.RREADY.poke(false.B)
      a.write_address_channel.AWADDR.poke("h60000000".U)
      a.write_address_channel.AWPROT.poke(0.U)
      a.write_address_channel.AWVALID.poke(false.B)
      a.write_data_channel.WDATA.poke("h1c0".U)
      a.write_data_channel.WSTRB.poke(15.U)
      a.write_data_channel.WVALID.poke(false.B)
      a.write_response_channel.BREADY.poke(false.B)
      def until(condition: => Boolean): Unit = {
        var n = 0
        while (!condition && n < 30) { c.clock.step(); n += 1 }
        assert(condition, "AXI transaction timed out")
      }
      a.read_address_channel.ARVALID.poke(true.B)
      until(a.read_address_channel.ARREADY.peekBoolean())
      c.clock.step()
      a.read_address_channel.ARVALID.poke(false.B)
      until(a.read_data_channel.RVALID.peekBoolean())
      val snapshot = a.read_data_channel.RDATA.peek().litValue
      for (_ <- 0 until 20) {
        c.clock.step()
        a.read_data_channel.RVALID.expect(true.B)
        a.read_data_channel.RDATA.expect(snapshot.U)
      }
      a.read_data_channel.RREADY.poke(true.B)
      c.clock.step(3)
      a.write_address_channel.AWVALID.poke(true.B)
      until(a.write_address_channel.AWREADY.peekBoolean())
      c.clock.step()
      a.write_address_channel.AWVALID.poke(false.B)
      a.write_data_channel.WVALID.poke(true.B)
      until(a.write_data_channel.WREADY.peekBoolean())
      c.clock.step()
      a.write_data_channel.WVALID.poke(false.B)
      until(a.write_response_channel.BVALID.peekBoolean())
      c.clock.step(20)
      c.io.writes.expect(1.U)
      a.write_response_channel.BREADY.poke(true.B)
      c.clock.step(3)
      c.io.writes.expect(1.U)
    }
  }
}
