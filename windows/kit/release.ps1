# Runs as SYSTEM from the scheduled task "FocusGuardRelease" at the unlock date.
# Independent second path to the sealed admin password, in case the service itself is broken:
# same DPAPI (SYSTEM user scope) the service used to seal it.
$Dir   = Join-Path $env:ProgramFiles 'FocusGuard'
$State = Join-Path $Dir 'state'
$out   = Join-Path $Dir 'UNLOCKED - admin password.txt'
if (Test-Path $out) { exit 0 }
$unlockFile = Join-Path $State 'unlock-date.txt'
$sealed     = Join-Path $State 'admin.sealed'
$plainFile  = Join-Path $State 'admin.plain'
if (-not (Test-Path $unlockFile)) { exit 1 }
$due = [datetime]::Parse((Get-Content $unlockFile -Raw).Trim(), [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]'AdjustToUniversal,AssumeUniversal')
if ([datetime]::UtcNow -lt $due) { exit 2 }
Add-Type -AssemblyName System.Security
if (Test-Path $sealed) {
    $bytes = [IO.File]::ReadAllBytes($sealed)
    $plain = [Text.Encoding]::UTF8.GetString([Security.Cryptography.ProtectedData]::Unprotect($bytes, $null, 'CurrentUser'))
} elseif (Test-Path $plainFile) {
    $plain = (Get-Content $plainFile -Raw).Trim()   # service never ran: nothing was sealed
} else { exit 3 }
$text = @"
FocusGuard commitment ended on $($due.ToLocalTime().ToString('yyyy-MM-dd HH:mm')).

Administrator account: FocusGuardAdmin
Password: $plain

To remove the blocker: sign out, sign in as FocusGuardAdmin, right-click remove.ps1 in $Dir -> Run with PowerShell.
To keep it: do nothing. Blocking stays on until you remove it.
"@
[IO.File]::WriteAllText($out, $text)
exit 0
