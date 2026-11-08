package board.cloud_pynq

import bus.BusSwitch
import chisel3._
import chisel3.util._
import chisel3.stage.{ChiselGeneratorAnnotation, ChiselStage}
import peripheral._
import riscv.Parameters
import riscv.core.CPU

/** cg_fpga cloud platform. CPU, AXI, memory and peripherals share the
  * platform's 40 MHz clock. AXI handshakes account for memory latency.
  */
class Top(binaryFilename: String = "led_control_demo.asmbin") extends RawModule {
  val clock = IO(Input(Clock()))
  val reset_n = IO(Input(Bool()))
  val btn = IO(Input(UInt(8.W)))
  val sw1 = IO(Input(UInt(32.W)))
  val sw2 = IO(Input(UInt(32.W)))
  val uart_rx = IO(Input(Bool()))
  val uart_tx = IO(Output(Bool()))
  val gpio_led = IO(Output(UInt(32.W)))
  val seg_cs = IO(Output(UInt(4.W)))
  val seg_data = IO(Output(UInt(8.W)))
  val ledm_cs = IO(Output(UInt(6.W)))
  val ledm_data = IO(Output(UInt(16.W)))
  val audio = IO(Output(UInt(7.W)))
  val vid_active = IO(Output(Bool()))
  val vid_data = IO(Output(UInt(24.W)))
  val vid_hblank = IO(Output(Bool()))
  val vid_hsync = IO(Output(Bool()))
  val vid_vblank = IO(Output(Bool()))
  val vid_vsync = IO(Output(Bool()))

  val rstSync = withClockAndReset(clock, (!reset_n).asAsyncReset) {
    val sync = RegInit(0.U(2.W))
    sync := Cat(sync(0), reset_n)
    sync
  }
  val reset = !rstSync(1)
  // Only a clocked reset may drive BRAM enables, addresses and output reset.
  // Keep the asynchronous assertion in the release synchronizer itself.
  val systemReset = withClockAndReset(clock, false.B) {
    RegNext(reset, true.B)
  }

  withClockAndReset(clock, systemReset) {
    val frequency = 40_000_000
    val mem = Module(new Memory(Parameters.MemorySizeInWords))
    mem.io.debug_read_address := 0.U
    val rom = Module(new InstructionROM(binaryFilename))
    val loader = Module(new ROMLoader(rom.capacity))
    val started = RegNext(true.B, false.B)
    val bootDone = RegInit(false.B)
    loader.io.load_start := !started
    loader.io.load_address := Parameters.EntryAddress
    loader.io.rom_data := rom.io.data
    rom.io.address := loader.io.rom_address
    when(started && loader.io.load_finished) { bootDone := true.B }

    // Keep the CPU reset until the final AXI write response has completed.
    val cpu = withReset(systemReset || !bootDone) { Module(new CPU) }
    cpu.io.debug_read_address := 0.U
    val busSwitch = Module(new BusSwitch)
    busSwitch.io.master <> cpu.io.axi4_channels
    busSwitch.io.address := cpu.io.bus_address
    // Separate dummy slaves avoid tying unrelated response channels together.
    for (i <- 0 until Parameters.SlaveDeviceCount) {
      busSwitch.io.slaves(i) <> Module(new DummySlave).io.channels
    }
    val unusedLoader = Module(new DummySlave)
    loader.io.channels <> unusedLoader.io.channels
    busSwitch.io.slaves(0) <> mem.io.channels
    when(!bootDone) { loader.io.channels <> mem.io.channels }

    val uart = Module(new Uart(frequency, 9600))
    val timer = Module(new Timer)
    busSwitch.io.slaves(2) <> uart.io.channels
    busSwitch.io.slaves(4) <> timer.io.channels
    cpu.io.interrupt_flag := Cat(uart.io.signal_interrupt, timer.io.signal_interrupt)
    uart.io.rxd := uart_rx.asUInt
    // Keep the pin idle until synchronous reset has reached the UART.
    uart_tx := Mux(reset, true.B, uart.io.txd.asBool)

    val seg = Module(new SegDisplay(frequency))
    val ledm = Module(new LedMatrixDisplay(frequency))
    val segAXI = Module(new AXIMMIO)
    val ledmAXI = Module(new AXIMMIO)
    busSwitch.io.slaves(3) <> segAXI.io.channels
    busSwitch.io.slaves(5) <> ledmAXI.io.channels
    seg.io.bundle <> segAXI.io.registers
    ledm.io.bundle <> ledmAXI.io.registers
    seg_cs := seg.io.seg_cs
    seg_data := seg.io.seg_data
    ledm_cs := ledm.io.ledm_cs
    ledm_data := ledm.io.ledm_data

    val mtime = Module(new MTime(frequency))
    val mtimeAXI = Module(new AXIMMIO)
    busSwitch.io.slaves(6) <> mtimeAXI.io.channels
    mtime.io.address := mtimeAXI.io.registers.address
    mtimeAXI.io.registers.read_data := mtime.io.read_data
    gpio_led := ~cpu.io.bus_address
    audio := "h7f".U
    vid_active := false.B
    vid_data := 0.U
    vid_hblank := false.B
    vid_hsync := false.B
    vid_vblank := false.B
    vid_vsync := false.B
  }
}

object VerilogGenerator extends App {
  val program = args.headOption.getOrElse("led_control_demo")
  val binary = if (program.endsWith(".asmbin")) program else s"$program.asmbin"
  val target = args.lift(1).getOrElse("verilog/cloud_pynq")
  (new ChiselStage).execute(Array("-X", "verilog", "-td", target),
    Seq(ChiselGeneratorAnnotation(() => new Top(binary))))
}
