@echo off
rem Double-click launcher: avoids the PowerShell execution-policy error.
rem Optional args: one directive, e.g.  fg.cmd block somesite.example   or   fg.cmd browsers off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0fg.ps1" %*
