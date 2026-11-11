
# setup variables
set project_dir "./riscv-z710-v2020"
set project_name "riscv-z710-v2020"


open_project $project_dir/$project_name.xpr

# Apply the clock constraints after the block-design module references are linked.
set z710_xdc [get_files -quiet z710.xdc]
if {[llength $z710_xdc] > 0} {
    set_property PROCESSING_ORDER LATE $z710_xdc
}

