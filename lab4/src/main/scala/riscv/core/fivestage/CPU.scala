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
import riscv.core.{CPUBundle, CSR, RegisterFile}
import bus.AXI4LiteMaster
import bus.AXI4Lite

class CPU extends Module {
  val io = IO(new CPUBundle)

  val ctrl = Module(new Control)
  val regs = Module(new RegisterFile)
  val inst_fetch = Module(new InstructionFetch)
  val if2id = Module(new IF2ID)
  val id = Module(new InstructionDecode)
  val id2ex = Module(new ID2EX)
  val ex = Module(new Execute)
  val ex2mem = Module(new EX2MEM)
  val mem = Module(new MemoryAccess)
  val mem2wb = Module(new MEM2WB)
  val wb = Module(new WriteBack)
  val forwarding = Module(new Forwarding)
  val clint = Module(new CLINT)
  val csr_regs = Module(new CSR)

  val axi4_master = Module(new AXI4LiteMaster(Parameters.AddrBits, Parameters.DataBits))
  axi4_master.io.channels <> io.axi4_channels

  io.debug_bus_write_enable := false.B
  io.debug_bus_write_data := 0.U
  
  // >>>>>>>>>>>>>>>>>>>> CPU Internal Bus Arbiter >>>>>>>>>>>>>>>>>>>>>
  // TODO: review this arbiter design

  // fulfill requests from MEM first
  object BusOwner extends ChiselEnum {
    val MEM, IF = Value
  }
  val bus_owner = RegInit(BusOwner.IF)  // init granted to IF to fetch instructions after reset

  // immediate signal indicating bus owner transfer: mem needs bus && bus is free
  val transfer_bus_to_mem = mem.io.bus.request && !axi4_master.io.bundle.busy && !axi4_master.io.bundle.read_valid
  when (transfer_bus_to_mem) {
    bus_owner := BusOwner.MEM  // NOTE: this mechanism relies on Control stall IF when MEM needs bus
  }
  
  mem.io.bus.read_data := axi4_master.io.bundle.read_data
  mem.io.bus.read_valid := axi4_master.io.bundle.read_valid
  mem.io.bus.write_valid := axi4_master.io.bundle.write_valid
  mem.io.bus.busy := axi4_master.io.bundle.busy
  mem.io.bus.granted := bus_owner === BusOwner.MEM || transfer_bus_to_mem

  inst_fetch.io.bus.read_data := axi4_master.io.bundle.read_data
  inst_fetch.io.bus.read_valid := axi4_master.io.bundle.read_valid
  inst_fetch.io.bus.write_valid := axi4_master.io.bundle.write_valid
  inst_fetch.io.bus.busy := axi4_master.io.bundle.busy
  inst_fetch.io.bus.granted := bus_owner === BusOwner.IF && !transfer_bus_to_mem


  when (bus_owner === BusOwner.MEM || transfer_bus_to_mem) {
    // connect MEM to bus
    io.bus_address := mem.io.bus.address
    axi4_master.io.bundle.address := mem.io.bus.address
    axi4_master.io.bundle.write := mem.io.bus.write
    axi4_master.io.bundle.write_data := mem.io.bus.write_data
    axi4_master.io.bundle.write_strobe := mem.io.bus.write_strobe
    axi4_master.io.bundle.read := mem.io.bus.read
    
    when (!mem.io.bus.request) {
      bus_owner := BusOwner.IF  // if MEM does not need bus, give it to IF
    }

    io.debug_bus_write_enable := mem.io.bus.write
    io.debug_bus_write_data := mem.io.bus.write_data
  }.otherwise {
    // connect IF to bus
    io.bus_address := inst_fetch.io.bus.address
    axi4_master.io.bundle.address := inst_fetch.io.bus.address
    axi4_master.io.bundle.write := inst_fetch.io.bus.write
    axi4_master.io.bundle.write_data := inst_fetch.io.bus.write_data
    axi4_master.io.bundle.write_strobe := inst_fetch.io.bus.write_strobe
    axi4_master.io.bundle.read := inst_fetch.io.bus.read
  }

  // <<<<<<<<<<<<<<<<< CPU Internal Bus Arbiter <<<<<<<<<<<<<<<<<<

