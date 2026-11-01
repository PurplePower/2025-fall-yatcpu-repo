// Copyright 2022 Canbin Huang
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package board.z710

import chisel3._
import chisel3.util.{Cat, MuxLookup}
import chisel3.stage.{ChiselGeneratorAnnotation, ChiselStage}
import peripheral._
import riscv.Parameters
import riscv.core.CPU

class Top(binaryFilename: String = "say_goodbye.asmbin") extends Module {
  val io = IO(new Bundle {
    val tx = Output(Bool())
    val rx = Input(Bool())
    val led = Output(Bool())
  })
  val clock_freq = 25000000
  val mem = Module(new Memory(Parameters.MemorySizeInWords))
  val uart = Module(new Uart(clock_freq, 115200))
  val instruction_rom = Module(new InstructionROM(binaryFilename))
  val rom_loader = Module(new ROMLoader(instruction_rom.capacity))
  rom_loader.io.rom_data := instruction_rom.io.data
  rom_loader.io.load_address := Parameters.EntryAddress
  instruction_rom.io.address := rom_loader.io.rom_address
  rom_loader.io.bundle.read_data := 0.U
  val CPU_clkdiv = RegInit(0.U(2.W))
  CPU_clkdiv := CPU_clkdiv + 1.U
  val CPU_tick = CPU_clkdiv === 1.U
  val cpu = withClock(CPU_clkdiv(1).asClock) { Module(new CPU) }
  val timer = withClock(CPU_clkdiv(1).asClock) { Module(new Timer) }
  val loading = !rom_loader.io.load_finished
  cpu.io.instruction_valid := !loading
  cpu.io.instruction := mem.io.instruction
  mem.io.instruction_address := cpu.io.instruction_address
  mem.io.debug_read_address := 0.U
  cpu.io.regs_debug_read_address := 0.U
  cpu.io.csr_regs_debug_read_address := 0.U
  cpu.io.interrupt_flag := Mux(loading, 0.U, Cat(uart.io.signal_interrupt, timer.io.signal_interrupt))
  mem.io.bundle.address := Mux(loading, rom_loader.io.bundle.address, cpu.io.memory_bundle.address)
  mem.io.bundle.write_data := Mux(loading, rom_loader.io.bundle.write_data, cpu.io.memory_bundle.write_data)
  mem.io.bundle.write_strobe := Mux(loading, rom_loader.io.bundle.write_strobe, cpu.io.memory_bundle.write_strobe)
  mem.io.bundle.write_enable := Mux(loading, rom_loader.io.bundle.write_enable,
    cpu.io.deviceSelect === 0.U && cpu.io.memory_bundle.write_enable && CPU_tick)
  uart.io.bundle.address := Mux(cpu.io.deviceSelect === 2.U, cpu.io.memory_bundle.address, 0.U)
  uart.io.bundle.write_data := cpu.io.memory_bundle.write_data
  uart.io.bundle.write_strobe := cpu.io.memory_bundle.write_strobe
  uart.io.bundle.write_enable := !loading && cpu.io.deviceSelect === 2.U && cpu.io.memory_bundle.write_enable && CPU_tick
  uart.io.receive_read := !loading && CPU_tick && cpu.io.deviceSelect === 2.U && mem.io.instruction(6,0) === "b0000011".U
  uart.io.rxd := io.rx
  io.tx := uart.io.txd.asBool
  timer.io.bundle.address := cpu.io.memory_bundle.address
  timer.io.bundle.write_data := cpu.io.memory_bundle.write_data
  timer.io.bundle.write_strobe := cpu.io.memory_bundle.write_strobe
  timer.io.bundle.write_enable := !loading && cpu.io.deviceSelect === 4.U && cpu.io.memory_bundle.write_enable
  cpu.io.memory_bundle.read_data := Mux(loading, 0.U, MuxLookup(cpu.io.deviceSelect, 0.U)(Seq(
    0.U -> mem.io.bundle.read_data, 2.U -> uart.io.bundle.read_data, 4.U -> timer.io.bundle.read_data)))
  val led_count = RegInit(0.U(32.W))
  led_count := Mux(led_count === (clock_freq - 1).U, 0.U, led_count + 1.U)
  io.led := led_count >= (clock_freq / 2).U
}

object VerilogGenerator extends App {
  (new ChiselStage).execute(Array("-X", "verilog", "-td", "verilog/z710"),
    Seq(ChiselGeneratorAnnotation(() => new Top)))
}
