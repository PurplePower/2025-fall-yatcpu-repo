#!/bin/sh
set -eu
cd "$(dirname "$0")"
cmake -S . -B build -DCMAKE_TOOLCHAIN_FILE="$PWD/toolchain.cmake"
if [ "$#" -eq 0 ]; then
    cmake --build build --parallel
else
    cmake --build build --parallel --target "$@"
fi
