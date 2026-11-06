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

package peripheral

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import riscv.TestAnnotations

class LedMatrixDisplayTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "LedMatrixDisplay"

  it should "read and write all rows and scan them with active-low data" in {
    test(new LedMatrixDisplay(frequency = 1_000_000, refreshUs = 2))
      .withAnnotations(TestAnnotations.annos) { c =>
        c.io.bundle.address.poke(0.U)
        c.io.bundle.write_data.poke(0.U)
        c.io.bundle.write_enable.poke(false.B)
        c.io.bundle.write_strobe.foreach(_.poke(false.B))
        c.reset.poke(true.B)
        c.clock.step()
        c.reset.poke(false.B)

        c.io.ledm_cs.expect(0.U)
        c.io.ledm_data.expect(0xFFFF.U)

        val pixels = (0 until 64).map(row => ((row * 0x0411) ^ 0xA55A) & 0xFFFF)
        for (row <- 0 until 64) {
          c.io.bundle.address.poke((row << 2).U)
          c.io.bundle.write_data.poke(pixels(row).U)
          c.io.bundle.write_enable.poke(true.B)
          c.io.bundle.write_strobe.foreach(_.poke(true.B))
          c.clock.step()
          c.io.bundle.write_enable.poke(false.B)
          c.io.bundle.read_data.expect(pixels(row).U)
        }

        // Synchronize to a row transition. Each row then lasts exactly two cycles.
        val oldRow = c.io.ledm_cs.peek().litValue.toInt
        var waited = 0
        while (c.io.ledm_cs.peek().litValue.toInt == oldRow && waited < 3) {
          c.clock.step()
          waited += 1
        }
        assert(waited < 3, "row selection did not advance")
        val firstRow = c.io.ledm_cs.peek().litValue.toInt

        for (offset <- 0 until 64) {
          val row = (firstRow + offset) % 64
          c.io.ledm_cs.expect(row.U)
          c.io.ledm_data.expect((~pixels(row) & 0xFFFF).U)
          c.clock.step()
          c.io.ledm_cs.expect(row.U)
          c.clock.step()
        }
        c.io.ledm_cs.expect(firstRow.U)
      }
  }
}
