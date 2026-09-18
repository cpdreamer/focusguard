@echo off
rem Double-click launcher: avoids the PowerShell execution-policy error.
rem Optional args are passed through, e.g.  setup.cmd -Days 1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup.ps1" %*
