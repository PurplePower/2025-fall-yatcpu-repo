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
import riscv.Parameters

class Control extends Module {
  val io = IO(new Bundle {
    val jump_flag = Input(Bool())   // NOTE: this signal now comes from ID stage
    val jump_instruction_id = Input(Bool()) // NOTE: whether instruction at ID is of jump/branch
    val rs1_id = Input(UInt(Parameters.PhysicalRegisterAddrWidth))
    val rs2_id = Input(UInt(Parameters.PhysicalRegisterAddrWidth))
    val memory_read_enable_ex = Input(Bool())
    val rd_ex = Input(UInt(Parameters.PhysicalRegisterAddrWidth))
    val memory_read_enable_mem = Input(Bool())
    val rd_mem = Input(UInt(Parameters.PhysicalRegisterAddrWidth))

    val stall_flag_if = Input(Bool()) // fetch inst. from mem stall flag
    val stall_flag_mem = Input(Bool()) // mem access stall flag

    val redirect_stall = Output(Bool())
    val if2id_flush = Output(Bool())
    val id2ex_flush = Output(Bool())
    val pc_stall = Output(Bool())
    val if2id_stall = Output(Bool())
    val id2ex_stall = Output(Bool())
    val ex2mem_stall = Output(Bool())
  })

  /*NOTE:
    Since branch taken computation is moved to ID, now ID and EX both may cause data race.

    If current instruction is a jump/branch type, ready to run ID stage and depends on data from:
      a) previous instruction, that is ready to run EX stage and
        1. gives result after EX stage, current instruction should be stalled for 1 cycle
        2. gives result after MEM stage, current instruction should be stalled for 2 cycles
      b) 2nd previous instruction, that is ready to run MEM stage and
        1. already gives result in EX stage, forward its result
        2. gives result after MEM stage, current instruction should be stalled for 1 cycle

    If current instruction is ready to run EX stage, and depends on data from:
      a) previous instruction, that is ready to run MEM stage and 
        1. already gives result in EX stage, forward it
        2. gives result after MEM stage, current instruction should be stalled for 1 cycle
      b) 2nd previous instruction, that is ready to run WB stage, no hazard happens.
  
  */

  /* 
    cases for memory access stall:
      1. if IF waits for memory, stall IF stage only
      2. if MEM waits for memory, stall all stages before MEM
   */

  // Redirects may invalidate an outstanding fetch, but must wait for older
  // data accesses and for operands needed by the instruction in ID.
  val branchHazard = io.jump_instruction_id && (
    (io.rs1_id =/= 0.U && (io.rs1_id === io.rd_ex || (io.rs1_id === io.rd_mem && io.memory_read_enable_mem))) ||
    (io.rs2_id =/= 0.U && (io.rs2_id === io.rd_ex || (io.rs2_id === io.rd_mem && io.memory_read_enable_mem))))
  val loadHazard = !io.jump_instruction_id && io.memory_read_enable_ex && (
    (io.rs1_id =/= 0.U && io.rs1_id === io.rd_ex) ||
    (io.rs2_id =/= 0.U && io.rs2_id === io.rd_ex))
  io.redirect_stall := io.stall_flag_mem || branchHazard || loadHazard

  when (io.jump_instruction_id) {
    // current instruction is of jump/branch type, need to check data dependencies
    val rs1_needs_stall = io.rs1_id =/= 0.U && (
      (io.rs1_id === io.rd_ex)  || // NOTE: no need to check memory_read_enable_ex, since both cases needs stall
      (io.rs1_id === io.rd_mem && io.memory_read_enable_mem)
    )
    val rs2_needs_stall = io.rs2_id =/= 0.U && (io.rs2_id === io.rd_ex || (io.rs2_id === io.rd_mem && io.memory_read_enable_mem))

    when (rs1_needs_stall || rs2_needs_stall) { // inst. at ID stage needs to stall
      // NOTE: in this case jump_flag is INVALID, since reg data isn't latest 
      // stall IF and ID stage
      io.if2id_flush := false.B  // keep ID stage
      io.id2ex_flush := true.B   // clear EX stage and after stages
      io.pc_stall := true.B
      io.if2id_stall := true.B
    } .otherwise {
      // NOTE: jump_flag is VALID
      when (io.jump_flag) { // ID says it's time to jump, clears IF stage only
        io.if2id_flush := !io.redirect_stall // clears IF2ID
        io.id2ex_flush := false.B  // don't flush, let inst at ID goes on
        io.pc_stall := io.redirect_stall
        io.if2id_stall := io.stall_flag_mem
      }
      .otherwise {
        io.if2id_flush := false.B
        io.id2ex_flush := false.B
        io.pc_stall := io.stall_flag_if || io.stall_flag_mem 
        io.if2id_stall := io.stall_flag_mem
      }
    }
  }
  .otherwise {
    // current instruction is not of jump/branch type, jump_flag is always FALSE
    // NOTE: notice that we check memory_read_enable_ex here since inst. at EX stage now may be a load instruction
    val rs1_needs_stall = io.rs1_id =/= 0.U && io.rs1_id === io.rd_ex && io.memory_read_enable_ex
    val rs2_needs_stall = io.rs2_id =/= 0.U && io.rs2_id === io.rd_ex && io.memory_read_enable_ex
    when (rs1_needs_stall || rs2_needs_stall) { // inst. at ID stage needs to stall
      // NOTE: compared to without jump judge moved to ID, current inst. is stalled at ID stage instead at EX stage
      io.if2id_flush := false.B  // keep ID stage
      io.id2ex_flush := true.B   // clear EX stage and after stages
      io.pc_stall := true.B
      io.if2id_stall := true.B
    } .otherwise {
      io.if2id_flush := io.jump_flag && !io.redirect_stall
      io.id2ex_flush := false.B
      io.pc_stall := (io.stall_flag_if || io.stall_flag_mem) && !io.jump_flag
      io.if2id_stall := io.stall_flag_mem
    }
  }

  // any moment that MEM stalls, EX and ID shall be stalled too. 
  // This is not related to jump/branch(clears only IF) or data hazard(stalls IF and ID)
  io.id2ex_stall := io.stall_flag_mem
  io.ex2mem_stall := io.stall_flag_mem 

}
