package riscv

import chisel3.stage.{ChiselGeneratorAnnotation, ChiselStage}

// Generate an isolated production design for native UART integration testing.
// This does not overwrite the RTL used by the LED-demo Vivado project.
object CloudGoodbyeVerilog extends App {
  (new ChiselStage).execute(
    Array("-X", "verilog", "-td", "target/goodbye-verilog"),
    Seq(ChiselGeneratorAnnotation(() => new board.cloud_pynq.Top("say_goodbye.asmbin")))
  )
}
