package board.z710v1_3


import chisel3._
import chisel3.stage.ChiselStage
import chisel3.util._
import chisel3.{ChiselEnum, _}

// import circt.stage.ChiselStage
import chisel3.stage.ChiselGeneratorAnnotation

import bus._
import peripheral._
import riscv._
import riscv.Parameters
import riscv.core.CPU
import javax.print.SimpleDoc

object BootStates extends ChiselEnum {
  val Init, Loading, BusWait, Finished = Value
}


class Top(binaryFilename: String ="say_goodbye.asmbin") extends Module {
  val io = IO(new Bundle {
    val tx = Output(Bool())
    val rx = Input(Bool())
    val led = Output(Bool())
  })
  val boot_state = RegInit(BootStates.Init)

  val clock_freq = 50_000_000
  val uart = Module(new Uart(clock_freq, 115200))
  io.tx := uart.io.txd
  uart.io.rxd := io.rx
  
  val mem = Module(new Memory(Parameters.MemorySizeInWords))
  val timer = Module(new Timer)
  val dummy = Module(new DummySlave)
  val bus_arbiter = Module(new BusArbiter)
  val bus_switch = Module(new BusSwitch)
  val instruction_rom = Module(new InstructionROM(binaryFilename))
  val rom_loader = Module(new ROMLoader(instruction_rom.capacity))

  // bus connections
  for (i <- 0 until Parameters.SlaveDeviceCount) {
    bus_switch.io.slaves(i) <> dummy.io.channels
  }
  bus_arbiter.io.bus_request(0) := true.B
  bus_switch.io.slaves(2) <> uart.io.channels
  bus_switch.io.slaves(4) <> timer.io.channels
  
  rom_loader.io.load_address := Parameters.EntryAddress
  rom_loader.io.load_start := false.B
  rom_loader.io.rom_data := instruction_rom.io.data
  instruction_rom.io.address := rom_loader.io.rom_address

  bus_switch.io.slaves(0) <> mem.io.channels
  rom_loader.io.channels <> dummy.io.channels

  switch(boot_state) {
    is(BootStates.Init) {
      rom_loader.io.load_start := true.B
      boot_state := BootStates.Loading
      rom_loader.io.channels <> mem.io.channels
    }
    is(BootStates.Loading) {
      rom_loader.io.load_start := false.B
      rom_loader.io.channels <> mem.io.channels
      when(rom_loader.io.load_finished) {
        boot_state := BootStates.Finished
      }
    }
    is(BootStates.Finished) { }
  }

  val reset_cpu = (reset.asBool) || (boot_state =/= BootStates.Finished)
  withReset (reset_cpu) {  // this ensures CPU is up only after program is loaded
    val cpu = Module(new CPU)
    bus_switch.io.master <> cpu.io.axi4_channels
    bus_switch.io.address := cpu.io.bus_address
    cpu.io.interrupt_flag := Cat(uart.io.signal_interrupt, timer.io.signal_interrupt)
    cpu.io.debug_read_address := 0.U

    // UART print
    val device = cpu.io.bus_address(Parameters.AddrBits - 1, Parameters.AddrBits - Parameters.SlaveDeviceCountBits)
    when (cpu.io.debug_bus_write_enable && device === 2.U) {
      printf("%c", cpu.io.debug_bus_write_data.asUInt)
    }
  }
  
  // debug signals 
  mem.io.debug_read_address := 0.U

  val led_count = RegInit(0.U(32.W))
  when (led_count >= clock_freq.U) { // the led blinks every second, clock freq is 100M
    led_count := 0.U
  }.otherwise {
    led_count := led_count + 1.U
  }
  io.led := (led_count >= (clock_freq.U >> 1))
}



object VerilogGenerator extends App {
  require(args.length <= 2, "Usage: VerilogGenerator [program[.asmbin]] [output-directory]")
  val program = args.headOption.getOrElse("say_goodbye")
  require(program.nonEmpty, "Program name must not be empty")
  val binary = if (program.endsWith(".asmbin")) program else s"$program.asmbin"
  val target = args.lift(1).getOrElse("verilog/z710v1.3")
  require(target.nonEmpty, "Output directory must not be empty")
  (new ChiselStage).execute(
    Array("-X", "verilog", "-td", target),
    Seq(ChiselGeneratorAnnotation(() => new Top(binary)))
  )
}
