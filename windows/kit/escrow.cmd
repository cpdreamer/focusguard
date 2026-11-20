@echo off
rem Phone reset-protection password escrow. Standard user, no admin password.
rem   escrow.cmd seal      -> seal the escrow account password (once per commitment)
rem   escrow.cmd request   -> the password appears 72 h later in "ESCROW - phone reset account.txt"
rem   escrow.cmd cancel    -> drop a pending request
set "FG=%ProgramFiles%\FocusGuard\FocusGuardDns.exe"
if not exist "%FG%" (echo FocusGuard is not installed. & pause & exit /b 1)
"%FG%" escrow %*
