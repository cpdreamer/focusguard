@echo off
rem Double-click launcher: avoids the PowerShell execution-policy error.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0remove.ps1" %*
