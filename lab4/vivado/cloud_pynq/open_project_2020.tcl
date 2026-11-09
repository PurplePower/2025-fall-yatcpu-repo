# Build/open the cloud_pynq project with Vivado 2020.1 and the 2018
# cg_fpga 1.6.3 IP (v_vid_in_axi4s:4.0).
#
# The 2018 IP has two extra interfaces compared with 1.4:
#   - PS7_MAXI: unused AXI4-Lite master; leave it unconnected so Vivado ties
#     it off.  Do not expose it as top-level pins.
#   - aresetn: unused reset output; leave it unconnected.

set script_dir [file normalize [file dirname [info script]]]

if {![info exists ::env(CG_FPGA_PROJECT_DIR)]} {
    set ::env(CG_FPGA_PROJECT_DIR) "cloud_pynq_2020"
}
set ::env(CG_FPGA_IP_REPO) \
    [file normalize [file join $script_dir "../../../online-fpga/cg_fpga_2018_1_6_3"]]
set ::env(CG_FPGA_IP_VLNV) "educg.net:user:cg_fpga:1.6.3"

source [file join $script_dir open_project.tcl]
