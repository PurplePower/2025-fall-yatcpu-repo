OUTPUT_ARCH("riscv")
ENTRY(_start)

/* Unified lab1 memory layout for every board.
 *
 * All lab1 boards use a 32 KiB BRAM (Parameters.MemorySizeInBytes = 32768)
 * and load the program at Parameters.EntryAddress = 0x1000:
 *
 *   0x0000_0000  unused / low memory
 *   0x0000_1000  .text
 *   0x0000_2000  .data (page aligned to keep text/data separable for MMU labs)
 *   0x0000_5000  paging page table (used by paging.c / tetris_mmu.c)
 *   0x0000_6000  .bss
 *   0x0000_8000  initial stack top (stack grows down)
 */
SECTIONS
{
    . = 0x00001000;
    .text : {
        *(.text.init)
        *(.text.startup)
        *(.text*)
    }

    . = ALIGN(0x1000);
    .data : {
        *(.data*)
        *(.rodata*)
        *(.sdata*)
    }

    . = 0x00006000;
    __bss_start = .;
    .bss : {
        *(.bss*)
        *(COMMON)
    }
    . = ALIGN(4);
    __bss_end = .;

    _end = .;

    /* init.S loads this symbol into sp.  A board-specific build may override it
     * with --defsym if it has a different memory size. */
    PROVIDE(__stack_top = 0x00008000);

    /DISCARD/ : {
        *(.comment)
        *(.riscv.attributes)
    }
}
