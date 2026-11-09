package peripheral

import chisel3._
import riscv.Parameters

class RAMBundle extends Bundle {
  val address = Input(UInt(Parameters.AddrWidth))
  val write_data = Input(UInt(Parameters.DataWidth))
  val write_enable = Input(Bool())
  val write_strobe = Input(Vec(Parameters.WordSize, Bool()))
  val read_data = Output(UInt(Parameters.DataWidth))
}
