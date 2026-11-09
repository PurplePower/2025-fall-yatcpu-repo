#!/bin/sh
# Build lab1 programs on native Linux with the RISC-V GCC toolchain.
set -e

cd "$(dirname "$0")"

JOBS=$(nproc 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)

rm -rf build
cmake -S . -B build -DCMAKE_TOOLCHAIN_FILE="$PWD/toolchain.cmake" .
cmake --build build --parallel "$JOBS"
