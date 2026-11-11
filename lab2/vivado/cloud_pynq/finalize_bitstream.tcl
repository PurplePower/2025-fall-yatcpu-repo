# Run after opening impl_1, with project_dir and bit set by the caller.
# Incremental routing can leave a small hold violation. Repair it physically;
# keep the original clock constraints and require both setup and hold to pass.
if {[llength [get_clocks -quiet clk_10m_design_1_clk_wiz_0_0]] != 1} {
    error "Missing CPU clock constraint"
}
set report_dir [file normalize "$project_dir/reports"]
file mkdir $report_dir
set hold_path [get_timing_paths -delay_type min -max_paths 1]
set repaired 0
if {[llength $hold_path] && [get_property SLACK $hold_path] < 0} {
    puts "Repairing post-route hold timing with physical optimization"
    phys_opt_design -hold_fix
    set repaired 1
    set hold_path [get_timing_paths -delay_type min -max_paths 1]
    if {[llength $hold_path] && [get_property SLACK $hold_path] < 0} {
        phys_opt_design -directive AggressiveHoldFix
    }
}
set setup_path [get_timing_paths -delay_type max -max_paths 1]
set hold_path [get_timing_paths -delay_type min -max_paths 1]
if {[llength $setup_path] == 0 || [llength $hold_path] == 0} {
    error "Missing timing paths; inspect constraints before using the bitstream"
}
if {[get_property SLACK $setup_path] < 0 || [get_property SLACK $hold_path] < 0} {
    report_timing_summary -delay_type min_max -report_unconstrained -file "$report_dir/timing_summary.rpt"
    error "Timing failed; inspect $report_dir/timing_summary.rpt"
}
if {$repaired} {
    write_checkpoint -force "$report_dir/final_routed.dcp"
    write_bitstream -force $bit
}
report_timing_summary -delay_type min_max -report_unconstrained -file "$report_dir/timing_summary.rpt"
report_drc -file "$report_dir/drc.rpt"
report_utilization -file "$report_dir/utilization.rpt"
report_clocks -file "$report_dir/clocks.rpt"
report_clock_interaction -file "$report_dir/clock_interaction.rpt"
check_timing -verbose -file "$report_dir/check_timing.rpt"
puts "Timing checks passed; reports: $report_dir"
