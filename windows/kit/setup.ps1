<#
.SYNOPSIS
  FocusGuard for Windows - installs the blocker and locks the machine until the unlock date.

.DESCRIPTION
  What it does, in order (every step is re-applied every minute by the service afterwards):
    1. Checks Windows edition, .NET 4.x, free port 53, kit files.
    2. Copies the kit to %ProgramFiles%\FocusGuard.
    3. Creates the local administrator "FocusGuardAdmin" with a random password nobody sees; the
       password is left in a SYSTEM-only file that the service seals with DPAPI on first start and
       reveals in "%ProgramFiles%\FocusGuard\UNLOCKED - admin password.txt" once the date is reached.
    4. Installs and starts the FocusGuardDns service (local DNS filter forwarding over DoH) and
       checks it answers. Default mode is allow-only: a name resolves only if allowlist.txt (or a
       granted allow.cmd request) lists it AND no block rule matches; everything else, known or not
       yet invented, is 0.0.0.0. -Mode blocklist gives the phone's behaviour instead (block lists only).
    5. Points every adapter at 127.0.0.1, blocks outbound DNS/DoT/public-DoH in the firewall, applies
       Chrome/Edge/Brave/Chromium policies, blocks other browsers / VPNs / torrent / game launchers
       by executable name, disables the Windows Recovery menu.
    6. Removes every account except FocusGuardAdmin from Administrators (you become a standard user).

.PARAMETER Days
  Length of the commitment from now. Default 14.
.PARAMETER Until
  Alternative: explicit local end date/time, e.g. "2026-10-02 18:00".
.PARAMETER Mode
  allowonly (default) or blocklist. Fixed for the whole commitment; a later setup run may only
  tighten it (blocklist -> allowonly), never the reverse.
.PARAMETER Yes
  Skip the typed confirmation.

.EXAMPLE
  Double-click setup.cmd (accept the admin prompt), or from a PowerShell in the kit folder:
      powershell -ExecutionPolicy Bypass -File .\setup.ps1 -Days 14
#>
[CmdletBinding()]
param(
    [int]$Days = 14,
    [string]$Until = "",
    [ValidateSet('allowonly', 'blocklist')][string]$Mode = 'allowonly',
    [switch]$Yes
)

$ErrorActionPreference = 'Stop'

# Self-elevate (right-click > Exécuter avec PowerShell does not ask for admin by itself).
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    $argList = @('-NoExit', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $MyInvocation.MyCommand.Path + '"'))
    if ($PSBoundParameters.ContainsKey('Days')) { $argList += @('-Days', $Days) }
    if ($Until) { $argList += @('-Until', ('"' + $Until + '"')) }
    if ($PSBoundParameters.ContainsKey('Mode')) { $argList += @('-Mode', $Mode) }
    if ($Yes) { $argList += '-Yes' }
    try { Start-Process powershell.exe -Verb RunAs -ArgumentList $argList } catch { Write-Host "Administrator rights are required (the prompt was refused)." -ForegroundColor Red; Read-Host "Enter to close" }
    exit
}

$ServiceName = 'FocusGuardDns'
$AdminUser   = 'FocusGuardAdmin'
$Dir         = Join-Path $env:ProgramFiles 'FocusGuard'
$State       = Join-Path $Dir 'state'
$Kit         = Split-Path -Parent $MyInvocation.MyCommand.Path
$ExtendDir   = Join-Path $Dir 'extend'
$KitFiles    = @('FocusGuardDns.exe', 'blocked.exe', 'FocusGuardDns.cs', 'FocusGuardBlocked.cs', 'lists.txt', 'allowlist.txt', 'remove.ps1', 'remove.cmd', 'release.ps1',
                 'extend.ps1', 'extend.cmd', 'allow.ps1', 'allow.cmd', 'block.ps1', 'block.cmd', 'README.md')
$ModeFile    = Join-Path $State 'mode.txt'

# Mode is part of the commitment: written once, later runs may only go blocklist -> allowonly.
function Write-Mode {
    $current = if (Test-Path $ModeFile) { (Get-Content $ModeFile -Raw).Trim() } else { $null }
    if ($current -eq 'allowonly' -and $Mode -ne 'allowonly') { Warn "installed mode is allow-only; -Mode blocklist would loosen the commitment and is ignored"; return }
    if ($current -ne $Mode) { [IO.File]::WriteAllText($ModeFile, $Mode); Ok "filter mode: $Mode" } else { Ok "filter mode: $Mode (unchanged)" }
}

