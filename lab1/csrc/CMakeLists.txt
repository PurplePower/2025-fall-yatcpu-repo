# Make CMake happy
cmake_minimum_required(VERSION 3.18)
project(yatcpu-programs C CXX ASM)

# Setting variables
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O0 -march=rv32i_zicsr -mabi=ilp32")
set(CMAKE_ASM_FLAGS "${CMAKE_ASM_FLAGS} -O0 -march=rv32i_zicsr -mabi=ilp32")
set(C_PROGRAMS tetris hello fibonacci quicksort paging tetris_mmu say_goodbye led_control_demo)
set(ASM_PROGRAMS mmio sb)
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

# The cloud_pynq program is built with -O2 so that uart_pace() and the
# MMIO polling loop keep their intended timing/behavior.
target_compile_options(say_goodbye PRIVATE -O2)

# Convenience target used by the top-level Makefile.
add_custom_target(cloud_pynq DEPENDS led_control_demo)

foreach(program IN LISTS ASM_PROGRAMS)
	add_executable(${program} ${program}.S)
	set_target_properties(${program} PROPERTIES LINK_DEPENDS ${LINKER_SCRIPT})
endforeach()

# Copy the .text and .data section to .asmbin files
foreach(program IN LISTS C_PROGRAMS ASM_PROGRAMS PROGRAMS)
	add_custom_command(
		TARGET ${program}
		POST_BUILD
		COMMAND ${CMAKE_OBJCOPY} ARGS ${OBJCOPY_ARGS} $<TARGET_FILE:${program}> ${CMAKE_SOURCE_DIR}/${DEST_DIR}/${program}.asmbin
	)
endforeach()

