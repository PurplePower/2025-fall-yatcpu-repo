# Generate the cloud_pynq bitstream.
#
# Usage (Vivado 2022.1 + cg_fpga 1.4):
#   cd lab1/vivado/cloud_pynq
#   vivado -mode batch -source generate_bitstream.tcl
#
# Usage (Vivado 2020.1 + cg_fpga 1.6.3):
#   vivado -mode batch -source generate_bitstream_2020.tcl

set script_dir [file normalize [file dirname [info script]]]
cd $script_dir
source [file join $script_dir open_project.tcl]

# Use the shortest-runtime strategies.  These trade some QoR for much faster
# synthesis/implementation, which is appropriate for iterative bitstream builds.
set_property strategy Flow_RuntimeOptimized [get_runs synth_1]
set_property strategy Flow_RuntimeOptimized [get_runs impl_1]

# Refresh the module-reference IP so the regenerated Top.v (and the CPU
# clock divider change) are picked up by synthesis.
update_module_reference design_1_Top_0_0

set jobs 8

reset_run synth_1
launch_runs synth_1 -jobs $jobs
wait_on_run synth_1
if {[get_property PROGRESS [get_runs synth_1]] != "100%"} {
    puts "ERROR: synthesis failed."
    exit 1
}

reset_run impl_1
launch_runs impl_1 -to_step write_bitstream -jobs $jobs
wait_on_run impl_1
if {[get_property PROGRESS [get_runs impl_1]] != "100%"} {
    puts "ERROR: implementation failed."
    exit 1
}

set bit [file normalize "$project_dir/$project_name.runs/impl_1/design_1_wrapper.bit"]
if {![file exists $bit]} {
    puts "ERROR: bitstream not found: $bit"
    exit 1
}

puts "Bitstream: $bit"
