@echo off
rem Double-click launcher: avoids the PowerShell execution-policy error.
rem Optional args: the names, e.g.  block.cmd somesite.example
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "& '%~dp0block.ps1' %*"
