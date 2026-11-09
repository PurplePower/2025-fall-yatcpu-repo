// Real-period integration test: production CPU, ROM, Timer and UART pins.
#include "VTop.h"
#include "verilated.h"
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

static uint64_t simulation_time;
double sc_time_stamp() { return static_cast<double>(simulation_time); }
static void require(bool condition, const char *message) {
    if (!condition) { std::fprintf(stderr, "FAIL: %s\n", message); std::exit(1); }
}

class SerialSimulation {
public:
    VTop dut;
    uint64_t cycles = 0;
    std::string output;
    std::vector<uint64_t> starts;
    static constexpr unsigned bit_cycles = 4167;
    SerialSimulation() {
        dut.uart_rx = 1;
        dut.btn = 0xff;
        dut.sw1 = dut.sw2 = 0xffffffff;
        dut.reset_n = 0;
        advance(8);
        dut.reset_n = 1;
    }
    void advance(uint64_t count) {
        for (uint64_t i = 0; i < count; ++i) {
            dut.clock = 0; dut.eval(); ++simulation_time;
            dut.clock = 1; dut.eval(); ++simulation_time;
            ++cycles;
            if (bit < 0) {
                if (!dut.uart_tx) {
                    start = cycles;
                    sample = cycles + bit_cycles * 3 / 2;
                    bit = 0;
                    value = 0;
                }
            } else if (cycles >= sample) {
                if (bit < 8) {
                    if (dut.uart_tx) value |= 1u << bit;
                    ++bit;
                    sample += bit_cycles;
                } else {
                    require(dut.uart_tx, "invalid UART stop bit");
                    output += static_cast<char>(value);
                    starts.push_back(start);
                    bit = -1;
                }
            }
        }
    }
    void await_bytes(size_t length, uint64_t timeout) {
        const auto deadline = cycles + timeout;
        while (output.size() < length && cycles < deadline) advance(4096);
        if (output.size() < length)
            std::fprintf(stderr, "cycles=%llu PC=%08x received=%zu expected=%zu text=[%s]\n",
                         static_cast<unsigned long long>(cycles), ~dut.gpio_led,
                         output.size(), length, output.c_str());
        require(output.size() >= length, "timed out waiting for UART output");
    }
    void send(char ch) {
        dut.uart_rx = 0; advance(bit_cycles);
        for (unsigned i = 0; i < 8; ++i) {
            dut.uart_rx = (static_cast<unsigned char>(ch) >> i) & 1u;
            advance(bit_cycles);
        }
        dut.uart_rx = 1; advance(bit_cycles);
    }
private:
    int bit = -1;
    unsigned value = 0;
    uint64_t sample = 0, start = 0;
};


static void check_display(SerialSimulation &sim, unsigned style) {
    static constexpr unsigned seg_code[16] = {
        0x3f,0x06,0x5b,0x4f,0x66,0x6d,0x7d,0x07,
        0x7f,0x6f,0x77,0x7c,0x39,0x5e,0x79,0x71};
    static constexpr unsigned glyphs[4][7] = {
        {0x1e,0x11,0x11,0x1e,0x14,0x12,0x11},
        {0x1f,0x04,0x04,0x04,0x04,0x04,0x1f},
        {0x0f,0x10,0x10,0x0e,0x01,0x01,0x1e},
        {0x0f,0x10,0x10,0x10,0x10,0x10,0x0f}};
    unsigned seg_seen = 0;
    uint64_t matrix_seen = 0;
    for (unsigned i = 0; i < 600000; ++i) {
        sim.advance(1);
        unsigned digit = sim.dut.seg_cs;
        require(digit < 8, "invalid segment scan index");
        unsigned expected = (~seg_code[(digit + 1 + style) & 15]) & 255;
        require(sim.dut.seg_data == expected, "incorrect seven-segment display");
        seg_seen |= 1u << digit;
        unsigned index = sim.dut.ledm_cs;
        unsigned row = index & 15, panel = index >> 4;
        unsigned pixels = 0;
        if (row >= 1 && row < 15) {
            for (unsigned col = 3; col < 13; ++col) {
                if (glyphs[(panel + style) & 3][(row-1)/2] & (1u << (4-(col-3)/2)))
                    pixels |= 1u << col;
            }
        }
        if (style & 2) pixels = (~pixels) & 65535;
        require(sim.dut.ledm_data == ((~pixels) & 65535), "incorrect LED matrix pixels");
        matrix_seen |= 1ULL << index;
    }
    require(seg_seen == 255 && matrix_seen == UINT64_MAX, "incomplete display scan");
}

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    SerialSimulation sim;
    sim.advance(6000000);
    check_display(sim, 0);
    sim.send('q');
    sim.await_bytes(1, 1000000);
    require(sim.output == "q", "UART interrupt echo did not return exactly one byte");
    sim.advance(42000000);
    check_display(sim, 1);
    std::puts("PASS: production LED ROM, all segment digits and 64 matrix rows, one-second timer animation, UART IRQ echo");
    sim.dut.reset_n = 0;
    sim.advance(64);
    sim.dut.reset_n = 1;
    sim.advance(6000000);
    check_display(sim, 0);
    std::puts("PASS: reset reloads the AXI RAM and restores initial display");
    sim.dut.final();
}
