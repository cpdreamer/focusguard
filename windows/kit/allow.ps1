# Ask for a site to be reachable (allow-only mode). Works as a standard user, no password.
# The service answers within seconds, but the site opens only at the start of the first daily
# window (state\window.txt, e.g. 06:00-08:00) that begins at least 8 h after the request - never
# sooner, and never if a block rule matches it. Asked at 19:00 -> tomorrow 06:00; asked at 23:00
# -> the morning after. Nothing else to do: it lands by itself.
#   allow.cmd                          -> asks for the name
#   allow.cmd moodle.univ-example.fr   -> one or more names
[CmdletBinding()]
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Names)
$ErrorActionPreference = 'Stop'
$Dir = Join-Path $env:ProgramFiles 'FocusGuard'
$ExtendDir = Join-Path $Dir 'extend'
$exe = Join-Path $Dir 'FocusGuardDns.exe'

function Done($code) { Write-Host ""; Read-Host "Enter to close" | Out-Null; exit $code }

if (-not (Test-Path $exe)) { Write-Host "FocusGuard is not installed." -ForegroundColor Red; Done 1 }
if (-not (Test-Path $ExtendDir)) { Write-Host "This installation is older than allow.cmd: run setup.cmd once, then allow.cmd again." -ForegroundColor Red; Done 1 }

if (-not $Names) {
    Write-Host "Which site should become reachable? Just the name, e.g.  moodle.univ-example.fr  (its subdomains come with it)."
    Write-Host "It opens at the next window start at least 8 h from now, not before. A site that a block rule matches is refused."
    $line = Read-Host "Site"
    $Names = $line -split '[\s,;]+' | Where-Object { $_ }
}
$Names = $Names | ForEach-Object { ($_ -replace '^[a-z]+://', '' -split '[/?#]')[0].Trim().ToLowerInvariant() } | Where-Object { $_ }
if (-not $Names) { Write-Host "Nothing to request."; Done 1 }

$result = Join-Path $ExtendDir 'allow-result.txt'
Remove-Item $result -Force -ErrorAction SilentlyContinue
[IO.File]::WriteAllLines((Join-Path $ExtendDir 'allow-request.txt'), [string[]]$Names)

$deadline = (Get-Date).AddSeconds(60)
while (-not (Test-Path $result) -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 1 }
if (-not (Test-Path $result)) { Write-Host "No answer from the service after 60 s - is it running? Check $Dir\state\focusguard.log." -ForegroundColor Red; Done 1 }
foreach ($text in Get-Content $result) {
    if ($text -like 'OK*') { Write-Host ("   ok  " + $text) -ForegroundColor Green } else { Write-Host ("   XX  " + $text) -ForegroundColor Red }
}
Write-Host ""
& $exe status | ForEach-Object { Write-Host ("   " + $_) }
Done 0