function Step($msg) { Write-Host ""; Write-Host ("== " + $msg) -ForegroundColor Cyan }
function Ok($msg)   { Write-Host ("   ok  " + $msg) -ForegroundColor Green }
function Warn($msg) { Write-Host ("   !!  " + $msg) -ForegroundColor Yellow }
function Fail($msg) { Write-Host ("   XX  " + $msg) -ForegroundColor Red; Write-Host ""; Write-Host "Nothing was changed that cannot be undone; fix the point above and run setup.ps1 again."; if (-not $Yes) { Read-Host "Enter to close" | Out-Null }; exit 1 }

function New-RandomPassword([int]$len = 24) {
    # letters/digits only: safe to type, no shell-quoting surprises when it is finally revealed
    $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
    $bytes = New-Object byte[] ($len * 4)
    $rng = [System.Security.Cryptography.RNGCryptoServiceProvider]::new()
    $rng.GetBytes($bytes)
    $sb = New-Object System.Text.StringBuilder
    for ($i = 0; $i -lt $len; $i++) {
        $n = [BitConverter]::ToUInt32($bytes, $i * 4)
        [void]$sb.Append($alphabet[$n % $alphabet.Length])
    }
    $sb.ToString()
}

# Members of Administrators as (Sid, Name) pairs. Get-LocalGroupMember throws on some machines
# (orphaned or Azure AD SIDs), so fall back to ADSI, which never does.
function Get-AdminMembers {
    try {
        return @(Get-LocalGroupMember -SID 'S-1-5-32-544' -ErrorAction Stop | ForEach-Object { [pscustomobject]@{ Sid = $_.SID.Value; Name = $_.Name } })
    } catch {
        $g = [ADSI]'WinNT://./Administrators,group'
        return @($g.Invoke('Members') | ForEach-Object {
            $bytes = $_.GetType().InvokeMember('objectSid', 'GetProperty', $null, $_, $null)
            $name  = $_.GetType().InvokeMember('Name', 'GetProperty', $null, $_, $null)
            [pscustomobject]@{ Sid = (New-Object Security.Principal.SecurityIdentifier($bytes, 0)).Value; Name = $name }
        })
    }
}
function Remove-AdminMember($sid) {
    try { Remove-LocalGroupMember -SID 'S-1-5-32-544' -Member $sid -ErrorAction Stop }
    catch { ([ADSI]'WinNT://./Administrators,group').Remove("WinNT://$sid") }
}

# Drop box for extend.cmd: every user may write a request, only the service (SYSTEM) acts on it.
function New-ExtendDir {
    New-Item -ItemType Directory -Force -Path $ExtendDir | Out-Null
    & icacls.exe $ExtendDir /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' '*S-1-5-32-545:(OI)(CI)M' | Out-Null
}

function Resolve-Local($name, $type = 'A') {
    try { Resolve-DnsName -Name $name -Type $type -Server 127.0.0.1 -DnsOnly -NoHostsFile -ErrorAction Stop }
    catch { $null }
}

