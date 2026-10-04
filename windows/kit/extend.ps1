# Push the unlock date later. Works as a standard user, no password, at any time before the
# current date is reached: the service (SYSTEM) only accepts a date later than the current one.
#   extend.cmd                        -> asks for the date
#   extend.cmd -Until 2026-10-02T18:00
#   extend.cmd -Days 14               -> 14 days from now
[CmdletBinding()]
param(
    [string]$Until = "",
    [int]$Days = 0
)
$ErrorActionPreference = 'Stop'
$Dir = Join-Path $env:ProgramFiles 'FocusGuard'
$ExtendDir = Join-Path $Dir 'extend'
$exe = Join-Path $Dir 'FocusGuardDns.exe'

function Done($code) { Write-Host ""; Read-Host "Enter to close" | Out-Null; exit $code }

if (-not (Test-Path $exe)) { Write-Host "FocusGuard is not installed." -ForegroundColor Red; Done 1 }
if (-not (Test-Path $ExtendDir)) {
    Write-Host "This installation is older than extend.cmd: run setup.cmd once (as $env:USERNAME it just updates the program files), then extend.cmd again." -ForegroundColor Red
    Done 1
}

Write-Host "Current state:"
& $exe status | ForEach-Object { Write-Host ("   " + $_) }
Write-Host ""

if (-not $Until -and $Days -gt 0) { $Until = (Get-Date).AddDays($Days).ToString('yyyy-MM-dd HH:mm') }
if (-not $Until) { $Until = Read-Host "New unlock date (e.g. 2026-10-02 18:00)" }
try { $local = [datetime]::Parse($Until, [Globalization.CultureInfo]::CurrentCulture) } catch { Write-Host "Cannot read the date '$Until' - use the form 2026-10-02 18:00." -ForegroundColor Red; Done 1 }
$utc = $local.ToUniversalTime()
Write-Host ("Requesting: locked until {0:dddd dd MMMM yyyy HH:mm}. This can never be undone or shortened." -f $local) -ForegroundColor Yellow
$answer = Read-Host "Type exactly  I COMMIT  to continue"
if ($answer -ne 'I COMMIT') { Write-Host "Cancelled, nothing changed."; Done 1 }

$result = Join-Path $ExtendDir 'result.txt'
Remove-Item $result -Force -ErrorAction SilentlyContinue
[IO.File]::WriteAllText((Join-Path $ExtendDir 'request.txt'), $utc.ToString('yyyy-MM-ddTHH:mm:ssZ'))

$deadline = (Get-Date).AddSeconds(60)
while (-not (Test-Path $result) -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 1 }
if (-not (Test-Path $result)) { Write-Host "No answer from the service after 60 s - is it running? Check $Dir\state\focusguard.log." -ForegroundColor Red; Done 1 }
$text = (Get-Content $result -Raw).Trim()
if ($text -like 'OK*') { Write-Host ("   ok  " + $text) -ForegroundColor Green } else { Write-Host ("   XX  " + $text) -ForegroundColor Red }
Write-Host ""
& $exe status | ForEach-Object { Write-Host ("   " + $_) }
Done 0
