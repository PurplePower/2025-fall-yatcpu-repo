#include "mmio.h"

// Keep the excerpt already supplied with the lab. Add further supplied lines
// here if needed; each entry includes the UART's CRLF line ending.
static const char *const lyrics[] = {
    "Never gonna give you up~ Never gonna let you down~\r\n",
    "Never gonna run around and~ desert you~\r\n"
};
#define LINE_COUNT (sizeof(lyrics) / sizeof(lyrics[0]))

// cloud_pynq Timer runs at 10 MHz: 100,000 clocks = a 10 ms tick.
// 100 ticks per line; one byte per tick also paces the cloud UART's small FIFO.
// Other boards must override this value for their CPU/Timer frequency.
#ifndef GOODBYE_TIMER_TICKS
#define GOODBYE_TIMER_TICKS 100000u
#endif
#define TICKS_PER_LINE 100u

static volatile unsigned int ticks;
// One atomic word holds both the byte and a sequence number. The foreground
// never clears it, so a receive interrupt cannot race a pending-flag clear.
static volatile unsigned int rx_event;
extern void enable_interrupt(void);

void trap_handler(void *epc, unsigned int cause)
{
    (void) epc;
    if (cause == 0x80000007u) {
        ++ticks;
    } else if (cause == 0x8000000Bu) {
        unsigned int ch = *UART_RECV;
        rx_event = ((rx_event + 0x100u) & ~0xFFu) | (ch & 0xFFu);
    }
}

static int due(unsigned int now, unsigned int deadline)
{
    return (int) (now - deadline) >= 0;
}

int main(void)
{
    *TIMER_ENABLED = 0;
    *TIMER_LIMIT = GOODBYE_TIMER_TICKS - 1u;
    enable_interrupt();
    *TIMER_ENABLED = 1;

    unsigned int seen_event = 0;
    unsigned int line = 0;
    unsigned int offset = 0;
    unsigned int playing = 1;
    unsigned int active_line = 0;
    unsigned int separator = 0;
    unsigned int next_line = ticks + TICKS_PER_LINE;
    unsigned int next_byte = ticks;

    for (;;) {
        unsigned int event = rx_event;
        unsigned int now = ticks;
        if (event != seen_event) {
            seen_event = event;
            switch (event & 0xFFu) {
            case 's':
                playing = 0;
                break;
            case 'p':
                if (!playing && line < LINE_COUNT) {
                    playing = 1;
                    next_line = now + TICKS_PER_LINE;
                    next_byte = now;
                }
                break;
            case 'r':
                line = 0;
                offset = 0;
                active_line = 0;
                playing = 1;
                separator = 2; // CRLF before restarting, even from mid-line.
                next_line = now + TICKS_PER_LINE;
                next_byte = now;
                break;
            default:
                break; // Ignore unrelated keys and terminal CR/LF.
            }
        }

        // Never wait for TX or sleep in the ISR/foreground. Commands continue
        // to be consumed while output is paced or the TX buffer is occupied.
        if (separator) {
            if (due(now, next_byte) && (*UART_STAT & 1u)) {
                *UART_SEND = separator == 2u ? '\r' : '\n';
                --separator;
                next_byte = now + 1u;
            }
            continue;
        }
        if (!playing) continue;

        if (!active_line && due(now, next_line)) {
            active_line = 1;
            offset = 0;
            next_line = now + TICKS_PER_LINE;
        }
        if (active_line && due(now, next_byte) && (*UART_STAT & 1u)) {
            *UART_SEND = (unsigned char) lyrics[line][offset++];
            next_byte = now + 1u;
            if (lyrics[line][offset] == '\0') {
                active_line = 0;
                if (++line == LINE_COUNT) playing = 0;
            }
        }
    }
}
