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

#include "mmio_cloud_pynq.h"

// Common-cathode 7-segment patterns, active high.
static const unsigned char seg_code[16] = {
    0x3F, 0x06, 0x5B, 0x4F,
    0x66, 0x6D, 0x7D, 0x07,
    0x7F, 0x6F, 0x77, 0x7C,
    0x39, 0x5E, 0x79, 0x71
};

int main(void) {
    unsigned int i;

    // cloud_pynq 的 seg_data 是低有效：0 点亮该段。
    // seg_code 是常见的共阴极高有效段码，这里取反后再写入，
    // 使“亮的段”组成预期数字。
    for (i = 0; i < 8u; ++i) {
        seg_set(i, (unsigned char)~seg_code[(i + 1u) & 0xFu]);
    }

    for (;;) {
        // Keep the CPU alive; the display is refreshed by hardware.
    }
}