# ------------------------------------------------------------------------------------------------
Step "Preflight"
$os = Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion'
$edition = $os.EditionID
Write-Host ("   Windows: {0} ({1}, build {2})" -f $os.ProductName, $edition, $os.CurrentBuild)
if ([int]$os.CurrentBuild -lt 17763) { Fail "Windows 10 1809 or newer is required (Set-DnsClientServerAddress / Get-LocalUser)." }
if ($edition -match 'CoreN|Core$|Home') { Ok "Home edition: everything here works on Home (no Group Policy needed; browser policies are registry-based)." }
elseif ($edition -match 'Professional|Education|Enterprise') { Ok "$edition edition." }
else { Warn "Unusual edition '$edition' - continuing, check the README limitations." }
if ((Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\CI\Policy' -Name 'SkuPolicyRequired' -ErrorAction SilentlyContinue).SkuPolicyRequired -eq 1) {
    Fail "Windows is in S mode: services and unsigned executables cannot be installed. Leave S mode first (Settings > Activation)."
}

$net = (Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\NET Framework Setup\NDP\v4\Full' -ErrorAction SilentlyContinue).Release
if (-not $net -or $net -lt 378389) { Fail ".NET Framework 4.5+ not found (it ships with Windows 10/11; enable it in 'Fonctionnalités Windows')." }
Ok ".NET Framework 4.x present ($net)"

# The kit ships source only: browsers and antivirus flag unsigned downloaded executables, and you
# should not have to trust a binary that takes your administrator rights. Build it here with the
# C# compiler that is part of the .NET Framework already installed on this machine.
$fwDir = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319'
if (-not (Test-Path $fwDir)) { $fwDir = Join-Path $env:WINDIR 'Microsoft.NET\Framework\v4.0.30319' }
$csc = Join-Path $fwDir 'csc.exe'
foreach ($pair in @(
    @{ Src = 'FocusGuardDns.cs';     Exe = 'FocusGuardDns.exe'; Target = 'exe';
       Refs = @('System.dll', 'System.Core.dll', 'System.ServiceProcess.dll', 'System.Security.dll') },
    @{ Src = 'FocusGuardBlocked.cs'; Exe = 'blocked.exe';       Target = 'winexe';
       Refs = @('System.dll', 'System.Core.dll', 'System.Drawing.dll', 'System.Windows.Forms.dll') }
)) {
    $exePath = Join-Path $Kit $pair.Exe
    $srcPath = Join-Path $Kit $pair.Src
    if (Test-Path $exePath) { continue }
    if (-not (Test-Path $srcPath)) { Fail "missing $($pair.Src) in $Kit - extract the whole kit zip." }
    if (-not (Test-Path $csc)) { Fail "C# compiler not found ($csc) - repair .NET Framework 4.x ('Fonctionnalités Windows')." }
    $cscArgs = @('/nologo', '/warn:0', '/optimize+', ('/target:' + $pair.Target), ('/out:' + $exePath))
    foreach ($r in $pair.Refs) { $cscArgs += ('/reference:' + (Join-Path $fwDir $r)) }
    $cscArgs += $srcPath
    $ErrorActionPreference = 'Continue'   # with 'Stop', stderr lines of a native command become terminating errors
    $build = & $csc $cscArgs 2>&1
    $ErrorActionPreference = 'Stop'
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $exePath)) { $build | ForEach-Object { Write-Host ("      " + $_) }; Fail "compiling $($pair.Src) failed." }
    Ok "compiled $($pair.Exe) with Windows' own C# compiler"
}

foreach ($f in $KitFiles) { if (-not (Test-Path (Join-Path $Kit $f))) { Fail "missing $f in $Kit - extract the whole kit zip." } }
Ok "kit files present in $Kit"

$svc = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
$alreadyInstalled = $null -ne $svc
# A new -Days/-Until on an existing installation = recommit: tear the old service down and go
# through the full install again with a fresh unlock date and a fresh sealed password. Only
# possible with admin rights, i.e. as FocusGuardAdmin after the previous date - so this can only
# ever extend, never shorten, a running commitment.
$recommit = $alreadyInstalled -and ($Until -or $PSBoundParameters.ContainsKey('Days'))
if ($recommit) {
    Step "Existing installation found - recommitting with a new unlock date"
    Stop-Service $ServiceName -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
    & sc.exe delete $ServiceName | Out-Null
    Unregister-ScheduledTask -TaskName 'FocusGuardRelease' -Confirm:$false -ErrorAction SilentlyContinue
    foreach ($f in @('admin.sealed', 'admin.plain', 'unlock-date.txt', 'released.flag')) { Remove-Item (Join-Path $State $f) -Force -ErrorAction SilentlyContinue }
    Remove-Item (Join-Path $Dir 'UNLOCKED - admin password.txt') -Force -ErrorAction SilentlyContinue
    Remove-Item (Join-Path $ExtendDir '*') -Force -ErrorAction SilentlyContinue
    $deadline = (Get-Date).AddSeconds(20)
    while ((Get-Service -Name $ServiceName -ErrorAction SilentlyContinue) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 500 }
    $alreadyInstalled = $false
    Ok "old service removed; original administrator list and downloaded blocklists kept"
}
if (-not $alreadyInstalled) {
    $udp53 = Get-NetUDPEndpoint -LocalPort 53 -ErrorAction SilentlyContinue | Where-Object { $_.LocalAddress -in @('0.0.0.0', '127.0.0.1', '::', '::1') }
    if ($udp53) { Fail "something already listens on UDP 53 (PID $($udp53.OwningProcess | Select-Object -First 1)) - stop it (Hyper-V/ICS DNS proxy, Acrylic, Pi-hole, AdGuard...)." }
    Ok "port 53 free"
}

