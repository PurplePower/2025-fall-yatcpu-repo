OUTPUT_ARCH("riscv")
ENTRY(_start)

/* cloud_pynq uses the lab1 unified 32 KiB BRAM:
 *   0x0000_1000  .text
 *   0x0000_2000  .data / .rodata
 *   0x0000_7000  .bss
 *   0x0000_8000  initial stack top
 */
SECTIONS
{
    . = 0x00001000;
    .text : {
        *(.text.init)
        *(.text.startup)
        *(.text*)
    }

    . = ALIGN(4);
    .data : {
        *(.data*)
        *(.rodata*)
        *(.sdata*)
    }

    . = 0x00007000;
    .bss : {
        *(.bss*)
        *(COMMON)
    }

    _end = .;

    /DISCARD/ : {
        *(.comment)
        *(.riscv.attributes)
    }
}
