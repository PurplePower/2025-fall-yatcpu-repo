## This file is for SYSU computer organization courses.
## used for Zynq7010 fpga revision 1.3 2024/02



# clock, 50 MHz
set_property IOSTANDARD LVCMOS33 [get_ports io_clock];
set_property PACKAGE_PIN U18 [get_ports io_clock];
create_clock -period 20.000 -name io_clock -waveform {0.000 10.000} -add [get_ports io_clock];

# CPU and Timer use the registered high bit of a two-bit /4 counter.
# Describe its rising/falling/rising edges relative to the divider input.
set cpu_div_q [get_pins -hierarchical -regexp {.*CPU_clkdiv_reg\[1\]/Q}]
set cpu_div_c [get_pins -of_objects [get_cells -of_objects $cpu_div_q] -filter {REF_PIN_NAME == C}]
create_generated_clock -name cpu_clk -source $cpu_div_c -edges {3 7 11} $cpu_div_q

# LEDs
set_property IOSTANDARD LVCMOS33 [get_ports io_led];
set_property PACKAGE_PIN J16 [get_ports io_led];   # PL_LED0
#set_property -dict {PACKAGE_PIN M14 IOSTANDARD LVCMOS33} [get_ports io_led_1];
#set_property -dict {PACKAGE_PIN M15 IOSTANDARD LVCMOS33} [get_ports io_led_2];

# switches
set_property -dict {PACKAGE_PIN V17 IOSTANDARD LVCMOS33} [get_ports clk_enable]; # PL_SW1
set_property -dict {PACKAGE_PIN Y16 IOSTANDARD LVCMOS33} [get_ports io_reset];  # PL_SW2

# do NOT assign reset to ANY BUTTON in Vivado 2020, its Utility Vector Logic Not cause unstable TX output
# while this isn't present in Vivado 2022



# UART ports
# rxd assigned to constant 1
set_property IOSTANDARD LVCMOS33 [get_ports UART0_TX_0]
set_property PACKAGE_PIN J15 [get_ports UART0_TX_0]
set_property IOSTANDARD LVCMOS33 [get_ports UART0_RX_0]
set_property PACKAGE_PIN T19 [get_ports UART0_RX_0]
