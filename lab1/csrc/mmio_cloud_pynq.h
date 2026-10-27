// Copyright 2026 YatCPU contributors
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

#ifndef MMIO_CLOUD_PYNQ_H
#define MMIO_CLOUD_PYNQ_H

// cloud_pynq MMIO map (see board/cloud_pynq/Top.scala)
//   deviceSelect 0: RAM
//   deviceSelect 2: UART   (0x4000_0000)
//   deviceSelect 3: 8-digit seven-segment display (0x6000_0000)

#define UART_BASE   0x40000000u
#define UART_BAUD   (*(volatile unsigned int *)(UART_BASE + 0x4))
#define UART_RECV   (*(volatile unsigned int *)(UART_BASE + 0xC))
#define UART_SEND   (*(volatile unsigned int *)(UART_BASE + 0x10))

#define SEG_BASE    0x60000000u
#define SEG_REG(d)  (*(volatile unsigned int *)(SEG_BASE + (((unsigned int)(d)) << 2)))
#define SEG_ENABLE  0x00000100u

// 注意：cloud_pynq 的 seg_data 为低有效（0 = 点亮该段）。
// seg_set 的 pattern 会原样写入 seg_data，因此调用者需按低有效编码；
// 例如 seg_demo.c 会把常见的共阴极高有效段码取反后再传入。
static inline void seg_set(unsigned int digit, unsigned char pattern) {
    SEG_REG(digit) = SEG_ENABLE | (unsigned int)pattern;
}

// 一次 32 位写：熄灭该位（0xFF 表示所有段都熄灭）。
static inline void seg_clear(unsigned int digit) {
    SEG_REG(digit) = SEG_ENABLE | 0xFFu;
}

// 一次操作清除全部 8 位。
static inline void seg_clear_all(void) {
    for (unsigned int i = 0; i < 8u; ++i) {
        SEG_REG(i) = SEG_ENABLE | 0xFFu;
    }
}

#endif // MMIO_CLOUD_PYNQ_H
