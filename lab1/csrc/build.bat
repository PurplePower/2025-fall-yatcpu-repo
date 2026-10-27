@echo off
REM Build lab1 programs on Windows + MSYS2 (MinGW64 environment).
REM Run this file from cmd.exe or PowerShell; it always operates in its own directory.
setlocal
pushd "%~dp0"

if exist build rmdir /Q /S build

cmake -S . -B build -G "MinGW Makefiles" -DCMAKE_TOOLCHAIN_FILE="%~dp0toolchain.cmake" .
if errorlevel 1 goto :error

cmake --build build --parallel
if errorlevel 1 goto :error

popd
exit /b 0

:error
popd
exit /b 1
