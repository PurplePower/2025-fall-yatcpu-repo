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

#include "mmio.h"

// 5x7 glyphs for R, I, S and C. Bit 4 is the leftmost glyph column.
static const unsigned char glyphs[LEDM_PANELS][7] = {
    {0x1E, 0x11, 0x11, 0x1E, 0x14, 0x12, 0x11},
    {0x1F, 0x04, 0x04, 0x04, 0x04, 0x04, 0x1F},
    {0x0F, 0x10, 0x10, 0x0E, 0x01, 0x01, 0x1E},
    {0x0F, 0x10, 0x10, 0x10, 0x10, 0x10, 0x0F}
};

int main(void) {
    unsigned int panel;

    // Show one 10x14 glyph, centered on each 16x16 panel: R I S C.
    for (panel = 0; panel < LEDM_PANELS; ++panel) {
        unsigned int row;
        for (row = 0; row < LEDM_ROWS; ++row) {
            unsigned int col;
            unsigned short pixels = 0;

            for (col = 0; col < LEDM_COLS; ++col) {
                if (row >= 1u && row < 15u && col >= 3u && col < 13u) {
                    unsigned int glyph_row = (row - 1u) >> 1;
                    unsigned int glyph_col = (col - 3u) >> 1;
                    unsigned int mask = 1u << (4u - glyph_col);

                    if ((glyphs[panel][glyph_row] & mask) != 0u) {
                        // Software bit 15 is the leftmost column. The controller
                        // converts this active-high bitmap to active-low outputs.
                        pixels |= (unsigned short) (1u << (15u - col));
                    }
                }
            }
            ledm_set_row(panel, row, pixels);
        }
    }

    for (;;) {
        // The LED matrix controller refreshes all four panels in hardware.
    }
}
