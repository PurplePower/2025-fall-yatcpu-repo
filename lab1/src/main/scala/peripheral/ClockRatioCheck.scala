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

/**
  * 检查 CPU 时钟是否至少比 Memory 时钟慢 `minRatio` 倍。
  *
  * `cpuTick` 必须在每个 CPU 周期中恰好有一个 Memory 时钟周期为高。
  * 模块本身运行在 Memory 时钟域，统计两个 `cpuTick` 脉冲之间的
  * Memory 周期数；在仿真中如果比例不足会触发 Chisel assert。
  *
  * 这是仿真/形式验证用的断言；综合时不会改变 CPU/Memory 的功能。
  */
class ClockRatioCheck(minRatio: Int = 2) extends Module {
  require(minRatio >= 1, "minRatio must be at least 1")

  val io = IO(new Bundle {
    val cpuTick = Input(Bool())
  })

  val started = RegInit(false.B)
  val count   = RegInit(0.U(16.W))

  when(io.cpuTick) {
    when(started) {
      assert(
        count + 1.U >= minRatio.U,
        s"CPU clock must be at least ${minRatio}x slower than memory clock"
      )
    }
    started := true.B
    count   := 0.U
  }.otherwise {
    count := count + 1.U
  }
}
