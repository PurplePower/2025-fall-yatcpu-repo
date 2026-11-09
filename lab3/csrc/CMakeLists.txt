# Make CMake happy
cmake_minimum_required(VERSION 3.18)
project(yatcpu-programs C CXX ASM)

# Setting variables
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O0 -march=rv32i_zicsr -mabi=ilp32")
set(CMAKE_ASM_FLAGS "${CMAKE_ASM_FLAGS} -O0 -march=rv32i_zicsr -mabi=ilp32")
set(C_PROGRAMS tetris hello fibonacci quicksort paging tetris_mmu simpletest say_goodbye led_control_demo pi)
# pi.c uses the 1 MHz MTime MMIO counter, so it does not depend on the CPU clock.
set(PI_COMPUTE_DIGITS 200 CACHE STRING "Number of pi digits computed by pi.c")
set(PI_PRINT_DIGITS 20 CACHE STRING "Number of pi decimals printed by pi.c")
set(ASM_PROGRAMS mmio sb hazard)
set(LINKER_SCRIPT ${CMAKE_SOURCE_DIR}/link.lds)
set(LINKER_FLAGS -T ${LINKER_SCRIPT})
set(OBJCOPY_ARGS -O binary -j .text -j .data)
if(NOT DEST_DIR)
	set(DEST_DIR "../src/main/resources")
endif()

# Let CMake know that there exists header files
include_directories("${CMAKE_SOURCE_DIR}")

add_library(prelude init.S)
set_target_properties(prelude PROPERTIES LINK_DEPENDS ${LINKER_SCRIPT})

# Let's build our executables
foreach(program IN LISTS C_PROGRAMS)
	add_executable(${program} ${program}.c)
	set_target_properties(${program} PROPERTIES LINK_DEPENDS ${LINKER_SCRIPT})
	target_link_libraries(${program} prelude ${LINKER_FLAGS})
endforeach()

# Optimize the cloud demos' foreground MMIO polling loops.
target_compile_options(say_goodbye PRIVATE -O2)
target_compile_options(led_control_demo PRIVATE -O2)
# pi.c is the compute benchmark; keep its software M-extension visible
# (no builtins) and optimize the integer loop.
target_compile_options(pi PRIVATE -O2 -fno-builtin -fno-stack-protector)
target_compile_definitions(pi PRIVATE
	PI_COMPUTE_DIGITS=${PI_COMPUTE_DIGITS}
	PI_PRINT_DIGITS=${PI_PRINT_DIGITS})
# Fast functional simulation uses the same source and startup, a shorter period only.
add_executable(led_control_demo_fast EXCLUDE_FROM_ALL led_control_demo.c)
target_compile_options(led_control_demo_fast PRIVATE -O2)
target_compile_definitions(led_control_demo_fast PRIVATE DEMO_TIMER_TICKS=20000u)
target_link_libraries(led_control_demo_fast prelude ${LINKER_FLAGS})
set_target_properties(led_control_demo_fast PROPERTIES LINK_DEPENDS ${LINKER_SCRIPT})
add_executable(trap_registers EXCLUDE_FROM_ALL trap_registers.S)
target_link_libraries(trap_registers prelude ${LINKER_FLAGS})
set_target_properties(trap_registers PROPERTIES LINK_DEPENDS ${LINKER_SCRIPT})
add_custom_target(test_programs DEPENDS led_control_demo_fast trap_registers)

# Convenience target used by the top-level Makefile.
add_custom_target(cloud_pynq DEPENDS led_control_demo)

foreach(program IN LISTS ASM_PROGRAMS)
	add_executable(${program} ${program}.S)
	set_target_properties(${program} PROPERTIES LINK_DEPENDS ${LINKER_SCRIPT})
endforeach()

# Copy the .text and .data section to .asmbin files
set(ALL_BINARIES ${C_PROGRAMS} ${ASM_PROGRAMS} led_control_demo_fast trap_registers)
foreach(program IN LISTS ALL_BINARIES)
	add_custom_command(
		TARGET ${program}
		POST_BUILD
		COMMAND ${CMAKE_OBJCOPY} ARGS ${OBJCOPY_ARGS} $<TARGET_FILE:${program}> ${CMAKE_SOURCE_DIR}/${DEST_DIR}/${program}.asmbin
	)
endforeach()
