// Copyright 2026 YatCPU contributors
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

package board.cloud_pynq

import chisel3._
import chisel3.stage.{ChiselGeneratorAnnotation, ChiselStage}
import chisel3.util.{Cat, MuxLookup}
import peripheral._
import riscv.Parameters
import riscv.core.CPU

/**
  * Cloud FPGA platform (cg_fpga) top for lab3.
  *
  * The platform input is 100 MHz; the clock wizard in the block design
  * produces 40 MHz.  The lab3 five-stage CPU and Timer share a registered
  * /4 divider and run at 10 MHz.  Memory, UART, SegDisplay,
  * LedMatrixDisplay, ROMLoader and MTime run at 40 MHz.
  * Timer keeps the lab2-compatible 10 MHz tick so the existing timer-based
  * C demos keep their 1 s / 10 ms periods.
  */
class CloudDebug extends Bundle {
  val pc = UInt(32.W)
  val timerLimit = UInt(32.W)
  val timerEnabled = Bool()
  val timerIrq = Bool()
  val seg0 = UInt(8.W)
  val matrixRow = UInt(16.W)
  val txWrites = UInt(32.W)
}

class Top(binaryFilename: String = "led_control_demo.asmbin", simulationDebug: Boolean = false) extends RawModule {
  val debug = if (simulationDebug) Some(IO(Output(new CloudDebug))) else None
  val clock   = IO(Input(Clock()))
  val reset_n = IO(Input(Bool()))   // cg_fpga.btn_rst, active low

  val btn = IO(Input(UInt(8.W)))
  val sw1 = IO(Input(UInt(32.W)))
  val sw2 = IO(Input(UInt(32.W)))

  val uart_rx = IO(Input(Bool()))
  val uart_tx = IO(Output(Bool()))

  val gpio_led  = IO(Output(UInt(32.W)))
  val seg_cs    = IO(Output(UInt(4.W)))
  val seg_data  = IO(Output(UInt(8.W)))
  val ledm_cs   = IO(Output(UInt(6.W)))
  val ledm_data = IO(Output(UInt(16.W)))
  val audio     = IO(Output(UInt(7.W)))

  val vid_active = IO(Output(Bool()))
  val vid_data   = IO(Output(UInt(24.W)))
  val vid_hblank = IO(Output(Bool()))
  val vid_hsync  = IO(Output(Bool()))
  val vid_vblank = IO(Output(Bool()))
  val vid_vsync  = IO(Output(Bool()))

  // Button is active low; synchronize the release.
  val rawReset = (!reset_n).asAsyncReset
  val rstSync = withClockAndReset(clock, rawReset) {
    val sync = RegInit(0.U(2.W))
    sync := Cat(sync(0), reset_n)
    sync
  }
  val reset = !rstSync(1)

  val clock_freq = 40_000_000

