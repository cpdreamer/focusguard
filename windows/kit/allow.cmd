@echo off
rem Double-click launcher. Calls the installed program directly, not PowerShell: under the lock
rem PowerShell runs restricted and refuses the helper scripts. Optional args pass through, e.g.
rem   allow.cmd moodle.univ-example.fr
set "FG=%ProgramFiles%\FocusGuard\FocusGuardDns.exe"
if not exist "%FG%" (echo FocusGuard is not installed. & pause & exit /b 1)
"%FG%" allow %*
