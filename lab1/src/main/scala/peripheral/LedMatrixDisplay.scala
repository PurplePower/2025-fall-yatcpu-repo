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
import chisel3.util.{Cat, log2Up}

/**
  * 云 FPGA 平台的四块 16x16 LED 矩阵扫描控制器。
  *
  * MMIO 帧缓存包含 64 个 32 位字寄存器，低 16 位有效：
  *   地址 = 基地址 + (panel * 16 + row) * 4
  * 软件写入 1 表示点亮；硬件输出前取反，以适配低有效的 ledm_data。
  *
  * ledm_cs[5:4] 从左到右选择矩阵，ledm_cs[3:0] 选择矩阵内的行。
  *
  * @param frequency 本模块时钟频率（Hz）
  * @param refreshUs 每一行的点亮时间（微秒）
  */
class LedMatrixDisplay(frequency: Int = 50_000_000, refreshUs: Int = 200) extends Module {
  val io = IO(new Bundle {
    val bundle    = new RAMBundle
    val ledm_cs   = Output(UInt(6.W))
    val ledm_data = Output(UInt(16.W))
  })

  require(frequency > 0, "frequency must be positive")
  require(refreshUs > 0, "refreshUs must be positive")

  private val divCycles = frequency.toLong * refreshUs / 1_000_000L
  require(divCycles >= 2 && divCycles <= Int.MaxValue,
    "refresh period must be between two clock cycles and Int.MaxValue clock cycles")
  private val divMax = divCycles - 1

  val framebuffer = RegInit(VecInit(Seq.fill(64)(0.U(16.W))))
  val idx = io.bundle.address(7, 2)

  when(io.bundle.write_enable) {
    framebuffer(idx) := io.bundle.write_data(15, 0)
  }
  io.bundle.read_data := Cat(0.U(16.W), framebuffer(idx))

  val div  = RegInit(0.U(log2Up(divCycles.toInt).W))
  val scan = RegInit(0.U(6.W))

  when(div === divMax.U) {
    div  := 0.U
    scan := scan + 1.U
  }.otherwise {
    div := div + 1.U
  }

  io.ledm_cs   := scan
  io.ledm_data := ~framebuffer(scan)
}
