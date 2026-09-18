@echo off
rem Double-click launcher: avoids the PowerShell execution-policy error.
rem Optional args are passed through, e.g.  extend.cmd -Until "2026-10-02 18:00"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0extend.ps1" %*
