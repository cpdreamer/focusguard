<#
.SYNOPSIS
  Removes FocusGuard from this PC. Needs an administrator: before the unlock date that is only
  FocusGuardAdmin, whose password is released in "UNLOCKED - admin password.txt" at the date.

  Undoes everything setup.ps1 and the service did: service, scheduled task, firewall rules, DNS
  settings, browser policies, blocked executables, recovery menu, administrator rights.

.PARAMETER KeepAdminAccount
  Keep the FocusGuardAdmin account (default: it is deleted once the original admins are restored).
#>
[CmdletBinding()]
param([switch]$KeepAdminAccount)

$ErrorActionPreference = 'Continue'

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    $argList = @('-NoExit', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $MyInvocation.MyCommand.Path + '"'))
    if ($KeepAdminAccount) { $argList += '-KeepAdminAccount' }
    try { Start-Process powershell.exe -Verb RunAs -ArgumentList $argList }
    catch { Write-Host "Administrator rights are required: before the unlock date only FocusGuardAdmin has them." -ForegroundColor Red; Read-Host "Enter to close" }
    exit
}

$ServiceName = 'FocusGuardDns'
$AdminUser   = 'FocusGuardAdmin'
$Dir         = Join-Path $env:ProgramFiles 'FocusGuard'
$State       = Join-Path $Dir 'state'
function Step($m) { Write-Host ""; Write-Host ("== " + $m) -ForegroundColor Cyan }
function Ok($m)   { Write-Host ("   ok  " + $m) -ForegroundColor Green }
function Warn($m) { Write-Host ("   !!  " + $m) -ForegroundColor Yellow }

Step "Service and scheduled task"
Stop-Service $ServiceName -Force -ErrorAction SilentlyContinue
& sc.exe delete $ServiceName | Out-Null
Unregister-ScheduledTask -TaskName 'FocusGuardRelease' -Confirm:$false -ErrorAction SilentlyContinue
Ok "service deleted, release task removed"

Step "Firewall"
Get-NetFirewallRule -DisplayName 'FocusGuard *' -ErrorAction SilentlyContinue | Remove-NetFirewallRule -ErrorAction SilentlyContinue
Ok "FocusGuard rules removed"

Step "DNS back to automatic (DHCP)"
Get-NetAdapter -ErrorAction SilentlyContinue | ForEach-Object { Set-DnsClientServerAddress -InterfaceIndex $_.ifIndex -ResetServerAddresses -ErrorAction SilentlyContinue }
Set-DnsClientGlobalSetting -UseDevolution $true -ErrorAction SilentlyContinue
& ipconfig.exe /flushdns | Out-Null
Ok "adapters reset"

Step "Browser and Windows policies"
foreach ($k in 'HKLM:\SOFTWARE\Policies\Google\Chrome', 'HKLM:\SOFTWARE\Policies\Microsoft\Edge', 'HKLM:\SOFTWARE\Policies\Chromium', 'HKLM:\SOFTWARE\Policies\BraveSoftware') {
    Remove-Item $k -Recurse -Force -ErrorAction SilentlyContinue
}
foreach ($kv in @(
    @('HKLM:\SOFTWARE\Policies\Microsoft\Windows\CurrentVersion\Internet Settings', 'ProxySettingsPerUser'),
    @('HKLM:\SOFTWARE\Policies\Microsoft\Windows\CurrentVersion\Internet Settings', 'ProxyEnable'),
    @('HKLM:\SOFTWARE\Policies\Microsoft\Internet Explorer\Control Panel', 'Proxy'),
    @('HKLM:\SOFTWARE\Policies\Microsoft\Windows\CloudContent', 'DisableWindowsConsumerFeatures'),
    @('HKLM:\SOFTWARE\Policies\Microsoft\Windows\CloudContent', 'DisableWindowsSpotlightFeatures'),
    @('HKLM:\SOFTWARE\Policies\Microsoft\Windows\Windows Feeds', 'EnableFeeds'),
    @('HKLM:\SOFTWARE\Policies\Microsoft\Dsh', 'AllowNewsAndInterests'),
    @('HKLM:\SOFTWARE\Policies\Microsoft\Windows\GameDVR', 'AllowGameDVR'))) {
    Remove-ItemProperty -Path $kv[0] -Name $kv[1] -ErrorAction SilentlyContinue
}
Ok "policies removed (Chrome/Edge back to defaults after restart)"

Step "Blocked programs (Image File Execution Options)"
$ifeo = 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Image File Execution Options'
$n = 0
Get-ChildItem $ifeo -ErrorAction SilentlyContinue | ForEach-Object {
    $dbg = (Get-ItemProperty $_.PSPath -Name Debugger -ErrorAction SilentlyContinue).Debugger
    if ($dbg -and $dbg -like '*\FocusGuard\blocked.exe*') {
        if (@($_.GetValueNames()).Count -le 1 -and @($_.GetSubKeyNames()).Count -eq 0) { Remove-Item $_.PSPath -Force } else { Remove-ItemProperty $_.PSPath -Name Debugger }
        $n++
    }
}
Ok "$n entries removed"

Step "Recovery menu"
& reagentc.exe /enable | Out-Null
& bcdedit.exe /set '{default}' recoveryenabled Yes | Out-Null
& bcdedit.exe /set '{default}' bootstatuspolicy DisplayAllFailures | Out-Null
& bcdedit.exe /deletevalue '{bootmgr}' displaybootmenu | Out-Null
Ok "Windows Recovery Environment re-enabled"

Step "Administrator rights"
$orig = Join-Path $State 'original-admins.txt'
$restored = 0
if (Test-Path $orig) {
    foreach ($line in Get-Content $orig) {
        $sid = ($line -split ' ', 2)[0]
        if (-not $sid) { continue }
        if ($sid -like '*-500') { Enable-LocalUser -SID $sid -ErrorAction SilentlyContinue; continue }
        try { Add-LocalGroupMember -SID 'S-1-5-32-544' -Member $sid -ErrorAction Stop; $restored++ }
        catch { if ($_.Exception.Message -notmatch 'already') { Warn "could not restore $line : $($_.Exception.Message)" } else { $restored++ } }
    }
    Ok "$restored account(s) are administrators again"
} else {
    Warn "original-admins.txt missing: make your account administrator by hand (Paramètres > Comptes > Famille et autres utilisateurs)"
}
if (-not $KeepAdminAccount) {
    $me = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name.Split('\')[-1]
    if ($me -ieq $AdminUser) {
        Warn "you are signed in as $AdminUser, so the account is kept for now. Sign in with your own account and run: Remove-LocalUser $AdminUser"
    } elseif ($restored -gt 0) {
        Remove-LocalUser -Name $AdminUser -ErrorAction SilentlyContinue
        Ok "$AdminUser deleted"
    } else {
        Warn "$AdminUser kept (no other administrator restored)"
    }
}

Step "Files"
Remove-Item $Dir -Recurse -Force -ErrorAction SilentlyContinue
if (Test-Path $Dir) { Warn "$Dir could not be fully deleted (files in use); delete it after a restart" } else { Ok "$Dir deleted" }

Write-Host ""
Write-Host "FocusGuard removed. Restart the PC to finish (browser policies and DNS)." -ForegroundColor Green
Read-Host "Enter to close" | Out-Null
