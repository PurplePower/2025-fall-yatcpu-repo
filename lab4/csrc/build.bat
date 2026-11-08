@echo off
setlocal
pushd "%~dp0"
cmake -S . -B build_cloud_pynq -G "MinGW Makefiles" -DCMAKE_TOOLCHAIN_FILE="%~dp0toolchain.cmake"
if errorlevel 1 goto :error
if "%~1"=="" (cmake --build build_cloud_pynq --parallel) else (cmake --build build_cloud_pynq --target %* --parallel)
if errorlevel 1 goto :error
popd
exit /b 0
:error
popd
exit /b 1
