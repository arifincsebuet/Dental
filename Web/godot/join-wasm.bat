@echo off
rem Joins the Godot 3D engine file (shipped in two parts). Double-click once after copying the folder.
cd /d "%~dp0"
copy /b /y index.wasm.part1 + index.wasm.part2 index.wasm
echo Done: index.wasm
pause
