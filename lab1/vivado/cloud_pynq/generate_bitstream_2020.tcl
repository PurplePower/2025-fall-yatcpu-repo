# Generate the cloud_pynq bitstream with Vivado 2020.1 and the 2018
# cg_fpga 1.6.3 IP.
#
# Usage:
#   cd lab1/vivado/cloud_pynq
#   vivado -mode batch -source generate_bitstream_2020.tcl

set script_dir [file normalize [file dirname [info script]]]

if {![info exists ::env(CG_FPGA_PROJECT_DIR)]} {
    set ::env(CG_FPGA_PROJECT_DIR) "cloud_pynq_2020"
}
set ::env(CG_FPGA_IP_REPO) \
    [file normalize [file join $script_dir "../../../online-fpga/cg_fpga_2018_1_6_3"]]
set ::env(CG_FPGA_IP_VLNV) "educg.net:user:cg_fpga:1.6.3"

source [file join $script_dir generate_bitstream.tcl]
