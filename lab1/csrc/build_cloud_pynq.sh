#!/bin/sh
# Build the cloud_pynq seven-segment demo into ../src/main/resources/seg_demo.asmbin
set -e

CROSS=${CROSS:-riscv64-unknown-elf-}
BUILD_DIR=${BUILD_DIR:-build_cloud_pynq}

mkdir -p "$BUILD_DIR"

${CROSS}gcc \
    -march=rv32i -mabi=ilp32 -mno-relax \
    -nostdlib -nostartfiles -ffreestanding -fno-builtin \
    -O2 \
    -T link_cloud_pynq.lds \
    start_cloud_pynq.S seg_demo.c \
    -o "$BUILD_DIR/seg_demo.elf"

${CROSS}objcopy -O binary -j .text -j .data \
    "$BUILD_DIR/seg_demo.elf" ../src/main/resources/seg_demo.asmbin

${CROSS}objdump -d "$BUILD_DIR/seg_demo.elf" > "$BUILD_DIR/seg_demo.dump"

echo "Generated ../src/main/resources/seg_demo.asmbin"
