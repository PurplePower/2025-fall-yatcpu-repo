# Copyright 2026 YatCPU contributors
#
# Minimal RV32I startup for the cloud_pynq demo.  It does not use CSR,
# interrupts or newlib; it only sets up the stack and calls main.

.section .text.init
.globl _start

_start:
    li sp, 0x8000
    call main
1:
    j 1b
