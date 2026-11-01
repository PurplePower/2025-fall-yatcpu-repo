OUTPUT_ARCH("riscv")
ENTRY(_start)

/* Unified lab2 memory layout for every board.
 *
 * All lab2 boards use a 32 KiB BRAM (Parameters.MemorySizeInBytes = 32768)
 * and load the program at Parameters.EntryAddress = 0x1000:
 *
 *   0x0000_0000  unused / low memory
 *   0x0000_1000  .text
 *   page aligned .data (page aligned to keep text/data separable for MMU labs)
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
        /* GCC places small const tables (e.g. lyric pointers) in .srodata. */
        *(.srodata*)
        *(.sdata*)
        /* InstructionROM consumes 32-bit words, including the final string bytes. */
        . = ALIGN(4);
    }

    ASSERT(. <= 0x5000, "text/data overlaps reserved page table at 0x5000")
    . = 0x00006000;
    __bss_start = .;
    .bss : {
        *(.bss*)
        *(.sbss*)
        *(COMMON)
    }
    . = ALIGN(4);
    __bss_end = .;

    _end = .;
    ASSERT(__bss_end <= 0x7000, "BSS leaves less than 4 KiB for stack")

    /* init.S loads this symbol into sp.  A board-specific build may override it
     * with --defsym if it has a different memory size. */
    PROVIDE(__stack_top = 0x00008000);

    /DISCARD/ : {
        *(.comment)
        *(.riscv.attributes)
    }
}
