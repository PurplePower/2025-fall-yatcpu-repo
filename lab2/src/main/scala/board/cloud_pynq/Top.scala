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
import chisel3.util.{Cat, MuxLookup, log2Up}
import peripheral._
import riscv.Parameters
import riscv.core.CPU

/**
  * 云 FPGA 平台（cg_fpga）的 lab2 顶层。
  *
  * 本模块是 RawModule，显式声明 clock / reset_n，并直接使用平台极性：
  *   - btn / sw1 / sw2：低有效
  *   - gpio_led / ledm_data / audio：低有效
  *   - seg_data：低有效（C 程序负责段码取反）
  *
  * 时钟：
  *   - clock = cg_fpga.clk_100M 经 clk_wiz 分频 = 40 MHz
  *   - Memory / UART / SegDisplay / LedMatrixDisplay / ROMLoader：40 MHz
  *   - CPU：40 MHz / 4 = 10 MHz
  *
  * 这样 CPU 比 Memory 慢 4 倍，满足同步读 BRAM 的时序要求。
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
  // Optional observation ports exist only in the simulation harness.
  val debug = if (simulationDebug) Some(IO(Output(new CloudDebug))) else None
  val clock   = IO(Input(Clock()))
  val reset_n = IO(Input(Bool()))   // cg_fpga.btn_rst, active low

  // 平台输入：低有效
  val btn = IO(Input(UInt(8.W)))
  val sw1 = IO(Input(UInt(32.W)))
  val sw2 = IO(Input(UInt(32.W)))

  // UART：rx 接 cg_fpga.tx_0，tx 接 cg_fpga.rx_0
  val uart_rx = IO(Input(Bool()))
  val uart_tx = IO(Output(Bool()))

  // 平台输出：低有效（seg_data 除外，C 程序负责取反）
  val gpio_led  = IO(Output(UInt(32.W)))
  val seg_cs    = IO(Output(UInt(4.W)))
  val seg_data  = IO(Output(UInt(8.W)))
  val ledm_cs   = IO(Output(UInt(6.W)))
  val ledm_data = IO(Output(UInt(16.W)))
  val audio     = IO(Output(UInt(7.W)))

  // 视频接口预留
  val vid_active = IO(Output(Bool()))
  val vid_data   = IO(Output(UInt(24.W)))
  val vid_hblank = IO(Output(Bool()))
  val vid_hsync  = IO(Output(Bool()))
  val vid_vblank = IO(Output(Bool()))
  val vid_vsync  = IO(Output(Bool()))

  // 复位同步：按钮输入低有效，同步释放。
  val rawReset = (!reset_n).asAsyncReset

  val rstSync = withClockAndReset(clock, rawReset) {
    val sync = RegInit(0.U(2.W))
    sync := Cat(sync(0), reset_n)
    sync
  }
  // Only the synchronizers assert asynchronously. Functional state uses a
  // synchronous reset, so BRAM addresses/write controls cannot change asynchronously.
  val reset = !rstSync(1)

  val clock_freq = 40_000_000

  // CPU 必须比 Memory 慢：Memory 是同步读 BRAM，读数据有一拍延迟。
  // 这里 40 MHz / 4 = 10 MHz；最低要求是 2 分频。
  val CPU_CLOCK_DIV = 4
  require(CPU_CLOCK_DIV >= 2, "CPU clock must be at least 2x slower than memory clock")

  withClockAndReset(clock, reset) {
    // 平台低有效输入 -> 正逻辑（当前 demo 未使用，保留给后续 GPIO）
    val btn_i = ~btn
    val sw1_i = ~sw1
    val sw2_i = ~sw2

    val mem  = Module(new Memory(Parameters.MemorySizeInWords))
    val uart = Module(new Uart(frequency = clock_freq, baudRate = 9600))
    val seg  = Module(new SegDisplay(frequency = clock_freq, refreshUs = 1000))
    val ledm = Module(new LedMatrixDisplay(frequency = clock_freq, refreshUs = 200))
    val mtime = Module(new MTime(clock_freq))

    val instruction_rom = Module(new InstructionROM(binaryFilename))
    val rom_loader      = Module(new ROMLoader(instruction_rom.capacity))

    rom_loader.io.rom_data := instruction_rom.io.data
    rom_loader.io.load_address := Parameters.EntryAddress
    instruction_rom.io.address := rom_loader.io.rom_address

    val cpuDivBits = log2Up(CPU_CLOCK_DIV)
    val CPU_clkdiv = RegInit(0.U(cpuDivBits.W))
    val CPU_tick   = Wire(Bool())
    val CPU_next   = Wire(UInt(cpuDivBits.W))
    CPU_next   := Mux(CPU_clkdiv === (CPU_CLOCK_DIV - 1).U, 0.U, CPU_clkdiv + 1.U)
    CPU_tick   := CPU_clkdiv === 1.U
    CPU_clkdiv := CPU_next

    // 仿真期断言：两个 CPU 脉冲之间至少间隔 2 个 Memory 周期。
    val ratioCheck = Module(new ClockRatioCheck(minRatio = 2))
    ratioCheck.io.cpuTick := CPU_tick

    // A registered divider bit avoids decoding a counter into a clock.
    // CPU_tick marks the master edge immediately preceding the CPU rising edge.
    val cpuClock = CPU_clkdiv(1).asClock
    val cpuResetSync = withClockAndReset(cpuClock, reset.asAsyncReset) {
      val sync = RegInit(0.U(2.W))
      sync := Cat(sync(0), true.B)
      sync
    }
    val cpuReset = !cpuResetSync(1)
    // Retiming the boot-ready flag also prevents the asynchronous synchronizer
    // from directly selecting BRAM addresses/write controls during reset.
    val cpuReady = RegNext(cpuResetSync(1), false.B)
    val cpu = withClockAndReset(cpuClock, cpuReset) { Module(new CPU) }
    val timer = withClockAndReset(cpuClock, cpuReset) { Module(new Timer) }

    cpu.io.regs_debug_read_address := 0.U
    cpu.io.csr_regs_debug_read_address := 0.U
    cpu.io.instruction_valid := rom_loader.io.load_finished && cpuReady
    mem.io.instruction_address := cpu.io.instruction_address
    cpu.io.instruction := mem.io.instruction
    mem.io.debug_read_address := 0.U

    val loading = !rom_loader.io.load_finished || !cpuReady
    cpu.io.interrupt_flag := Mux(loading, 0.U, Cat(uart.io.signal_interrupt, timer.io.signal_interrupt))
    timer.io.bundle.address := cpu.io.memory_bundle.address
    timer.io.bundle.write_data := cpu.io.memory_bundle.write_data
    timer.io.bundle.write_strobe := cpu.io.memory_bundle.write_strobe
    // Timer shares the CPU clock; no fast-domain write pulse here.
    timer.io.bundle.write_enable := !loading && cpu.io.deviceSelect === 4.U && cpu.io.memory_bundle.write_enable
    mtime.io.address := cpu.io.memory_bundle.address

    // 启动时 ROMLoader 写 RAM；启动完成后 CPU 访问 RAM/外设。
    when(loading) {
      mem.io.bundle.address      := rom_loader.io.bundle.address
      mem.io.bundle.write_data   := rom_loader.io.bundle.write_data
      mem.io.bundle.write_enable := rom_loader.io.bundle.write_enable
      mem.io.bundle.write_strobe := rom_loader.io.bundle.write_strobe
    }.otherwise {
      mem.io.bundle.address      := cpu.io.memory_bundle.address
      mem.io.bundle.write_data   := cpu.io.memory_bundle.write_data
      // The CPU clock is the memory clock divided by 4, so its write_enable
      // stays high for four memory cycles.  Qualify it with CPU_tick so a
      // single store becomes a single-cycle write pulse.
      mem.io.bundle.write_enable :=
        cpu.io.memory_bundle.write_enable && cpu.io.deviceSelect === 0.U && CPU_tick
      mem.io.bundle.write_strobe := cpu.io.memory_bundle.write_strobe
    }
    rom_loader.io.bundle.read_data := 0.U

    // MMIO：deviceSelect 2 = UART，3 = 数码管，5 = LED 矩阵
    uart.io.bundle.address      := Mux(cpu.io.deviceSelect === 2.U, cpu.io.memory_bundle.address, 0.U)
    uart.io.receive_read := !loading && CPU_tick && cpu.io.deviceSelect === 2.U &&
      mem.io.instruction(6, 0) === "b0000011".U
    uart.io.bundle.write_data   := cpu.io.memory_bundle.write_data
    uart.io.bundle.write_enable :=
      !loading && cpu.io.memory_bundle.write_enable && cpu.io.deviceSelect === 2.U && CPU_tick
    uart.io.bundle.write_strobe := cpu.io.memory_bundle.write_strobe
    uart.io.rxd := uart_rx.asUInt
    uart_tx := uart.io.txd.asBool

    seg.io.bundle.address      := cpu.io.memory_bundle.address
    seg.io.bundle.write_data   := cpu.io.memory_bundle.write_data
    seg.io.bundle.write_enable :=
      !loading && cpu.io.memory_bundle.write_enable && cpu.io.deviceSelect === 3.U && CPU_tick
    seg.io.bundle.write_strobe := cpu.io.memory_bundle.write_strobe

    ledm.io.bundle.address      := cpu.io.memory_bundle.address
    ledm.io.bundle.write_data   := cpu.io.memory_bundle.write_data
    ledm.io.bundle.write_enable :=
      !loading && cpu.io.memory_bundle.write_enable && cpu.io.deviceSelect === 5.U && CPU_tick
    ledm.io.bundle.write_strobe := cpu.io.memory_bundle.write_strobe

    val mmio_read = MuxLookup(cpu.io.deviceSelect, 0.U(32.W))(
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
      when(uart.io.bundle.write_enable && uart.io.bundle.address === 16.U) { txWrites := txWrites + 1.U }
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

    // 32 位 LED 显示当前 PC；平台低有效，所以取反。
    gpio_led := ~cpu.io.instruction_address

    // 其余外设先关闭（低有效平台，全 1 为关闭）。
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
