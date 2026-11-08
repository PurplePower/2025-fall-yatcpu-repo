# Copyright 2021 Howard Lau
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

ifeq ($(OS),Windows_NT)
CMAKE_GENERATOR = -G "MinGW Makefiles"
endif
OUTPUT_DIR ?=

test:
	sbt test

verilator:
	sbt "runMain board.verilator.VerilogGenerator"
	cd verilog/verilator && verilator --trace --exe --cc sim_main.cpp Top.v && make -C obj_dir -f VTop.mk

verilator-sim: verilator
	cd verilog/verilator && obj_dir/VTop $(SIM_TIME)

basys3: PROGRAM ?= tetris
basys3:
	sbt "runMain board.basys3.VerilogGenerator $(PROGRAM) $(OUTPUT_DIR)"
	
pynq: PROGRAM ?= hello
pynq:
	sbt "runMain board.pynq.VerilogGenerator $(PROGRAM) $(OUTPUT_DIR)"
	
z710: PROGRAM ?= say_goodbye
z710: cloud-pynq-program
	sbt "runMain board.z710.VerilogGenerator $(PROGRAM) $(OUTPUT_DIR)"

z710v1.3: PROGRAM ?= say_goodbye
z710v1.3: cloud-pynq-program
	sbt "runMain board.z710v1_3.VerilogGenerator $(PROGRAM) $(OUTPUT_DIR)"

# Build any lab1 program with the unified startup/linker script.
# Usage: make program PROGRAM=say_goodbye
program:
	cmake -S csrc -B csrc/build_cloud_pynq $(CMAKE_GENERATOR) -DCMAKE_TOOLCHAIN_FILE="$(CURDIR)/csrc/toolchain.cmake"
	cmake --build csrc/build_cloud_pynq --target $(patsubst %.asmbin,%,$(or $(PROGRAM),led_control_demo)) --parallel

cloud-pynq-program: program

cloud_pynq: PROGRAM ?= led_control_demo
cloud_pynq: cloud-pynq-program
	sbt "runMain board.cloud_pynq.VerilogGenerator $(PROGRAM) $(OUTPUT_DIR)"

bitstream-basys3: basys3
	cd vivado/basys3 && vivado -mode batch -source generate_bitstream.tcl

program-basys3: bitstream-basys3
	cd vivado/basys3 && vivado -mode batch -source program_device.tcl

vivado-sim-basys3: basys3
	cd vivado/basys3 && vivado -mode batch -source run_simulation.tcl
	
bitstream-pynq: pynq
	cd vivado/pynq && vivado -mode batch -source generate_bitstream.tcl

program-pynq: bitstream-pynq
	cd vivado/pynq && vivado -mode batch -source program_device.tcl

vivado-sim-pynq: pynq
	cd vivado/pynq && vivado -mode batch -source run_simulation.tcl
	
bitstream-z710: z710
	cd vivado/z710 && vivado -mode batch -source generate_bitstream.tcl

program-z710: bitstream-z710
	cd vivado/z710 && vivado -mode batch -source program_device.tcl

vivado-sim-z710: z710
	cd vivado/z710 && vivado -mode batch -source run_simulation.tcl

bitstream-z710v1.3: z710v1.3
	cd vivado/z710v1.3 && vivado -mode batch -source generate_bitstream.tcl

bitstream-cloud_pynq: cloud_pynq
	cd vivado/cloud_pynq && vivado -mode batch -source generate_bitstream.tcl

.PHONY: basys3 verilator z710 z710v1.3 cloud_pynq cloud-pynq-program program test bitstream verilator-sim vivado-sim
