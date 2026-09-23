@echo off
rem Double-click launcher: avoids the PowerShell execution-policy error.
rem Optional args: the names, e.g.  allow.cmd moodle.univ-example.fr
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0allow.ps1" %*
