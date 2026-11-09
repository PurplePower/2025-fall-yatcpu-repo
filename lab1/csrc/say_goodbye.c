#include "mmio.h"

void uart_send_char(char c)
{
  // Wait until the UART's single-byte TX buffer is empty before writing.
  // This works on every UART board regardless of baud rate / CPU clock.
  while ((*UART_STAT & 1u) == 0u) {
  }
  *UART_SEND = (unsigned char) c;
}

// The cloud platform's AXI Uartlite has a small RX FIFO (16 bytes) and the
// web/PS side may poll it slower than 9600 baud.  Keep the average byte rate
// below the PS drain rate so the FIFO does not overflow and drop characters.
static void uart_pace(void)
{
  for (volatile unsigned int i = 0; i < 10000u; ++i) {
  }
}

int main() {

    const char* s = "Never gonna give you up~ Never gonna let you down~\r\n"
    "Never gonna run around and~ desert you~\r\n";

    while (1) {
      const char *p = s;
      while (*p != 0)
      {
        uart_send_char(*p);
        p++;
        // uart_pace();
      }

      break;  // print once, but pressing CPU reset can print again
    }

    return 0;
}
