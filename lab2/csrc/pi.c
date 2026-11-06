/*
 * pi.c - compute pi with the Rabinowitz-Wagon spigot algorithm.
 *
 * Target: RV32I + Zicsr, no M/F/D extension.
 * The M extension is emulated in software below, so the program can be
 * compiled with -march=rv32i_zicsr and linked with -nostdlib.
 *
 * Output: the first PI_PRINT_DIGITS decimal digits of pi, followed by
 * the CPU cycle count and the wall-clock elapsed time from the 1 MHz
 * MTime MMIO peripheral.  MTime is independent of the CPU clock, so the
 * same program works on 10/12.5/25/50 MHz CPU configurations.
 *
 * Build-time knobs:
 *   -DPI_COMPUTE_DIGITS=200  digits actually computed (timing workload)
 *   -DPI_PRINT_DIGITS=20     digits printed after the decimal point
 */

typedef unsigned int u32;
typedef int s32;

#ifndef PI_COMPUTE_DIGITS
#define PI_COMPUTE_DIGITS 200
#endif
#ifndef PI_PRINT_DIGITS
#define PI_PRINT_DIGITS 20
#endif

/* ------------------------------------------------------------------ */
/* Software M extension (RV32I only).                                  */
/* These are the libgcc/compiler-rt entry points emitted by the C       */
/* compiler for '*', '/', '%' on rv32i.                                */
/* ------------------------------------------------------------------ */

__attribute__((noinline, used))
u32 __mulsi3(u32 a, u32 b) {
    u32 p = 0;
    int i;
    for (i = 0; i < 32; ++i) {
        if (b & 1u) p += a;
        a <<= 1;
        b >>= 1;
    }
    return p;
}

static u32 soft_udivmod(u32 n, u32 d, u32 *rem) {
    u32 q = 0, r = 0;
    int i;
    if (d == 0) {              /* division by zero: defined here as 0 */
        *rem = 0;
        return 0;
    }
    for (i = 31; i >= 0; --i) {
        r = (r << 1) | ((n >> i) & 1u);
        if (r >= d) {
            r -= d;
            q |= (1u << i);
        }
    }
    *rem = r;
    return q;
}

__attribute__((noinline, used))
u32 __udivsi3(u32 n, u32 d) {
    u32 r;
    return soft_udivmod(n, d, &r);
}

__attribute__((noinline, used))
u32 __umodsi3(u32 n, u32 d) {
    u32 r;
    soft_udivmod(n, d, &r);
    return r;
}

__attribute__((noinline, used))
s32 __divsi3(s32 a, s32 b) {
    u32 ua = (a < 0) ? (u32)(-a) : (u32)a;
    u32 ub = (b < 0) ? (u32)(-b) : (u32)b;
    u32 q = __udivsi3(ua, ub);
    return ((a < 0) ^ (b < 0)) ? -(s32)q : (s32)q;
}

__attribute__((noinline, used))
s32 __modsi3(s32 a, s32 b) {
    u32 ua = (a < 0) ? (u32)(-a) : (u32)a;
    u32 ub = (b < 0) ? (u32)(-b) : (u32)b;
    u32 r = __umodsi3(ua, ub);
    return (a < 0) ? -(s32)r : (s32)r;
}

/* The freestanding C compiler may lower large fills/copies to these.
 * Provide them so -nostdlib links without libc. */
#ifndef PI_HOST_TEST
__attribute__((noinline, used))
void *memset(void *dst, int c, unsigned int n) {
    unsigned char *d = (unsigned char *)dst;
    while (n--) *d++ = (unsigned char)c;
    return dst;
}

__attribute__((noinline, used))
void *memcpy(void *dst, const void *src, unsigned int n) {
    unsigned char *d = (unsigned char *)dst;
    const unsigned char *s = (const unsigned char *)src;
    while (n--) *d++ = *s++;
    return dst;
}
#endif

