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

#ifndef LAB1_MMIO_H
#define LAB1_MMIO_H

// Board-independent MMIO map.
//
// The CPU decodes the device from addr[31:29] (deviceSelect):
//   0: internal 32 KiB RAM
//   1: character display / VRAM
//   2: UART
//   3: 8-digit seven-segment display (cloud_pynq)
//   4: timer (reserved; not instantiated on current lab1 boards)
//
// Not every board implements every device.  Current board capabilities:
//   basys3      : VRAM
//   pynq        : VRAM
//   z710        : UART (115200)
//   z710v1.3    : UART (115200)
//   cloud_pynq  : UART (9600), 8-digit seven-segment display
//   verilator   : external bus / testbench
//
// Programs that use a device must only be run on a board that implements it.

#define VRAM_BASE 0x20000000
#define VRAM ((volatile unsigned char *) VRAM_BASE)

#define UART_BASE 0x40000000
#define UART_BAUDRATE ((volatile unsigned int *) (UART_BASE + 4))
#define UART_IRQ ((volatile unsigned int *) (UART_BASE + 8))
#define UART_RECV ((volatile unsigned int *) (UART_BASE + 12))
#define UART_SEND ((volatile unsigned int *) (UART_BASE + 16))
// bit0 = TX ready (single-byte buffer empty), bit1 = RX valid (sticky)
#define UART_STAT ((volatile unsigned int *) (UART_BASE + 20))

#define SEG_BASE 0x60000000
#define SEG_REG(d) (*(volatile unsigned int *) (SEG_BASE + (((unsigned int) (d)) << 2)))
#define SEG_ENABLE 0x00000100u

#define TIMER_BASE 0x80000000
#define TIMER_LIMIT ((volatile unsigned int *) (TIMER_BASE + 4))
#define TIMER_ENABLED ((volatile unsigned int *) (TIMER_BASE + 8))

// Virtual addresses used by the MMU programs (paging / tetris_mmu).
// They are mapped to the physical bases above by the program's page table.
#define VA_VRAM_BASE 0x00100000
#define VA_VRAM ((volatile unsigned char *) VA_VRAM_BASE)
#define VA_TIMER_BASE 0x00200000
#define VA_TIMER_LIMIT ((volatile unsigned int *) (VA_TIMER_BASE + 4))
#define VA_TIMER_ENABLED ((volatile unsigned int *) (VA_TIMER_BASE + 8))
#define VA_UART_BASE 0x00300000
#define VA_UART_BAUDRATE ((volatile unsigned int *) (VA_UART_BASE + 4))
#define VA_UART_RECV ((volatile unsigned int *) (VA_UART_BASE + 12))
#define VA_UART_SEND ((volatile unsigned int *) (VA_UART_BASE + 16))
#define VA_UART_STAT ((volatile unsigned int *) (VA_UART_BASE + 20))

#ifndef __ASSEMBLER__
// cloud_pynq's seg_data is active low (0 lights the segment).
// seg_set writes the pattern as-is; callers must encode active-low patterns.
static inline void seg_set(unsigned int digit, unsigned char pattern) {
    SEG_REG(digit) = SEG_ENABLE | (unsigned int) pattern;
}

static inline void seg_clear(unsigned int digit) {
    SEG_REG(digit) = SEG_ENABLE | 0xFFu;
}

static inline void seg_clear_all(void) {
    for (unsigned int i = 0; i < 8u; ++i) {
        SEG_REG(i) = SEG_ENABLE | 0xFFu;
    }
}
#endif

#endif // LAB1_MMIO_H
