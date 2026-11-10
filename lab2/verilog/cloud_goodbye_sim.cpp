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
    unsigned cpuDiv = 0;
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
            dut.clock = 0; dut.cpu_clock = (cpuDiv >> 1) & 1; dut.eval(); ++simulation_time;
            dut.clock = 1; dut.eval(); ++simulation_time;
            ++cpuDiv;
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
    constexpr uint64_t second = 40000000;
    const std::string first = "Never gonna give you up~ Never gonna let you down~\r\n";
    const std::string last = "Never gonna run around and~ desert you~\r\n";
    const auto song = first + last;
    sim.await_bytes(song.size(), second * 3);
    require(sim.output == song, "production output differs from the supplied excerpt");
    const auto interval = sim.starts[first.size()] - sim.starts[0];
    require(interval > second - 12000 && interval < second + 12000,
            "line starts are not one second apart");
    sim.advance(second + 100000);
    require(sim.output == song, "player did not stop after one pass");
    sim.send('p'); sim.advance(second + 100000);
    require(sim.output == song, "play after EOF must not restart");
    std::printf("PASS: production ROM, one-second line interval = %llu master clocks; stop at EOF\n",
                static_cast<unsigned long long>(interval));
    std::fflush(stdout);

    sim.send('r');
    sim.await_bytes(song.size() + 2 + 5, second * 2);
    require(sim.output.substr(song.size(), 7) == "\r\n" + first.substr(0, 5),
            "replay must prepend CRLF and start at the first line");
    sim.send('s'); sim.advance(100000); // Drain the already accepted UART byte(s).
    const auto paused = sim.output;
    require(paused.size() < song.size() + 2 + first.size(), "pause did not interrupt the line");
    sim.send('s'); sim.send('x'); sim.send('\r'); sim.send('\n');
    sim.advance(second + 100000);
    require(sim.output == paused, "paused output changed, or unknown key resumed playback");
    sim.send('p');
    sim.await_bytes(song.size() * 2 + 2, second * 3);
    require(sim.output == song + "\r\n" + song, "resume dropped/repeated characters");
    std::puts("PASS: mid-line stop/resume, repeated stop, ignored keys, replay from EOF");
    std::fflush(stdout);

    const auto prior = sim.output;
    sim.send('r');
    sim.await_bytes(prior.size() + 2 + 5, second * 2);
    const auto partial_start = prior.size() + 2;
    sim.send('r');
    const auto deadline = sim.cycles + second;
    while (sim.output.find("\r\n", partial_start) == std::string::npos && sim.cycles < deadline)
        sim.advance(4096);
    const auto separator = sim.output.find("\r\n", partial_start);
    require(separator != std::string::npos, "mid-line replay did not insert CRLF");
    const auto partial = sim.output.substr(partial_start, separator - partial_start);
    require(first.compare(0, partial.size(), partial) == 0, "partial old line was corrupted");
    const auto restart = separator + 2;
    sim.await_bytes(restart + song.size(), second * 3);
    require(sim.output.substr(restart) == song, "mid-line replay did not restart the complete excerpt");
    sim.advance(second + 100000);
    require(sim.output.size() == restart + song.size(), "replay did not stop at EOF");
    std::puts("PASS: replay during transmission discards pending text, inserts CRLF and stops at EOF");
    sim.dut.final();
}
