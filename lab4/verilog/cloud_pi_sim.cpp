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


int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    SerialSimulation sim;
    size_t seen = 0;
    while (sim.cycles < 4000000000ULL && sim.output.find(" ms\n") == std::string::npos) {
        sim.advance(8192);
        if (seen != sim.output.size()) {
            std::fwrite(sim.output.data() + seen, 1, sim.output.size() - seen, stdout);
            std::fflush(stdout);
            seen = sim.output.size();
        }
    }
    require(sim.output.find("3.14159265358979323846") == 0, "incorrect pi digits");
    require(sim.output.find("computed_digits = 200\n") != std::string::npos, "incorrect workload");
    require(sim.output.find("timebase_hz = 1000000\n") != std::string::npos, "incorrect timebase");
    require(sim.output.find(" ms\n") != std::string::npos, "benchmark did not finish");
    auto cycles = std::stoull(sim.output.substr(sim.output.find("cycles = ") + 9));
    auto us = std::stoull(sim.output.substr(sim.output.find("elapsed_us = ") + 13));
    require(cycles + 1000 > us*40 && cycles < us*40 + 1000, "cycle and MTime clocks disagree");
    std::printf("PASS: production pi ROM, digits, workload, cycle/MTime ratio; %llu master cycles\n", (unsigned long long)sim.cycles);
    sim.dut.final();
}