/* ------------------------------------------------------------------ */
/* UART and cycle counter                                              */
/* ------------------------------------------------------------------ */

#ifdef PI_HOST_TEST
#include <stdio.h>
#define MTIME_HZ 1000000u
static void uart_putc(char c) { putchar(c); }
static void uart_puts(const char *s) { while (*s) putchar(*s++); }
static void uart_put_uint(unsigned int v) { printf("%u", v); }
static u32 read_cycle(void) {
    static u32 c = 0;
    c += 1000000u;
    return c;
}
static u32 read_mtime_lo(void) {
    static u32 t = 0;
    t += 5000000u;              /* pretend the benchmark took 5 s */
    return t;
}
#else
#include "mmio.h"

static u32 read_cycle(void) {
    u32 v;
    asm volatile("csrr %0, cycle" : "=r"(v) : : "memory");
    return v;
}
#endif

static void uart_put_3digits(u32 v) {
    uart_putc((char)('0' + ((v / 100u) % 10u)));
    uart_putc((char)('0' + ((v / 10u) % 10u)));
    uart_putc((char)('0' + (v % 10u)));
}

/* ------------------------------------------------------------------ */
/* pi spigot                                                           */
/* ------------------------------------------------------------------ */

static int out_index;
static int printed_frac;

static void emit_digit(int d) {
    /* The algorithm emits an initial 0 before the integer part 3. */
    if (out_index == 0) {
        ++out_index;
        return;
    }
    if (out_index == 1) {
        uart_putc((char)('0' + d));
        uart_putc('.');
    } else if (printed_frac < PI_PRINT_DIGITS) {
        uart_putc((char)('0' + d));
        ++printed_frac;
    }
    ++out_index;
}

int main(void) {
    enum { N = PI_COMPUTE_DIGITS };
    enum { LEN = 10 * N / 3 + 1 };
    volatile u32 a[LEN];       /* on the stack, not in .bss */
    int nines = 0;
    int predigit = 0;
    int i, j, q;
    u32 c0, c1, cycles, t0, t1, elapsed_us, ms, frac_us;

    out_index = 0;
    printed_frac = 0;
    for (i = 0; i < LEN; ++i) a[i] = 2u;

    c0 = read_cycle();
    t0 = read_mtime_lo();

    for (j = 0; j < N; ++j) {
        q = 0;
        for (i = LEN; i > 0; --i) {
            u32 x = 10u * a[i - 1] + __mulsi3((u32)q, (u32)i);
            u32 r;
            q = (int)soft_udivmod(x, (u32)(2 * i - 1), &r);
            a[i - 1] = r;
        }
        a[0] = (u32)(q % 10);
        q /= 10;

        if (q == 9) {
            ++nines;
        } else if (q == 10) {
            emit_digit(predigit + 1);
            while (nines-- > 0) emit_digit(0);
            predigit = 0;
            nines = 0;
        } else {
            emit_digit(predigit);
            predigit = q;
            while (nines-- > 0) emit_digit(9);
            nines = 0;
        }
    }
    emit_digit(predigit);

    c1 = read_cycle();
    t1 = read_mtime_lo();
    cycles = c1 - c0;
    elapsed_us = t1 - t0;       /* 1 MTime tick = 1 us */

    uart_puts("\ncomputed_digits = ");
    uart_put_uint((u32)PI_COMPUTE_DIGITS);
    uart_puts("\ntimebase_hz = ");
    uart_put_uint(MTIME_HZ);
    uart_puts("\ncycles = ");
    uart_put_uint(cycles);
    uart_puts("\nelapsed_us = ");
    uart_put_uint(elapsed_us);

    ms = elapsed_us / 1000u;
    frac_us = elapsed_us % 1000u;

    uart_puts("\nelapsed_ms = ");
    uart_put_uint(ms);
    uart_putc('.');
    uart_put_3digits(frac_us);
    uart_puts(" ms\n");

    return 0;
}
