# CPU (10 MHz) and memory (40 MHz) are two outputs of the same clk_wiz.
# CPU stores are qualified by a synchronized CPU_tick, so data launched in
# either domain has a full CPU period (four memory cycles) to settle.
set mem_clk [get_clocks clk_40m_design_1_clk_wiz_0_0]
set cpu_clk [get_clocks clk_10m_design_1_clk_wiz_0_0]
# Memory read data launched at the first memory edge after the CPU address
# changes is captured by the CPU.  A setup value of 2 allows one full CPU
# cycle, and the matching hold value (the 4:1 ratio) puts the hold check on
# the actual CPU capture edge.
set_multicycle_path 2 -setup -from $mem_clk -to $cpu_clk
set_multicycle_path 4 -hold  -from $mem_clk -to $cpu_clk
# CPU store data must be stable at the 4th memory edge, where CPU_tick
# produces the single write pulse.
set_multicycle_path 4 -setup -from $cpu_clk -to $mem_clk
set_multicycle_path 3 -hold  -from $cpu_clk -to $mem_clk

# Keep the reset-release and CPU-tick synchronizer stages together.
set_property ASYNC_REG TRUE [get_cells -hierarchical -regexp {.*(rstSync|cpuResetSync)_reg\[[01]\]}]
set_property ASYNC_REG TRUE [get_cells -hierarchical -regexp {.*CPU_tick_sync_reg\[[0-2]\]}]