  withClockAndReset(clock, reset) {
    val mem  = Module(new AsyncMemory(Parameters.MemorySizeInWords))
    val uart = Module(new Uart(frequency = clock_freq, baudRate = 9600))
    val seg  = Module(new SegDisplay(frequency = clock_freq, refreshUs = 1000))
    val ledm = Module(new LedMatrixDisplay(frequency = clock_freq, refreshUs = 200))
    val mtime = Module(new MTime(clock_freq))

    val instruction_rom = Module(new InstructionROM(binaryFilename))
    val rom_loader      = Module(new ROMLoader(instruction_rom.capacity))

    rom_loader.io.rom_data := instruction_rom.io.data
    rom_loader.io.load_address := Parameters.EntryAddress
    rom_loader.io.bundle.read_data := 0.U

    // Use a registered bit as the /4 clock, rather than a counter decode.
    // CPU_tick qualifies fast-domain accesses at the master edge immediately
    // preceding each CPU rising edge, after the CPU outputs have settled.
    val CPU_clkdiv = RegInit(0.U(2.W))
    CPU_clkdiv := CPU_clkdiv + 1.U
    val CPU_tick = CPU_clkdiv === 1.U
    val cpuClock = CPU_clkdiv(1).asClock
    val cpuResetSync = withClockAndReset(cpuClock, reset.asAsyncReset) {
      val sync = RegInit(0.U(2.W))
      sync := Cat(sync(0), true.B)
      sync
    }
    val cpuReset = !cpuResetSync(1)
    val cpuReady = RegNext(cpuResetSync(1), false.B)
    val timer = withClockAndReset(cpuClock, cpuReset) { Module(new Timer) }

    val loading = !rom_loader.io.load_finished || !cpuReady
    // Timer is in the 10 MHz domain.  Stretch its interrupt level so it
    // survives IF2ID flushes/stalls in the five-stage pipeline.
    val timer_irq_hold = RegInit(0.U(4.W))
    when(timer.io.signal_interrupt) {
      timer_irq_hold := 15.U
    }.elsewhen(timer_irq_hold =/= 0.U) {
      timer_irq_hold := timer_irq_hold - 1.U
    }
    val timer_irq_level = timer_irq_hold =/= 0.U

    val cpu = withClockAndReset(cpuClock, cpuReset) { Module(new CPU) }
    cpu.io.debug_read_address := 0.U
    cpu.io.instruction_valid := !loading
    // Keep the existing combinational instruction ROM and AsyncMemory;
    // changing the memory implementation is independent of CPU division.
    // InstructionROM uses word addresses starting at the binary's first word.
    instruction_rom.io.address := Mux(loading, rom_loader.io.rom_address,
      ((cpu.io.instruction_address - Parameters.EntryAddress) >> 2).asUInt)
    cpu.io.instruction := instruction_rom.io.data
    mem.io.debug_read_address := 0.U
    cpu.io.interrupt_flag := Mux(loading, 0.U,
      Cat(uart.io.signal_interrupt, timer_irq_level))

    when(loading) {
      mem.io.bundle.address      := rom_loader.io.bundle.address
      mem.io.bundle.write_data   := rom_loader.io.bundle.write_data
      mem.io.bundle.write_enable := rom_loader.io.bundle.write_enable
      mem.io.bundle.write_strobe := rom_loader.io.bundle.write_strobe
    }.otherwise {
      mem.io.bundle.address      := cpu.io.memory_bundle.address
      mem.io.bundle.write_data   := cpu.io.memory_bundle.write_data
      mem.io.bundle.write_enable :=
        cpu.io.memory_bundle.write_enable && cpu.io.device_select === 0.U && CPU_tick
      mem.io.bundle.write_strobe := cpu.io.memory_bundle.write_strobe
    }

    // Timer shares the CPU clock, so each store is consumed once directly.
    timer.io.bundle.address := cpu.io.memory_bundle.address
    timer.io.bundle.write_data := cpu.io.memory_bundle.write_data
    timer.io.bundle.write_strobe := cpu.io.memory_bundle.write_strobe
    timer.io.bundle.write_enable := !loading && cpu.io.device_select === 4.U &&
      cpu.io.memory_bundle.write_enable

    // MMIO: 2 = UART, 3 = SegDisplay, 4 = Timer, 5 = LED matrix, 6 = MTime.
    uart.io.bundle.address := Mux(cpu.io.device_select === 2.U, cpu.io.memory_bundle.address, 0.U)
    uart.io.bundle.write_data := cpu.io.memory_bundle.write_data
    uart.io.bundle.write_strobe := cpu.io.memory_bundle.write_strobe
    uart.io.bundle.write_enable :=
      !loading && CPU_tick && cpu.io.device_select === 2.U && cpu.io.memory_bundle.write_enable
    uart.io.receive_read := !loading && CPU_tick && cpu.io.device_select === 2.U &&
      cpu.io.memory_bundle.address === 0xC.U && !cpu.io.memory_bundle.write_enable
    uart.io.rxd := uart_rx.asUInt
    uart_tx := uart.io.txd.asBool

    seg.io.bundle.address := cpu.io.memory_bundle.address
    seg.io.bundle.write_data := cpu.io.memory_bundle.write_data
    seg.io.bundle.write_enable :=
      !loading && CPU_tick && cpu.io.device_select === 3.U && cpu.io.memory_bundle.write_enable
    seg.io.bundle.write_strobe := cpu.io.memory_bundle.write_strobe

    ledm.io.bundle.address := cpu.io.memory_bundle.address
    ledm.io.bundle.write_data := cpu.io.memory_bundle.write_data
    ledm.io.bundle.write_enable :=
      !loading && CPU_tick && cpu.io.device_select === 5.U && cpu.io.memory_bundle.write_enable
    ledm.io.bundle.write_strobe := cpu.io.memory_bundle.write_strobe

    mtime.io.address := cpu.io.memory_bundle.address

    val mmio_read = MuxLookup(cpu.io.device_select, 0.U(32.W))(
      Seq(
        0.U(3.W) -> mem.io.bundle.read_data,
        2.U(3.W) -> uart.io.bundle.read_data,
        3.U(3.W) -> seg.io.bundle.read_data,
        4.U(3.W) -> timer.io.bundle.read_data,
        5.U(3.W) -> ledm.io.bundle.read_data,
        6.U(3.W) -> mtime.io.read_data
      )
    )
    cpu.io.memory_bundle.read_data := Mux(loading, 0.U, mmio_read)

    debug.foreach { d =>
      val seg0 = RegInit(0.U(8.W))
      val matrixRow = RegInit(0.U(16.W))
      val txWrites = RegInit(0.U(32.W))
      when(seg.io.bundle.write_enable && seg.io.bundle.address === 0.U) {
        seg0 := seg.io.bundle.write_data(7, 0)
      }
      when(ledm.io.bundle.write_enable && ledm.io.bundle.address === 4.U) {
        matrixRow := ledm.io.bundle.write_data(15, 0)
      }
      when(uart.io.bundle.write_enable && uart.io.bundle.address === 16.U) {
        txWrites := txWrites + 1.U
      }
      d.pc := cpu.io.instruction_address
      d.timerLimit := timer.io.debug_limit
      d.timerEnabled := timer.io.debug_enabled
      d.timerIrq := timer.io.signal_interrupt
      d.seg0 := seg0
      d.matrixRow := matrixRow
      d.txWrites := txWrites
    }

    seg_cs    := seg.io.seg_cs
    seg_data  := seg.io.seg_data
    ledm_cs   := ledm.io.ledm_cs
    ledm_data := ledm.io.ledm_data

    gpio_led := ~cpu.io.instruction_address
    audio := ~0.U(7.W)

    vid_active := false.B
    vid_data   := 0.U
    vid_hblank := false.B
    vid_hsync  := false.B
    vid_vblank := false.B
    vid_vsync  := false.B
  }
}

object VerilogGenerator extends App {
  require(args.length <= 2, "Usage: VerilogGenerator [program[.asmbin]] [output-directory]")
  val program = args.headOption.getOrElse("led_control_demo")
  require(program.nonEmpty, "Program name must not be empty")
  val binary = if (program.endsWith(".asmbin")) program else s"$program.asmbin"
  val target = args.lift(1).getOrElse("verilog/cloud_pynq")
  require(target.nonEmpty, "Output directory must not be empty")
  (new ChiselStage).execute(
    Array("-X", "verilog", "-td", target),
    Seq(ChiselGeneratorAnnotation(() => new Top(binary)))
  )
}