  ctrl.io.jump_flag := id.io.if_jump_flag
  ctrl.io.jump_instruction_id := id.io.ctrl_jump_instruction
  ctrl.io.rs1_id := id.io.regs_reg1_read_address
  ctrl.io.rs2_id := id.io.regs_reg2_read_address
  ctrl.io.memory_read_enable_ex := id2ex.io.output_memory_read_enable
  ctrl.io.rd_ex := id2ex.io.output_regs_write_address
  ctrl.io.memory_read_enable_mem := ex2mem.io.output_memory_read_enable
  ctrl.io.rd_mem := ex2mem.io.output_regs_write_address
  ctrl.io.stall_flag_if := inst_fetch.io.out_stall_flag
  ctrl.io.stall_flag_mem := mem.io.ctrl_stall_flag

  regs.io.write_enable := mem2wb.io.output_regs_write_enable
  regs.io.write_address := mem2wb.io.output_regs_write_address
  regs.io.write_data := wb.io.regs_write_data
  regs.io.read_address1 := id.io.regs_reg1_read_address
  regs.io.read_address2 := id.io.regs_reg2_read_address

  regs.io.debug_read_address := io.debug_read_address
  io.debug_read_data := regs.io.debug_read_data


  inst_fetch.io.in_stall_flag := ctrl.io.pc_stall
  inst_fetch.io.jump_flag_id := id.io.if_jump_flag && !ctrl.io.redirect_stall
  inst_fetch.io.jump_address_id := id.io.if_jump_address

  if2id.io.stall := ctrl.io.if2id_stall
  if2id.io.flush := ctrl.io.if2id_flush
  if2id.io.instruction := inst_fetch.io.id_instruction
  if2id.io.instruction_address := inst_fetch.io.instruction_address
  if2id.io.interrupt_flag := io.interrupt_flag

  id.io.instruction := if2id.io.output_instruction
  id.io.instruction_address := if2id.io.output_instruction_address
  id.io.reg1_data := regs.io.read_data1
  id.io.reg2_data := regs.io.read_data2
  id.io.forward_from_mem := mem.io.forward_to_ex
  id.io.forward_from_wb := wb.io.regs_write_data
  id.io.reg1_forward := forwarding.io.reg1_forward_id
  id.io.reg2_forward := forwarding.io.reg2_forward_id
  id.io.interrupt_assert := clint.io.id_interrupt_assert
  id.io.interrupt_handler_address := clint.io.id_interrupt_handler_address

  id2ex.io.flush := ctrl.io.id2ex_flush
  id2ex.io.stall := ctrl.io.id2ex_stall
  id2ex.io.instruction := if2id.io.output_instruction
  id2ex.io.instruction_address := if2id.io.output_instruction_address
  id2ex.io.reg1_data := regs.io.read_data1
  id2ex.io.reg2_data := regs.io.read_data2
  id2ex.io.regs_reg1_read_address := id.io.regs_reg1_read_address
  id2ex.io.regs_reg2_read_address := id.io.regs_reg2_read_address
  id2ex.io.regs_write_enable := id.io.ex_reg_write_enable
  id2ex.io.regs_write_address := id.io.ex_reg_write_address
  id2ex.io.regs_write_source := id.io.ex_reg_write_source
  id2ex.io.immediate := id.io.ex_immediate
  id2ex.io.aluop1_source := id.io.ex_aluop1_source
  id2ex.io.aluop2_source := id.io.ex_aluop2_source
  id2ex.io.csr_write_enable := id.io.ex_csr_write_enable
  id2ex.io.csr_address := id.io.ex_csr_address
  id2ex.io.memory_read_enable := id.io.ex_memory_read_enable
  id2ex.io.memory_write_enable := id.io.ex_memory_write_enable
  id2ex.io.csr_read_data := csr_regs.io.id_reg_read_data

  ex.io.instruction := id2ex.io.output_instruction
  ex.io.instruction_address := id2ex.io.output_instruction_address
  ex.io.reg1_data := id2ex.io.output_reg1_data
  ex.io.reg2_data := id2ex.io.output_reg2_data
  ex.io.immediate := id2ex.io.output_immediate
  ex.io.aluop1_source := id2ex.io.output_aluop1_source
  ex.io.aluop2_source := id2ex.io.output_aluop2_source
  ex.io.csr_read_data := id2ex.io.output_csr_read_data
  ex.io.forward_from_mem := mem.io.forward_to_ex
  ex.io.forward_from_wb := wb.io.regs_write_data
  ex.io.reg1_forward := forwarding.io.reg1_forward_ex
  ex.io.reg2_forward := forwarding.io.reg2_forward_ex


