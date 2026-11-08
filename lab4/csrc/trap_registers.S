# Regression fixture: even an assembly ISR may clobber registers; the common
# trap entry must restore both caller-saved and callee-saved registers.
.section .text
.globl main
main:
  call enable_interrupt
  li s0, 0x11223344
  li tp, 0x55667788
  li t2, 0x12345678
  li a5, 0x76543210
  li s11, 0x33445566
  li t6, 0x778899aa
  li t0, 0xdeadbeef
  sw t0, 4(zero)
1:
  j 1b

.globl trap_handler
trap_handler:
  li s0, 0
  li tp, 0
  li t2, 0
  li a5, 0
  li s11, 0
  li t6, 0
  li t0, 0x2022
  sw t0, 8(zero)
  ret
