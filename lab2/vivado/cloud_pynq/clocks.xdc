# The CPU and Timer share the registered /4 divider, not a combinational decode.
# Match the retained divider bit in the Top module-reference hierarchy.
set cpu_div_q [get_pins -hierarchical -regexp {.*CPU_clkdiv_reg\[1\]/Q}]
set cpu_div_c [get_pins -of_objects [get_cells -of_objects $cpu_div_q] -filter {REF_PIN_NAME == C}]
create_generated_clock -name cloud_cpu -source $cpu_div_c -edges {3 7 11} $cpu_div_q

# Keep the two reset-release synchronizer stages together during placement.
set_property ASYNC_REG TRUE [get_cells -hierarchical -regexp {.*(rstSync|cpuResetSync)_reg\[[01]\]}]