  ex2mem.io.stall := ctrl.io.ex2mem_stall
  ex2mem.io.flush := false.B
  ex2mem.io.regs_write_enable := id2ex.io.output_regs_write_enable
  ex2mem.io.regs_write_source := id2ex.io.output_regs_write_source
  ex2mem.io.regs_write_address := id2ex.io.output_regs_write_address
  ex2mem.io.instruction_address := id2ex.io.output_instruction_address
  ex2mem.io.funct3 := id2ex.io.output_instruction(14, 12)
  ex2mem.io.reg2_data := ex.io.mem_reg2_data
  ex2mem.io.memory_read_enable := id2ex.io.output_memory_read_enable
  ex2mem.io.memory_write_enable := id2ex.io.output_memory_write_enable
  ex2mem.io.alu_result := ex.io.mem_alu_result
  ex2mem.io.csr_read_data := id2ex.io.output_csr_read_data

  mem.io.alu_result := ex2mem.io.output_alu_result
  mem.io.reg2_data := ex2mem.io.output_reg2_data
  mem.io.memory_read_enable := ex2mem.io.output_memory_read_enable
  mem.io.memory_write_enable := ex2mem.io.output_memory_write_enable
  mem.io.funct3 := ex2mem.io.output_funct3
  mem.io.regs_write_source := ex2mem.io.output_regs_write_source
  mem.io.csr_read_data := ex2mem.io.output_csr_read_data

  mem2wb.io.instruction_address := ex2mem.io.output_instruction_address
  mem2wb.io.alu_result := ex2mem.io.output_alu_result
  mem2wb.io.regs_write_enable := ex2mem.io.output_regs_write_enable
  mem2wb.io.regs_write_source := ex2mem.io.output_regs_write_source
  mem2wb.io.regs_write_address := ex2mem.io.output_regs_write_address
  mem2wb.io.memory_read_data := mem.io.wb_memory_read_data
  mem2wb.io.csr_read_data := ex2mem.io.output_csr_read_data

  wb.io.instruction_address := mem2wb.io.output_instruction_address
  wb.io.alu_result := mem2wb.io.output_alu_result
  wb.io.memory_read_data := mem2wb.io.output_memory_read_data
  wb.io.regs_write_source := mem2wb.io.output_regs_write_source
  wb.io.csr_read_data := mem2wb.io.output_csr_read_data

  forwarding.io.rs1_id := id.io.regs_reg1_read_address
  forwarding.io.rs2_id := id.io.regs_reg2_read_address
  forwarding.io.rs1_ex := id2ex.io.output_regs_reg1_read_address
  forwarding.io.rs2_ex := id2ex.io.output_regs_reg2_read_address
  forwarding.io.rd_mem := ex2mem.io.output_regs_write_address
  forwarding.io.reg_write_enable_mem := ex2mem.io.output_regs_write_enable
  forwarding.io.rd_wb := mem2wb.io.output_regs_write_address
  forwarding.io.reg_write_enable_wb := mem2wb.io.output_regs_write_enable

  clint.io.instruction_address_if := inst_fetch.io.instruction_address
  clint.io.instruction_id := if2id.io.output_instruction
  clint.io.jump_flag := id.io.clint_jump_flag
  clint.io.jump_address := id.io.clint_jump_address
  clint.io.interrupt_flag := io.interrupt_flag
  clint.io.stall_flag_ctrl := ctrl.io.redirect_stall
  clint.io.csr_bundle <> csr_regs.io.clint_access_bundle

  csr_regs.io.reg_read_address_id := id.io.ex_csr_address
  csr_regs.io.reg_write_enable_ex := id2ex.io.output_csr_write_enable
  csr_regs.io.reg_write_address_ex := id2ex.io.output_csr_address
  csr_regs.io.reg_write_data_ex := ex.io.csr_write_data
}
