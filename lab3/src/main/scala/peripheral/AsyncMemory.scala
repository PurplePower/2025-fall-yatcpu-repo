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

package peripheral

import chisel3._
import riscv.Parameters

/**
  * Data memory with combinational (asynchronous) reads.
  *
  * The normal Memory uses SyncReadMem and therefore requires the CPU clock
  * to be slower than the memory clock.  cloud_pynq keeps this asynchronous
  * data RAM together with the asynchronous InstructionROM, including when
  * the CPU runs from the /4 divided clock.
  */
class AsyncMemory(capacity: Int) extends Module {
  val io = IO(new Bundle {
    val bundle = new RAMBundle
    val debug_read_address = Input(UInt(Parameters.AddrWidth))
    val debug_read_data = Output(UInt(Parameters.DataWidth))
  })

  val mem = Mem(capacity, Vec(Parameters.WordSize, UInt(Parameters.ByteWidth)))

  when(io.bundle.write_enable) {
    val write_data_vec = Wire(Vec(Parameters.WordSize, UInt(Parameters.ByteWidth)))
    for (i <- 0 until Parameters.WordSize) {
      write_data_vec(i) := io.bundle.write_data((i + 1) * Parameters.ByteBits - 1, i * Parameters.ByteBits)
    }
    mem.write((io.bundle.address >> 2).asUInt, write_data_vec, io.bundle.write_strobe)
  }

  io.bundle.read_data := mem.read((io.bundle.address >> 2).asUInt).asUInt
  io.debug_read_data := mem.read((io.debug_read_address >> 2).asUInt).asUInt
}
