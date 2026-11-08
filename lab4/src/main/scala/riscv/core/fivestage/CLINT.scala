// Copyright 2021 Howard Lau
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

package riscv.core.fivestage_final

import chisel3._
import chisel3.util.{Cat, MuxLookup}
import riscv.Parameters
import riscv.core.CSRDirectAccessBundle

object InterruptStatus {
  val None = 0x0.U(8.W)
  val Timer0 = 0x1.U(8.W)
  val Ret = 0xFF.U(8.W)
}

// Core Local Interrupt Controller
class CLINT extends Module {
  val io = IO(new Bundle {
    val interrupt_flag = Input(UInt(Parameters.InterruptFlagWidth))

    val instruction_id = Input(UInt(Parameters.InstructionWidth))
    val instruction_address_if = Input(UInt(Parameters.AddrWidth))

    val stall_flag_ctrl = Input(Bool())
    val jump_flag = Input(Bool())
    val jump_address = Input(UInt(Parameters.AddrWidth))

    val id_interrupt_handler_address = Output(UInt(Parameters.AddrWidth))
    val id_interrupt_assert = Output(Bool())

    val csr_bundle = new CSRDirectAccessBundle
  })
  // Timer is a pulse source. Preserve its rising edge across AXI stalls;
  // a level still high after acceptance must not enqueue the same event again.
  val timerPending = RegInit(false.B)
  val timerEdge = io.interrupt_flag(0) && !RegNext(io.interrupt_flag(0), false.B)
  when(timerEdge) { timerPending := true.B }
  val interrupt_flags = Cat(io.interrupt_flag(31, 1), timerPending || timerEdge)
  val interrupt_enable = io.csr_bundle.mstatus(3)
  val instruction_address = Mux(
    io.jump_flag,
    io.jump_address,
    io.instruction_address_if,
  )
  val mstatus_disable_interrupt = io.csr_bundle.mstatus(31, 4) ## 0.U(1.W) ## io.csr_bundle.mstatus(2, 0)
  val mstatus_recover_interrupt = io.csr_bundle.mstatus(31, 4) ## io.csr_bundle.mstatus(7) ## io.csr_bundle.mstatus(2, 0)

  when(!io.stall_flag_ctrl && (io.instruction_id === InstructionsEnv.ecall || io.instruction_id === InstructionsEnv.ebreak)) {
    io.csr_bundle.mstatus_write_data := mstatus_disable_interrupt
    io.csr_bundle.mepc_write_data := instruction_address
    io.csr_bundle.mcause_write_data := MuxLookup(
      io.instruction_id,
      10.U,
      IndexedSeq(
        InstructionsEnv.ecall -> 11.U,
        InstructionsEnv.ebreak -> 3.U,
      )
    )
    io.csr_bundle.direct_write_enable := true.B
    io.id_interrupt_assert := true.B
    io.id_interrupt_handler_address := io.csr_bundle.mtvec
  }.elsewhen(!io.stall_flag_ctrl && interrupt_flags =/= InterruptStatus.None && interrupt_enable) {
    io.csr_bundle.mstatus_write_data := mstatus_disable_interrupt
    io.csr_bundle.mepc_write_data := instruction_address
    io.csr_bundle.mcause_write_data := Mux(interrupt_flags(0), 0x80000007L.U, 0x8000000BL.U)
    io.csr_bundle.direct_write_enable := true.B
    io.id_interrupt_assert := true.B
    io.id_interrupt_handler_address := io.csr_bundle.mtvec
    when(interrupt_flags(0)) { timerPending := false.B }
  }.elsewhen(!io.stall_flag_ctrl && io.instruction_id === InstructionsRet.mret) {
    io.csr_bundle.mstatus_write_data := mstatus_recover_interrupt
    io.csr_bundle.mepc_write_data := io.csr_bundle.mepc
    io.csr_bundle.mcause_write_data := io.csr_bundle.mcause
    io.csr_bundle.direct_write_enable := true.B
    io.id_interrupt_assert := true.B
    io.id_interrupt_handler_address := io.csr_bundle.mepc
  }.otherwise {
    io.csr_bundle.mstatus_write_data := io.csr_bundle.mstatus
    io.csr_bundle.mepc_write_data := io.csr_bundle.mepc
    io.csr_bundle.mcause_write_data := io.csr_bundle.mcause
    io.csr_bundle.direct_write_enable := false.B
    io.id_interrupt_assert := false.B
    io.id_interrupt_handler_address := 0.U
  }
}
