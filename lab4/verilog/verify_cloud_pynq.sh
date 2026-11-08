#!/usr/bin/env bash
# Run from any directory using a shell with sbt, CMake, Verilator and GCC.
# Complete the lab4 AXI TODOs before running this script.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p target
generator=()
case "$(uname -s)" in
    MINGW*|MSYS*) generator=(-G "MinGW Makefiles") ;;
esac
cmake -S csrc -B csrc/build_cloud_pynq "${generator[@]}" \
    -DCMAKE_TOOLCHAIN_FILE="$PWD/csrc/toolchain.cmake"
cmake --build csrc/build_cloud_pynq --target say_goodbye pi led_control_demo --parallel
sbt 'runMain board.cloud_pynq.VerilogGenerator say_goodbye target/goodbye-verilog' \
    'runMain board.cloud_pynq.VerilogGenerator pi target/pi-verilog' \
    'runMain board.cloud_pynq.VerilogGenerator led_control_demo target/led-verilog'
jobs=${JOBS:-8}
for program in goodbye pi led; do
    sim=$program
    if [[ $program == goodbye ]]; then sim=say_goodbye; fi
    verilator --cc --exe --build -j "$jobs" -Wno-fatal --top-module Top \
        --Mdir "target/$program-native" "target/$program-verilog/Top.v" \
        "$PWD/verilog/cloud_${sim}_sim.cpp" \
        > "target/$program-native-build.log" 2>&1
    "target/$program-native/VTop" > "target/$program-native.log" 2>&1
    cat "target/$program-native.log"
done
