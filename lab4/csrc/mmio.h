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

#ifndef LAB3_MMIO_H
#define LAB3_MMIO_H

// Board-independent MMIO map.
//
// The CPU decodes the device from addr[31:29] (deviceSelect):
//   0: internal 32 KiB RAM
//   1: character display / VRAM
//   2: UART
//   3: 8-digit seven-segment display (cloud_pynq)
//   4: timer
//   5: four 16x16 LED matrices (cloud_pynq)
//   6: 1 MHz wall-clock counter (MTime)
//
// Not every board implements every device.  Current board capabilities:
//   basys3      : VRAM, Timer, UART (115200)
//   pynq        : VRAM, Timer, UART (115200)
//   z710        : UART (115200), Timer (6.25 MHz)
//   z710v1.3    : UART (115200), Timer (12.5 MHz)
//   cloud_pynq  : UART (9600), Timer (10 MHz), 8-digit seven-segment display, LED matrices
//   verilator   : external bus / testbench
//
// MTime is implemented by z710, z710v1.3, basys3 and cloud_pynq at 0xC0000000.
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

#define LEDM_BASE 0xA0000000u
#define LEDM_PANELS 4u
#define LEDM_ROWS 16u
#define LEDM_COLS 16u
#define LEDM_REG(panel, row) (*(volatile unsigned int *) \
    (LEDM_BASE + ((((unsigned int) (panel)) * LEDM_ROWS + ((unsigned int) (row))) << 2)))

#define TIMER_BASE 0x80000000
#define TIMER_LIMIT ((volatile unsigned int *) (TIMER_BASE + 4))
#define TIMER_ENABLED ((volatile unsigned int *) (TIMER_BASE + 8))

// 1 MHz wall-clock counter.  Independent of the CPU clock.
#define MTIME_BASE 0xC0000000u
#define MTIME_LO ((volatile unsigned int *) (MTIME_BASE + 0))
#define MTIME_HI ((volatile unsigned int *) (MTIME_BASE + 4))
#define MTIME_HZ 1000000u

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
// Read the 1 MHz tick counter.  Use the low word for benchmarks shorter than
// 2^32 microseconds (~71.6 minutes); use read_mtime() for a 64-bit snapshot.
static inline unsigned int read_mtime_lo(void) {
    return *MTIME_LO;
}

static inline unsigned long long read_mtime(void) {
    unsigned int hi, lo, hi2;
    do {
        hi  = *MTIME_HI;
        lo  = *MTIME_LO;
        hi2 = *MTIME_HI;
    } while (hi != hi2);
    return ((unsigned long long) hi << 32) | lo;
}

// Common UART TX helpers.  Always check the TX-ready bit before writing,
// so a character is never dropped by the single-byte TX buffer.
static inline int uart_tx_ready(void) {
    return (*UART_STAT & 1u) != 0u;
}

// Non-blocking: returns 0 when the TX buffer is full.
static inline int uart_try_putc(char c) {
    if (!uart_tx_ready()) {
        return 0;
    }
    *UART_SEND = (unsigned int) (unsigned char) c;
    return 1;
}

// Blocking: waits for the TX buffer, then sends one character.
static inline void uart_putc(char c) {
    while (!uart_try_putc(c)) {
        /* wait for TX ready */
    }
}

static inline void uart_puts(const char *s) {
    while (*s != '\0') {
        uart_putc(*s++);
    }
}

static inline void uart_put_uint(unsigned int v) {
    char buf[10];
    int n = 0;
    if (v == 0u) {
        uart_putc('0');
        return;
    }
    while (v != 0u) {
        buf[n++] = (char) ('0' + (v % 10u));
        v /= 10u;
    }
    while (n > 0) {
        uart_putc(buf[--n]);
    }
}

// Software uses active-high row bitmaps; hardware inverts ledm_data.
static inline void ledm_set_row(unsigned int panel, unsigned int row,
                                unsigned short pixels) {
    LEDM_REG(panel, row) = (unsigned int) pixels;
}

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

#endif // LAB3_MMIO_H