& (Join-Path $Kit 'FocusGuardDns.exe') selftest (Join-Path $Kit 'lists.txt') (Join-Path $Kit 'allowlist.txt') | Out-Null
if ($LASTEXITCODE -ne 0) { Fail "FocusGuardDns.exe selftest failed - corrupt lists.txt / allowlist.txt or exe." }
Ok "filter self-test passed"
$allowCount = @(Get-Content (Join-Path $Kit 'allowlist.txt') | Where-Object { $_ -match '^\s*[^#\s]' }).Count
if ($Mode -eq 'allowonly' -and $allowCount -lt 10) { Fail "allowlist.txt has only $allowCount names - restore the kit's file and add yours to it." }

$me = [System.Security.Principal.WindowsIdentity]::GetCurrent()
$meName = $me.Name
Ok "running as $meName (administrator)"

# ------------------------------------------------------------------------------------------------
if ($alreadyInstalled) {
    Step "Existing installation found - updating program files only (state, admin account and unlock date are kept; pass -Days/-Until to recommit)"
    Stop-Service $ServiceName -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
    foreach ($f in $KitFiles) { Copy-Item (Join-Path $Kit $f) (Join-Path $Dir $f) -Force }
    New-ExtendDir
    if ($PSBoundParameters.ContainsKey('Mode')) { Write-Mode }
    Start-Service $ServiceName
    Ok "updated and restarted"
    & (Join-Path $Dir 'FocusGuardDns.exe') status
    if (-not $Yes) { Read-Host "Enter to close" | Out-Null }
    exit 0
}

# ------------------------------------------------------------------------------------------------
Step "Commitment"
if ($Until) {
    try { $unlockLocal = [datetime]::Parse($Until, [Globalization.CultureInfo]::CurrentCulture) } catch { Fail "cannot parse -Until '$Until' (use e.g. '2026-10-02 18:00')." }
} else {
    if ($Days -lt 1 -or $Days -gt 120) { Fail "-Days must be between 1 and 120." }
    $unlockLocal = (Get-Date).AddDays($Days)
}
if ($unlockLocal -le (Get-Date).AddHours(1)) { Fail "unlock date must be at least one hour in the future." }
$unlockUtc = $unlockLocal.ToUniversalTime()
Write-Host ("   until: {0:dddd dd MMMM yyyy HH:mm} (local) = {1:yyyy-MM-ddTHH:mm:ss}Z" -f $unlockLocal, $unlockUtc)
Write-Host ""
Write-Host "   After this point:" -ForegroundColor Yellow
if ($Mode -eq 'allowonly') {
    Write-Host "   - ALLOW-ONLY filter: only the $allowCount names in allowlist.txt (and their subdomains) open, in every program and browser."
    Write-Host "     Anything else - including sites and proxies that do not exist yet - is dead. A missing site is added with allow.cmd"
    Write-Host "     and opens 24 h later, never sooner, never if a block rule matches it. Check allowlist.txt has your school sites NOW."
} else {
    Write-Host "   - BLOCKLIST filter (the phone's behaviour): everything opens unless a block rule matches. New proxy sites get through."
}
Write-Host "   - your account ($meName) becomes a STANDARD user: no installing system software, no changing DNS/proxy/firewall/time,"
Write-Host "     no uninstalling, no 'Réinitialiser ce PC' from the login screen."
Write-Host "   - the only administrator is '$AdminUser'; its password exists nowhere readable until the date above."
Write-Host "   - the only ways out before then: reinstall Windows from a USB stick (everything wiped), or an unknown bug."
Write-Host "   - make sure your files are backed up (OneDrive/Drive/USB) and Windows updates are done. Chrome/Edge stay; Firefox/Opera/Brave get blocked."
Write-Host ""
if (-not $Yes) {
    $answer = Read-Host "   Type exactly  I COMMIT  to continue"
    if ($answer -ne 'I COMMIT') { Write-Host "Cancelled, nothing changed."; exit 1 }
}

