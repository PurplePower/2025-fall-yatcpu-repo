# CPU, AXI, BRAM and peripherals use the 40 MHz clock from clk_wiz directly.
# The clock IP provides its constraint; no fabric-generated clock is needed.

# Keep the reset-release synchronizer stages together during placement.
set_property ASYNC_REG TRUE [get_cells -hierarchical -regexp {.*rstSync_reg\[[01]\]}]
