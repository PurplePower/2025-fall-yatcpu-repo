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
  * 8 位数码管扫描控制器。
  *
  * MMIO 寄存器（32 位字访问，地址 = 基地址 + digit * 4）：
  *   - bit[7:0]  段码。共阴极，高电平点亮；例如 0x3F 显示 "0"。
  *   - bit[8]    显示使能。1 = 显示 bit[7:0] 的段码，0 = 熄灭该位。
  *   - bit[31:9] 保留。
  *
  * 读回同一地址返回 {enable, pattern}，方便调试。
  *
  * 硬件接口：
  *   - seg_cs[3:0]  ：4 位二进制位选，0~7 对应 8 位数码管。
  *   - seg_data[7:0]：当前位选数码管的段码，共阴极高有效。
  *
  * C 程序只需要一次 32 位写即可更新某一位；扫描和刷新由本模块完成。
  *
  * @param frequency 本模块时钟频率（Hz）
  * @param refreshUs 每一位的点亮时间（微秒）
  */
class SegDisplay(frequency: Int = 25_000_000, refreshUs: Int = 1000) extends Module {
  val io = IO(new Bundle {
    val bundle   = new RAMBundle
    val seg_cs   = Output(UInt(4.W))
    val seg_data = Output(UInt(8.W))
  })

  require(frequency > 0, "frequency must be positive")
  require(refreshUs > 0, "refreshUs must be positive")

  private val divMax = frequency / 1_000_000 * refreshUs - 1
  require(divMax > 0, "refresh period must be at least one clock cycle")

  // 8 位的段码和显示使能
  val digits  = RegInit(VecInit(Seq.fill(8)(0.U(8.W))))
  val enables = RegInit(VecInit(Seq.fill(8)(false.B)))

  // 地址 bit[4:2] 选择 digit 0~7
  val idx = io.bundle.address(4, 2)

  when(io.bundle.write_enable && idx < 8.U) {
    digits(idx)  := io.bundle.write_data(7, 0)
    enables(idx) := io.bundle.write_data(8)
  }

  // 读回 {enable, pattern}，高位补 0
  io.bundle.read_data := MuxLookup(idx, 0.U(32.W))(
    (0 until 8).map { i =>
      i.U(3.W) -> Cat(0.U(23.W), enables(i), digits(i))
    }
  )

  // 扫描计数器：每 refreshUs 切换一位
  val div  = RegInit(0.U(log2Up(divMax + 1).W))
  val scan = RegInit(0.U(3.W))

  when(div === divMax.U) {
    div  := 0.U
    scan := scan + 1.U
  }.otherwise {
    div := div + 1.U
  }

  io.seg_cs   := scan
  io.seg_data := Mux(enables(scan), digits(scan), 0.U)
}
