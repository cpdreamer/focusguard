@echo off
rem Double-click launcher: avoids the PowerShell execution-policy error. Uses -Command (not -File):
rem under the SRP lock PowerShell runs in ConstrainedLanguage and -File refuses to dot-source a trusted script.
rem Optional args are passed through, e.g.  extend.cmd -Until "2026-10-02 18:00"
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "& '%~dp0extend.ps1' %*"
