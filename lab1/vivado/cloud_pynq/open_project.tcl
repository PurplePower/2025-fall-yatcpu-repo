# Build/open the cloud_pynq Vivado 2022.1 project.
#
# Run from this directory, or source it from Vivado:
#   cd lab1/vivado/cloud_pynq
#   vivado -mode batch -source open_project.tcl

set script_dir [file normalize [file dirname [info script]]]
cd $script_dir

set project_dir  "cloud_pynq"
set project_name "cloud_pynq"
set part         "xc7z020clg400-1"
set board_part   "tul.com.tw:pynq-z2:part0:1.0"

set ip_repo [file normalize "../../../online-fpga/cg-fpga-vivado2022.1/ip_repo"]
set top_v   [file normalize "../../verilog/cloud_pynq/Top.v"]

if {![file exists $top_v]} {
    puts "ERROR: $top_v not found."
    puts "       Complete the lab1 CPU TODOs, then run:"
    puts "         cd lab1 && make cloud_pynq"
    exit 1
}

if {[file exists $project_dir/$project_name.xpr]} {
    open_project $project_dir/$project_name.xpr
} else {
    create_project $project_name $project_dir -part $part -force
    set_property BOARD_PART $board_part [current_project]
}

set_property ip_repo_paths [list $ip_repo] [current_project]
update_ip_catalog

add_files -norecurse [list $top_v]
update_compile_order -fileset sources_1

# Create the block design from scratch on first run.
if {[llength [get_files -quiet design_1.bd]] == 0} {
    create_bd_design "design_1"

    create_bd_cell -type ip -vlnv educg.net:user:cg_fpga:1.4 cg_fpga_0
    create_bd_cell -type module -reference Top Top_0

    # 100 MHz -> 50 MHz for Memory/UART/Seg/LED matrix/ROM. Top divides CPU by 4 again.
    create_bd_cell -type ip -vlnv xilinx.com:ip:clk_wiz:6.0 clk_wiz_0
    set_property -dict [list \
        CONFIG.PRIM_IN_FREQ {100.000} \
        CONFIG.CLKOUT1_REQUESTED_OUT_FREQ {50.000} \
        CONFIG.CLKOUT1_USED {true} \
        CONFIG.CLK_OUT1_PORT {clk_50m} \
        CONFIG.PRIM_SOURCE {Global_buffer} \
        CONFIG.USE_LOCKED {true} \
        CONFIG.USE_RESET {true} \
    ] [get_bd_cells clk_wiz_0]

    # clk_wiz reset is active high; cg_fpga.btn_rst is active low.
    create_bd_cell -type ip -vlnv xilinx.com:ip:util_vector_logic:2.0 clk_reset_inv
    set_property -dict [list CONFIG.C_OPERATION {not} CONFIG.C_SIZE {1}] [get_bd_cells clk_reset_inv]

    # Hold Top in reset until btn_rst is released and clk_wiz is locked.
    create_bd_cell -type ip -vlnv xilinx.com:ip:util_vector_logic:2.0 reset_and
    set_property -dict [list CONFIG.C_OPERATION {and} CONFIG.C_SIZE {1}] [get_bd_cells reset_and]

    # PS7 DDR / MIO are the only top-level board interfaces.
    make_bd_intf_pins_external [get_bd_intf_pins cg_fpga_0/DDR]
    make_bd_intf_pins_external [get_bd_intf_pins cg_fpga_0/FIXED_IO]

    # 100 MHz reference clock -> clk_wiz 50 MHz -> Top.
    connect_bd_net [get_bd_pins cg_fpga_0/clk_100M] [get_bd_pins clk_wiz_0/clk_in1]
    connect_bd_net [get_bd_pins clk_wiz_0/clk_50m] [get_bd_pins Top_0/clock]
    connect_bd_net [get_bd_pins cg_fpga_0/clk_100M] [get_bd_pins cg_fpga_0/vid_io_in_clk]

    connect_bd_net [get_bd_pins cg_fpga_0/btn_rst] [get_bd_pins clk_reset_inv/Op1]
    connect_bd_net [get_bd_pins clk_reset_inv/Res] [get_bd_pins clk_wiz_0/reset]

    connect_bd_net [get_bd_pins cg_fpga_0/btn_rst] [get_bd_pins reset_and/Op1]
    connect_bd_net [get_bd_pins clk_wiz_0/locked] [get_bd_pins reset_and/Op2]
    connect_bd_net [get_bd_pins reset_and/Res] [get_bd_pins Top_0/reset_n]

    # Input devices.
    connect_bd_net [get_bd_pins cg_fpga_0/btn]        [get_bd_pins Top_0/btn]
    connect_bd_net [get_bd_pins cg_fpga_0/gpio_sw_1]  [get_bd_pins Top_0/sw1]
    connect_bd_net [get_bd_pins cg_fpga_0/gpio_sw_2]  [get_bd_pins Top_0/sw2]

    # UART crossover: Uartlite TX -> CPU RX, CPU TX -> Uartlite RX.
    connect_bd_net [get_bd_pins cg_fpga_0/tx_0] [get_bd_pins Top_0/uart_rx]
    connect_bd_net [get_bd_pins cg_fpga_0/rx_0] [get_bd_pins Top_0/uart_tx]

    # Output devices.
    connect_bd_net [get_bd_pins Top_0/gpio_led]  [get_bd_pins cg_fpga_0/gpio_led]
    connect_bd_net [get_bd_pins Top_0/seg_cs]    [get_bd_pins cg_fpga_0/seg_cs]
    connect_bd_net [get_bd_pins Top_0/seg_data]  [get_bd_pins cg_fpga_0/seg_data]
    connect_bd_net [get_bd_pins Top_0/ledm_cs]   [get_bd_pins cg_fpga_0/ledm_cs]
    connect_bd_net [get_bd_pins Top_0/ledm_data] [get_bd_pins cg_fpga_0/ledm_data]
    connect_bd_net [get_bd_pins Top_0/audio]     [get_bd_pins cg_fpga_0/audio]

    # Video is reserved for a later phase; tie the data/timing inputs off.
    connect_bd_net [get_bd_pins Top_0/vid_active] [get_bd_pins cg_fpga_0/vid_active]
    connect_bd_net [get_bd_pins Top_0/vid_data]   [get_bd_pins cg_fpga_0/vid_data]
    connect_bd_net [get_bd_pins Top_0/vid_hblank] [get_bd_pins cg_fpga_0/vid_hblank]
    connect_bd_net [get_bd_pins Top_0/vid_hsync]  [get_bd_pins cg_fpga_0/vid_hsync]
    connect_bd_net [get_bd_pins Top_0/vid_vblank] [get_bd_pins cg_fpga_0/vid_vblank]
    connect_bd_net [get_bd_pins Top_0/vid_vsync]  [get_bd_pins cg_fpga_0/vid_vsync]

    validate_bd_design
    save_bd_design
}

generate_target all [get_files design_1.bd]

set wrapper [make_wrapper -files [get_files design_1.bd] -top]
add_files -norecurse $wrapper
set_property top design_1_wrapper [current_fileset]
update_compile_order -fileset sources_1

# Minimize build time for iterative bitstream generation.
if {[llength [get_runs -quiet synth_1]] > 0} {
    set_property strategy Flow_RuntimeOptimized [get_runs synth_1]
}
if {[llength [get_runs -quiet impl_1]] > 0} {
    set_property strategy Flow_RuntimeOptimized [get_runs impl_1]
}

puts "cloud_pynq project is ready: [file normalize $project_dir/$project_name.xpr]"
