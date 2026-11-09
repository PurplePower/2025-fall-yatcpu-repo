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
import chisel3.util._
import riscv.Parameters

/**
  * 1 MHz wall-clock counter exposed through MMIO.
  *
  * The module is clocked by a stable board clock.  `clockFreqHz` is the
  * frequency of that clock and is supplied by each board Top, so the same
  * RTL works on 25/40/50/100 MHz boards without changing this module.
  *
  * MMIO layout (deviceSelect 6, base 0xC000_0000):
  *   0x0 -> mtime[31:0]
  *   0x4 -> mtime[63:32]
  *
  * Software should read high/low/high for a stable 64-bit snapshot.
  */
class MTime(clockFreqHz: Int, tickHz: Int = 1000000) extends Module {
  val io = IO(new Bundle {
    val address = Input(UInt(Parameters.AddrWidth))
    val read_data = Output(UInt(Parameters.DataWidth))
  })

  require(clockFreqHz > 0, "MTime clock frequency must be positive")
  require(tickHz > 0, "MTime tick frequency must be positive")
  require(tickHz <= clockFreqHz, "MTime tick frequency cannot exceed its clock")

  private val mtime = RegInit(0.U(64.W))

  if (clockFreqHz % tickHz == 0) {
    // Exact integer divider: no jitter.
    val div = clockFreqHz / tickHz
    val cnt = RegInit(0.U(math.max(log2Ceil(div), 1).W))
    val tick = cnt === (div - 1).U
    when(tick) {
      cnt := 0.U
    }.otherwise {
      cnt := cnt + 1.U
    }
    when(tick) {
      mtime := mtime + 1.U
    }
  } else {
    // Fractional divider for a future board whose clock is not an integer
    // multiple of 1 MHz.  The average tick rate is exactly tickHz.
    val step = ((BigInt(tickHz) << 32) / clockFreqHz).U(32.W)
    val phase = RegInit(0.U(32.W))
    val sum = phase + step
    val tick = sum(32)
    phase := sum(31, 0)
    when(tick) {
      mtime := mtime + 1.U
    }
  }

  io.read_data := MuxLookup(io.address, 0.U)(Seq(
    0x0.U -> mtime(31, 0),
    0x4.U -> mtime(63, 32),
  ))
}