# ------------------------------------------------------------------------------------------------
Step "Installing files to $Dir"
New-Item -ItemType Directory -Force -Path $Dir | Out-Null
New-Item -ItemType Directory -Force -Path $State | Out-Null
foreach ($f in $KitFiles) { Copy-Item (Join-Path $Kit $f) (Join-Path $Dir $f) -Force }
# program dir: normal Program Files ACL (Users read/execute - blocked.exe must be runnable by you).
# state dir: SYSTEM + Administrators write, Users read (status/log); admin.plain gets its own SYSTEM-only ACL below.
& icacls.exe $State /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' '*S-1-5-32-545:(OI)(CI)RX' | Out-Null
[IO.File]::WriteAllText((Join-Path $State 'unlock-date.txt'), $unlockUtc.ToString('yyyy-MM-ddTHH:mm:ssZ'))
New-ExtendDir
Write-Mode
Ok "files copied, unlock date written"

# original administrators, so remove.ps1 can give the rights back later
$adminGroup = Get-LocalGroup -SID 'S-1-5-32-544'
$origFile = Join-Path $State 'original-admins.txt'
if ($recommit -and (Test-Path $origFile) -and @(Get-Content $origFile | Where-Object { $_ -match '\S' }).Count -gt 0) {
    # on a recommit the current admins are only FocusGuardAdmin (+ the disabled built-in): the
    # accounts that must get their rights back at the end are the ones recorded the first time
    Ok ("kept the {0} original administrator(s) recorded at the first setup" -f @(Get-Content $origFile | Where-Object { $_ -match '\S' }).Count)
} else {
    $origAdmins = Get-AdminMembers | Where-Object { $_.Name.Split('\')[-1] -ne $AdminUser } | ForEach-Object { $_.Sid + ' ' + $_.Name }
    [IO.File]::WriteAllLines($origFile, [string[]]$origAdmins)
    Ok ("recorded {0} current administrator(s) for the removal script" -f @($origAdmins).Count)
}

# ------------------------------------------------------------------------------------------------
Step "Creating the sealed administrator account '$AdminUser'"
$plain = New-RandomPassword 24
$secure = ConvertTo-SecureString $plain -AsPlainText -Force
$existing = Get-LocalUser -Name $AdminUser -ErrorAction SilentlyContinue
if ($existing) {
    Set-LocalUser -Name $AdminUser -Password $secure -PasswordNeverExpires $true -AccountNeverExpires
    Enable-LocalUser -Name $AdminUser
} else {
    New-LocalUser -Name $AdminUser -Password $secure -PasswordNeverExpires -AccountNeverExpires -Description 'FocusGuard: sole admin until the unlock date' | Out-Null
}
# New-LocalUser leaves PASSWD_NOTREQD set ("Password required: No" in `net user`); clear it so a blank password is never accepted.
& net user $AdminUser /passwordreq:yes | Out-Null
if (-not (Get-AdminMembers | Where-Object { $_.Name.Split('\')[-1] -ieq $AdminUser })) { Add-LocalGroupMember -Group $adminGroup -Member $AdminUser }
$plainFile = Join-Path $State 'admin.plain'
[IO.File]::WriteAllText($plainFile, $plain)
& icacls.exe $plainFile /inheritance:r /grant:r '*S-1-5-18:F' | Out-Null
$plain = $null; $secure = $null
[GC]::Collect()
# sanity: the account must be able to log on (an account with a broken password would be a lock-out)
if (-not (Get-LocalUser -Name $AdminUser).Enabled) { Fail "$AdminUser is not enabled - aborting before anything is locked." }
Ok "$AdminUser created, in Administrators, password written for the service to seal (SYSTEM-only file)"

# ------------------------------------------------------------------------------------------------
Step "Installing the $ServiceName service"
$exe = Join-Path $Dir 'FocusGuardDns.exe'
& sc.exe create $ServiceName binPath= "`"$exe`"" start= auto obj= LocalSystem DisplayName= "FocusGuard DNS filter" | Out-Null
if ($LASTEXITCODE -ne 0) { Fail "sc create failed ($LASTEXITCODE)" }
& sc.exe description $ServiceName "Local DNS filter and lock enforcer. Do not stop: removing it needs the FocusGuardAdmin password, released at the unlock date." | Out-Null
& sc.exe failure $ServiceName reset= 0 actions= restart/2000/restart/2000/restart/2000 | Out-Null
Start-Service $ServiceName
$deadline = (Get-Date).AddSeconds(30)
do { Start-Sleep -Milliseconds 500; $svc = Get-Service $ServiceName } while ($svc.Status -ne 'Running' -and (Get-Date) -lt $deadline)
if ($svc.Status -ne 'Running') { Fail "service did not start; see $State\focusguard.log" }
Ok "service running"

# Second, service-independent release path: a SYSTEM scheduled task at the unlock date.
$action  = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument ('-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + (Join-Path $Dir 'release.ps1') + '"')
$trigger = New-ScheduledTaskTrigger -Once -At $unlockLocal -RepetitionInterval (New-TimeSpan -Hours 1) -RepetitionDuration (New-TimeSpan -Days 365)
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit (New-TimeSpan -Minutes 5)
$principal = New-ScheduledTaskPrincipal -UserId 'S-1-5-18' -RunLevel Highest
Register-ScheduledTask -TaskName 'FocusGuardRelease' -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Force | Out-Null
Ok "backup release task registered (runs as SYSTEM at the unlock date, hourly afterwards until the password file exists)"

Step "Checking the resolver on 127.0.0.1"
Start-Sleep -Seconds 2
$a = Resolve-Local 'wikipedia.org'
if (-not $a) { Fail "wikipedia.org did not resolve through the local resolver (no internet? DoH to 1.1.1.3 blocked by the network?). Service stopped; run remove.ps1 to undo." }
Ok ("wikipedia.org -> {0}" -f ($a | Where-Object { $_.Type -eq 'A' } | Select-Object -First 1 -ExpandProperty IPAddress))
$b = Resolve-Local 'forja.ma'
if (-not $b -or ($b | Where-Object { $_.Type -eq 'A' } | Select-Object -First 1 -ExpandProperty IPAddress) -ne '0.0.0.0') { Fail "forja.ma was not blocked (got: $($b.IPAddress)) - lists.txt not loaded?" }
Ok "forja.ma -> 0.0.0.0 (blocked)"
$c = Resolve-Local 'youtube.com'
if (($c | Where-Object { $_.Type -eq 'A' } | Select-Object -First 1 -ExpandProperty IPAddress) -ne '0.0.0.0') { Fail "youtube.com was not blocked." }
Ok "youtube.com -> 0.0.0.0 (blocked)"
if ($Mode -eq 'allowonly') {
    foreach ($probe in @('example.com', 'trickybird-clone.example', 'some-proxy.vercel.app')) {
        $d = Resolve-Local $probe
        if (-not $d -or ($d | Where-Object { $_.Type -eq 'A' } | Select-Object -First 1 -ExpandProperty IPAddress) -ne '0.0.0.0') { Fail "$probe was not denied (got: $($d.IPAddress)) - allow-only mode not active?" }
    }
    Ok "unknown names -> 0.0.0.0 (allow-only mode active)"
    foreach ($probe in @('login.microsoftonline.com', 'accounts.google.com', 'raw.githubusercontent.com')) {
        $d = Resolve-Local $probe
        if (-not $d -or ($d | Where-Object { $_.Type -eq 'A' } | Select-Object -First 1 -ExpandProperty IPAddress) -eq '0.0.0.0') { Fail "$probe did not resolve - allowlist.txt not loaded?" }
    }
    Ok "Microsoft / Google sign-in and the blocklist source resolve"
}

# ------------------------------------------------------------------------------------------------
Step "Applying the system lock (DNS, firewall, browser policies, blocked programs, recovery menu)"
& $exe enforce-once | Out-Null
& ipconfig.exe /flushdns | Out-Null
$bad = Get-DnsClientServerAddress -AddressFamily IPv4 | Where-Object { $_.InterfaceAlias -notmatch 'Loopback' -and $_.ServerAddresses.Count -gt 0 -and ($_.ServerAddresses -ne '127.0.0.1') }
if ($bad) { Warn ("adapters not yet on 127.0.0.1: " + (($bad | ForEach-Object { $_.InterfaceAlias }) -join ', ') + " - the service retries every minute") } else { Ok "every adapter uses 127.0.0.1 / ::1" }
$fw = Get-NetFirewallRule -DisplayName 'FocusGuard block DNS udp' -ErrorAction SilentlyContinue
if ($fw) { Ok "firewall rules present (outbound 53/853 and public DoH resolvers blocked)" } else { Warn "firewall rules missing - the service retries every minute" }
if ((Get-ItemProperty 'HKLM:\SOFTWARE\Policies\Google\Chrome' -ErrorAction SilentlyContinue).ForceGoogleSafeSearch -eq 1) { Ok "Chrome/Edge policies applied (SafeSearch, no incognito, no DoH, no proxy)" } else { Warn "browser policies missing - the service retries every minute" }
$urls = @((Get-Item 'HKLM:\SOFTWARE\Policies\Google\Chrome\URLBlocklist' -ErrorAction SilentlyContinue).Property).Count
if ($urls -gt 100) { Ok "$urls browser URL rules applied (Images/Videos tabs, web proxies)" } else { Warn "browser URL rules missing ($urls) - the service retries every minute" }
$sys = Resolve-DnsName wikipedia.org -Type A -DnsOnly -ErrorAction SilentlyContinue
if ($sys) { Ok "system-wide resolution works through the filter" } else { Warn "system resolution not working yet - reconnect Wi-Fi or reboot; if it persists run remove.ps1 (still possible right now)" }

# ------------------------------------------------------------------------------------------------
Step "Waiting for the service to download the 7 blocklists (~1.1 million domains, 1-4 min; refreshed daily)"
$deadline = (Get-Date).AddMinutes(4)
$got = $null
do {
    $st = (& $exe status 2>&1 | Out-String)
    if ($st -match 'downloaded blocklist: (\d+) domains') { $got = $Matches[1]; break }
    Start-Sleep -Seconds 5
} while ((Get-Date) -lt $deadline)
if ($got) { Ok "downloaded lists ready ($got domains)" } else { Warn "not downloaded yet; the service keeps retrying every 10 min (built-in lists already active) - reason in $State\focusguard.log" }

# ------------------------------------------------------------------------------------------------
Step "Removing administrator rights from every account except $AdminUser"
$removed = @()
foreach ($m in Get-AdminMembers) {
    $n = $m.Name.Split('\')[-1]
    if ($n -ieq $AdminUser) { continue }
    if ($m.Sid -like '*-500') { Disable-LocalUser -SID $m.Sid -ErrorAction SilentlyContinue; $removed += "$n (built-in Administrator, disabled)"; continue }
    try { Remove-AdminMember $m.Sid; $removed += $n } catch { Warn "could not remove $n : $_" }
}
foreach ($r in $removed) { Ok "removed: $r" }
$left = Get-AdminMembers | ForEach-Object { $_.Name.Split('\')[-1] }
if (@($left).Count -ne 1 -or $left -ne $AdminUser) { Warn ("Administrators now: " + ($left -join ', ') + " - the service finishes the cleanup within a minute") } else { Ok "Administrators = $AdminUser only" }

# ------------------------------------------------------------------------------------------------
Step "Done"
& $exe status
Write-Host ""
Write-Host ("   Locked until {0:dddd dd MMMM yyyy HH:mm}." -f $unlockLocal) -ForegroundColor Green
Write-Host "   On that day the file  $Dir\UNLOCKED - admin password.txt  appears (within a minute)."
Write-Host "   To remove then: sign out -> sign in as $AdminUser with that password -> double-click $Dir\remove.cmd."
Write-Host "   To keep the blocker after the date: do nothing."
Write-Host "   To lock it for longer, any time before the date, no password needed: double-click $Dir\extend.cmd."
Write-Host "   To block one more site for good: $Dir\block.cmd."
if ($Mode -eq 'allowonly') { Write-Host "   A school site that does not open: $Dir\allow.cmd  -> reachable 24 h later (refused if a block rule matches it)." }
Write-Host ""
Write-Host "   Sign out and back in now (your admin token is still active in this session), then restart Chrome/Edge." -ForegroundColor Yellow
Write-Host "   Check: wikipedia.org loads, youtube.com / forja.ma do not, Firefox shows 'blocked by FocusGuard'."
if (-not $Yes) { Read-Host "Enter to close" | Out-Null }
