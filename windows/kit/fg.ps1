# Send directives to the service. Works as a standard user, no password. One directive per line:
#   block <site>        blocked for good (and every subdomain), effective within seconds
#   unallow <site>      removed from the allow list now; asking for it again goes through the window
#   browsers off        Chrome/Edge show "blocked" from now on
#   blockapp <x.exe>    that program shows "blocked" from now on, for good
#   cancel <site>       drop a waiting allow request
#   allow <site>        opens at the first window start >= 8 h from now (never if a block rule matches)
#   browsers on         same rule as allow
# There is no unblock, no unblockapp, no allowapp, no unlock: those do not exist until the date.
#   fg.cmd                       -> asks for the directives (empty line ends)
#   fg.cmd block somesite.example
#   fg.cmd browsers off
[CmdletBinding()]
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Words)
$ErrorActionPreference = 'Stop'
$Dir = Join-Path $env:ProgramFiles 'FocusGuard'
$ExtendDir = Join-Path $Dir 'extend'
$exe = Join-Path $Dir 'FocusGuardDns.exe'

function Done($code) { Write-Host ""; Read-Host "Enter to close" | Out-Null; exit $code }

if (-not (Test-Path $exe)) { Write-Host "FocusGuard is not installed." -ForegroundColor Red; Done 1 }
if (-not (Test-Path $ExtendDir)) { Write-Host "This installation is older than fg.cmd: run setup.cmd once, then fg.cmd again." -ForegroundColor Red; Done 1 }

$lines = @()
if ($Words) { $lines = @(($Words -join ' ')) }
else {
    Write-Host "Directives, one per line (block X / unallow X / browsers off / blockapp X.exe / cancel X / allow X / browsers on). Empty line to send."
    while ($true) { $l = Read-Host ">"; if (-not $l.Trim()) { break }; $lines += $l.Trim() }
}
if (-not $lines) { Write-Host "Nothing to send."; Done 1 }

$tightening = @($lines | Where-Object { $_ -match '^(block|unallow|blockapp|cancel)\s|^browsers\s+off$' })
if ($tightening) {
    Write-Host ("Permanent until the unlock date, no way back: " + ($tightening -join ' | ')) -ForegroundColor Yellow
    $answer = Read-Host "Type exactly  I COMMIT  to continue"
    if ($answer -ne 'I COMMIT') { Write-Host "Cancelled, nothing sent."; Done 1 }
}

$result = Join-Path $ExtendDir 'fg-result.txt'
Remove-Item $result -Force -ErrorAction SilentlyContinue
[IO.File]::WriteAllLines((Join-Path $ExtendDir 'fg-request.txt'), [string[]]$lines)

$deadline = (Get-Date).AddSeconds(60)
while (-not (Test-Path $result) -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 1 }
if (-not (Test-Path $result)) { Write-Host "No answer from the service after 60 s - is it running? Check $Dir\state\focusguard.log." -ForegroundColor Red; Done 1 }
foreach ($text in Get-Content $result) {
    if ($text -like 'OK*') { Write-Host ("   ok  " + $text) -ForegroundColor Green } else { Write-Host ("   XX  " + $text) -ForegroundColor Red }
}
Write-Host ""
& $exe status | ForEach-Object { Write-Host ("   " + $_) }
Done 0
