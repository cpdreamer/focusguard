# Block a site for good. Works as a standard user, no password, in both filter modes; takes effect
# within seconds and can never be undone (the list only grows, block rules beat every allow).
#   block.cmd                      -> asks for the name
#   block.cmd somesite.example     -> one or more names
[CmdletBinding()]
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Names)
$ErrorActionPreference = 'Stop'
$Dir = Join-Path $env:ProgramFiles 'FocusGuard'
$ExtendDir = Join-Path $Dir 'extend'
$exe = Join-Path $Dir 'FocusGuardDns.exe'

function Done($code) { Write-Host ""; Read-Host "Enter to close" | Out-Null; exit $code }

if (-not (Test-Path $exe)) { Write-Host "FocusGuard is not installed." -ForegroundColor Red; Done 1 }
if (-not (Test-Path $ExtendDir)) { Write-Host "This installation is older than block.cmd: run setup.cmd once, then block.cmd again." -ForegroundColor Red; Done 1 }

if (-not $Names) {
    Write-Host "Which site should be blocked? Just the name, e.g.  somesite.example  (its subdomains go with it)."
    $line = Read-Host "Site"
    $Names = $line -split '[\s,;]+' | Where-Object { $_ }
}
$Names = $Names | ForEach-Object { ($_ -replace '^[a-z]+://', '' -split '[/?#]')[0].Trim().ToLowerInvariant() } | Where-Object { $_ }
if (-not $Names) { Write-Host "Nothing to block."; Done 1 }

Write-Host ("Blocking for good, on this PC, no way back: " + ($Names -join ', ')) -ForegroundColor Yellow
$answer = Read-Host "Type exactly  I COMMIT  to continue"
if ($answer -ne 'I COMMIT') { Write-Host "Cancelled, nothing changed."; Done 1 }

$result = Join-Path $ExtendDir 'block-result.txt'
Remove-Item $result -Force -ErrorAction SilentlyContinue
[IO.File]::WriteAllLines((Join-Path $ExtendDir 'block-request.txt'), [string[]]$Names)

$deadline = (Get-Date).AddSeconds(60)
while (-not (Test-Path $result) -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 1 }
if (-not (Test-Path $result)) { Write-Host "No answer from the service after 60 s - is it running? Check $Dir\state\focusguard.log." -ForegroundColor Red; Done 1 }
foreach ($text in Get-Content $result) {
    if ($text -like 'OK*') { Write-Host ("   ok  " + $text) -ForegroundColor Green } else { Write-Host ("   XX  " + $text) -ForegroundColor Red }
}
Done 0
