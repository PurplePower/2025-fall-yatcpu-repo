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
EXE_EXT = .exe
endif
PROGRAM ?= led_control_demo
VIVADO ?= vivado

test:
	sbt test

verilator:
	sbt "runMain board.verilator.VerilogGenerator"
	cd verilog/verilator && verilator --trace --exe --cc sim_main.cpp Top.v && make -C obj_dir -f VTop.mk

verilator-sim: verilator
	cd verilog/verilator && obj_dir/VTop $(SIM_TIME)

basys3:
	sbt "runMain board.basys3.VerilogGenerator"
	
pynq:
	sbt "runMain board.pynq.VerilogGenerator"
	
z710:
	sbt "runMain board.z710.VerilogGenerator"

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

.PHONY: basys3 verilator z710 test bitstream program verilator-sim vivado-sim

program:
	cmake -S csrc -B csrc/build_cloud_pynq $(CMAKE_GENERATOR) -DCMAKE_TOOLCHAIN_FILE="$(CURDIR)/csrc/toolchain.cmake"
	cmake --build csrc/build_cloud_pynq --target $(PROGRAM) --parallel

cloud-pynq-program: program

cloud_pynq: program
	sbt "runMain board.cloud_pynq.VerilogGenerator $(PROGRAM)"

bitstream-cloud_pynq: cloud_pynq
	cd vivado/cloud_pynq && $(VIVADO) -mode batch -source generate_bitstream.tcl

bitstream-cloud_pynq-2020: cloud_pynq
	cd vivado/cloud_pynq && $(VIVADO) -mode batch -source generate_bitstream_2020.tcl

z710v1.3:
	sbt "runMain board.z710v1_3.VerilogGenerator"

.PHONY: program cloud-pynq-program cloud_pynq bitstream-cloud_pynq bitstream-cloud_pynq-2020 z710v1.3

# Run from a shell with Verilator, make and native g++ on PATH (MSYS2 MinGW64 on Windows).
native-cloud-test: cloud_pynq
	verilator --cc --exe --build -j 4 --top-module Top -Wno-fatal --Mdir target/cloud-native verilog/cloud_pynq/Top.v "$(CURDIR)/verilog/cloud_timer_sim.cpp"
	./target/cloud-native/VTop$(EXE_EXT)

.PHONY: native-cloud-test

# Test the production UART player without replacing the LED-demo Vivado RTL.
native-goodbye-test:
	cmake -S csrc -B csrc/build_cloud_pynq $(CMAKE_GENERATOR) -DCMAKE_TOOLCHAIN_FILE="$(CURDIR)/csrc/toolchain.cmake"
	cmake --build csrc/build_cloud_pynq --target say_goodbye --parallel
	sbt "Test / runMain riscv.CloudGoodbyeVerilog"
	verilator --cc --exe --build -j 4 --top-module Top -Wno-fatal --Mdir target/goodbye-native target/goodbye-verilog/Top.v "$(CURDIR)/verilog/cloud_goodbye_sim.cpp"
	./target/goodbye-native/VTop$(EXE_EXT)

.PHONY: native-goodbye-test
