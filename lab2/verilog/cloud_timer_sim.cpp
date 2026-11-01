// Native long-duration test for the production cloud_pynq Top.v and ROM.
// Avoids ChiselTest's thread handoff on each of the 80 million clock cycles.
#include "VTop.h"
#include "verilated.h"
#include <cstdint>
#include <cstdio>
#include <cstdlib>

static uint64_t simulation_time = 0;
double sc_time_stamp() { return static_cast<double>(simulation_time); }

static void require(bool condition, const char *message) {
    if (!condition) { std::fprintf(stderr, "FAIL: %s\n", message); std::exit(1); }
}

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VTop dut;
    dut.uart_rx = 1;
    dut.btn = 0xff;
    dut.sw1 = dut.sw2 = 0xffffffff;
    auto step = [&]() {
        dut.clock = 0; dut.eval(); ++simulation_time;
        dut.clock = 1; dut.eval(); ++simulation_time;
    };
    dut.reset_n = 0;
    for (int i = 0; i < 8; ++i) step();
    dut.reset_n = 1;
    unsigned digit0 = 0;
    unsigned row1 = 0;
    uint64_t firstChange = 0, secondChange = 0;
    bool initialized = false;
    for (uint64_t cycle = 1; cycle <= 81000000; ++cycle) {
        step();
        if (dut.seg_cs == 0) {
            digit0 = dut.seg_data;
            if (digit0 == 0xf9) initialized = true;
            if (initialized && digit0 == 0xa4 && !firstChange) firstChange = cycle;
            if (initialized && digit0 == 0xb0 && !secondChange) secondChange = cycle;
        }
        if (dut.ledm_cs == 1) row1 = dut.ledm_data;
        if (cycle == 1000000) require(digit0 == 0xf9, "initial digit 0 must show 1");
        if (cycle == 39000000) require(digit0 == 0xf9, "animation changed before one second");
        if (cycle == 41000000) {
            require(digit0 == 0xa4, "first Timer interrupt did not advance display to 2");
            // I glyph, row 1: five doubled pixels, columns 3..12.
            require(row1 == (0xffffu ^ 0x1ff8u), "matrix did not rotate RISC to ISCR");
        }
        if (cycle % 10000000 == 0) {
            std::printf("cycles=%llu digit0=%02x\n", static_cast<unsigned long long>(cycle), digit0);
            std::fflush(stdout);
        }
    }
    require(digit0 == 0xb0, "second Timer interrupt did not advance display to 3");
    require(firstChange >= 40000000 && firstChange < 40500000, "first change outside one-second scan window");
    require(secondChange >= 80000000 && secondChange < 80500000, "second change outside two-second scan window");
    std::printf("PASS: production ROM, 40 MHz master / 10 MHz Timer; changes at %llu and %llu cycles\n",
        static_cast<unsigned long long>(firstChange), static_cast<unsigned long long>(secondChange));
    dut.reset_n = 0;
    for (int i = 0; i < 8; ++i) step();
    dut.reset_n = 1;
    for (int i = 0; i < 1000000; ++i) {
        step();
        if (dut.seg_cs == 0) digit0 = dut.seg_data;
    }
    require(digit0 == 0xf9, "reset did not reload the program and restore initial style");
    std::puts("PASS: reset restores initial style after two Timer interrupts");
    dut.final();
}
