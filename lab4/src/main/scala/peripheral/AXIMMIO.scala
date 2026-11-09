package peripheral

import bus.{AXI4LiteChannels, AXI4LiteSlave}
import chisel3._
import chisel3.util.RegEnable

/** Connect a local register device to the teaching AXI slave. Its write level
  * may span several cycles while BREADY is low, so commit each store once.
  * Read data is captured once as well, including live counters such as MTime.
  */
class AXIMMIO extends Module {
  val io = IO(new Bundle {
    val channels = Flipped(new AXI4LiteChannels(32, 32))
    val registers = Flipped(new RAMBundle)
  })
  val slave = Module(new AXI4LiteSlave(32, 32))
  slave.io.channels <> io.channels
  val readOnce = slave.io.bundle.read && !RegNext(slave.io.bundle.read, false.B)
  val writeOnce = slave.io.bundle.write && !RegNext(slave.io.bundle.write, false.B)
  io.registers.address := slave.io.bundle.address & "h1fffffff".U
  io.registers.write_data := slave.io.bundle.write_data
  io.registers.write_strobe := slave.io.bundle.write_strobe
  io.registers.write_enable := writeOnce && slave.io.bundle.write_strobe.asUInt.orR
  val data = RegEnable(io.registers.read_data, readOnce)
  slave.io.bundle.read_data := data
  slave.io.bundle.read_valid := RegNext(readOnce, false.B)
}
