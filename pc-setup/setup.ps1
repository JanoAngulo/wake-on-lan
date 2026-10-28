<#
.SYNOPSIS
    Installs, updates or removes the Wake agent on this PC. Safe to run more than once.

.DESCRIPTION
    -Check      Read-only. Shows each item as Current / Wanted / OK. No admin needed.
    -Uninstall  Stops the agent and removes the logon task, firewall rules, files and pairing.
    (none)      Installs or updates. Run from an elevated PowerShell.

    The agent comes either as a self-contained folder (-AgentDir, holds WakeAgent.exe with its own
    Java runtime: what the release download contains) or as a jar run by an installed JDK 21
    (-AgentJar: what a source build produces).

    Items:
      1. Wired adapter: Wake on Magic Packet and Shutdown Wake-On-Lan enabled.
      2. Fast Startup off, so shutdown is a real shutdown that Wake-on-LAN can wake from.
      3. Firewall: agent ports 47613/TCP and 47614/UDP allowed from the local subnet only.
      4. Agent copied to %LOCALAPPDATA%\Wake and started at logon by a scheduled task.
      6. Optional fingerprint unlock module: WakeCredentialProvider.dll in C:\Program Files\Wake (admin-only,
         because LogonUI loads it as SYSTEM), registered as an extra lock-screen tile. Asked about on install;
         -Unlock / -NoUnlock answer for you. -UnlockDll points at the DLL.
      7. Optional fingerprint sign-in after shutdown (needs item 6 and the release download): WakeSignIn.exe in
         C:\Program Files\Wake\SignIn, started at boot by the "Wake Sign-in" task as LOCAL SERVICE with its own
         task SID and one privilege. It listens on 47615/TCP, local subnet only, while the sign-in or lock screen
         is up. Asked about on install; -SignIn / -NoSignIn answer for you.

    -Check also reports, without changing it, whether BitLocker protects C:.

    Green Ethernet and Power Saving Mode are only reported: changing adapter settings resets the
    network link, and Wake-on-LAN usually works with them on.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File pc-setup\setup.ps1 -Check
    powershell -ExecutionPolicy Bypass -File pc-setup\setup.ps1 -AgentDir WakeAgent
    powershell -ExecutionPolicy Bypass -File pc-setup\setup.ps1 -AgentJar agent\build\libs\wake-agent.jar
    powershell -ExecutionPolicy Bypass -File pc-setup\setup.ps1 -Uninstall
#>
param(
    [switch]$Check,
    [switch]$Uninstall,
    [string]$AgentDir,
    [string]$AgentJar = "$PSScriptRoot\..\agent\build\libs\wake-agent.jar",
    [switch]$Unlock,
    [switch]$NoUnlock,
    [string]$UnlockDll,
    [switch]$SignIn,
    [switch]$NoSignIn
)

$ErrorActionPreference = 'Stop'
$HttpPort = 47613
$DiscoveryPort = 47614
$TaskName = 'Wake Agent'
$RuleNames = 'Wake Agent HTTP', 'Wake Agent Discovery'
$InstallDir = Join-Path $env:LOCALAPPDATA 'Wake'
$InstalledJar = Join-Path $InstallDir 'wake-agent.jar'
$InstalledApp = Join-Path $InstallDir 'WakeAgent'
$InstalledExe = Join-Path $InstalledApp 'WakeAgent.exe'
$SecretDir = Join-Path $env:APPDATA 'Wake'
$PowerKey = 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Power'
$UnlockClsid = '{8C5D3B0E-6A41-4C7E-9F2B-5D1A7E3C9B04}'
$UnlockTarget = Join-Path $env:ProgramFiles 'Wake\WakeCredentialProvider.dll'
$UnlockComKey = "HKLM:\SOFTWARE\Classes\CLSID\$UnlockClsid"
$UnlockProviderKey = "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\Credential Providers\$UnlockClsid"
$PasswordLessKey = 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\PasswordLess\Device'
$SignInTask = 'Wake Sign-in'
$SignInAccount = "NT TASK\$SignInTask"
$SignInDir = Join-Path $env:ProgramFiles 'Wake\SignIn'
$SignInExe = Join-Path $SignInDir 'WakeSignIn.exe'
$SignInData = Join-Path $env:ProgramData 'Wake'
$SignInKeyDir = Join-Path $SignInData 'signin'
$SignInLogDir = Join-Path $SignInData 'logs'
$SignInPort = 47615
$SignInRule = 'Wake Sign-in'
$WakeKey = 'HKLM:\SOFTWARE\Wake'

function Test-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    (New-Object Security.Principal.WindowsPrincipal $id).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Stop-Agent {
    Get-CimInstance Win32_Process |
        Where-Object { $_.Name -eq 'WakeAgent.exe' -or ($_.Name -match '^javaw?\.exe$' -and $_.CommandLine -like '*wake-agent.jar*') } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Milliseconds 800
}

# Hyper-V adapters count only when they hold the default gateway: the host's vEthernet on an External switch,
# or the network card inside a Hyper-V VM. Default Switch and WSL have no gateway, so they never match.
function Get-LanAdapter {
    $gateway = Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway -and $_.NetAdapter.Status -eq 'Up' } |
        Where-Object { $_.InterfaceDescription -notmatch 'Radmin|VPN|VMware|VirtualBox|TAP|Wi-?Fi|Wireless|Tunnel|WireGuard|Tailscale|Wintun|NordLynx' } |
        Where-Object { $_.InterfaceDescription -notmatch 'Virtual' -or $_.InterfaceDescription -match 'Hyper-V' } |
        Sort-Object { $_.InterfaceDescription -match 'Hyper-V|Virtual' } |
        Select-Object -First 1
    if (-not $gateway) { throw 'No wired network adapter with internet access was found. Wake-on-LAN needs a cable.' }
    Get-NetAdapter -InterfaceIndex $gateway.InterfaceIndex
}

# The card whose wake settings matter. Behind an External switch that's the physical NIC sharing the vEthernet's MAC.
function Get-WakeAdapter($lan) {
    if ($lan.HardwareInterface) { return $lan }
    $physical = Get-NetAdapter -Physical -ErrorAction SilentlyContinue | Where-Object MacAddress -eq $lan.MacAddress | Select-Object -First 1
    if ($physical) { $physical } else { $lan }
}

function Get-Javaw {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += Join-Path $env:JAVA_HOME 'bin\javaw.exe' }
    $candidates += Get-ChildItem 'C:\Program Files\Java\jdk-2*\bin\javaw.exe', 'C:\Program Files\Eclipse Adoptium\jdk-2*\bin\javaw.exe' -ErrorAction SilentlyContinue | ForEach-Object FullName
    $onPath = Get-Command javaw.exe -ErrorAction SilentlyContinue
    if ($onPath) { $candidates += $onPath.Source }
    $found = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $found) { throw 'javaw.exe (JDK 21) not found. Use the release download instead, which includes its own Java.' }
    $found
}

function Test-UnlockModule { (Test-Path $UnlockProviderKey) -and (Test-Path -LiteralPath $UnlockTarget) }

function Add-NativeMoveFile {
    if ('Wake.Native' -as [type]) { return }
    Add-Type -Namespace Wake -Name Native -MemberDefinition @'
[DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
public static extern bool MoveFileEx(string existing, string replacement, int flags);
'@
}

function Install-UnlockModule($dll) {
    New-Item -ItemType Directory -Force (Split-Path $UnlockTarget) | Out-Null
    $same = (Test-Path -LiteralPath $UnlockTarget) -and (Get-FileHash -LiteralPath $dll).Hash -eq (Get-FileHash -LiteralPath $UnlockTarget).Hash
    if (-not $same) {
        try {
            Copy-Item -LiteralPath $dll -Destination $UnlockTarget -Force
        } catch {
            # LogonUI keeps the DLL loaded. A loaded file can still be renamed: move it aside, copy the new one
            # in (LogonUI picks it up the next time it starts), and delete the old one at the next reboot.
            $aside = "$UnlockTarget.old-$([DateTime]::Now.Ticks)"
            Rename-Item -LiteralPath $UnlockTarget -NewName (Split-Path $aside -Leaf)
            try {
                Copy-Item -LiteralPath $dll -Destination $UnlockTarget -Force
            } catch {
                # Never leave the registry pointing at a missing DLL: put the old one back.
                Rename-Item -LiteralPath $aside -NewName (Split-Path $UnlockTarget -Leaf)
                throw
            }
            Add-NativeMoveFile
            if (-not [Wake.Native]::MoveFileEx($aside, $null, 4)) {  # MOVEFILE_DELAY_UNTIL_REBOOT
                Write-Output "Delete $aside after the next restart."
            }
        }
    }
    New-Item -Force $UnlockComKey | Out-Null
    Set-ItemProperty $UnlockComKey '(default)' 'Wake unlock'
    New-Item -Force "$UnlockComKey\InprocServer32" | Out-Null
    Set-ItemProperty "$UnlockComKey\InprocServer32" '(default)' $UnlockTarget
    Set-ItemProperty "$UnlockComKey\InprocServer32" 'ThreadingModel' 'Apartment'
    New-Item -Force $UnlockProviderKey | Out-Null
    Set-ItemProperty $UnlockProviderKey '(default)' 'Wake unlock'
}

# LogonUI may still have the DLL loaded; then it's deleted at the next reboot.
function Remove-UnlockModule {
    foreach ($key in $UnlockProviderKey, $UnlockComKey) {
        if (Test-Path $key) { Remove-Item -Recurse -Force $key }
    }
    $dir = Split-Path $UnlockTarget
    # Old copies set aside by an update while LogonUI had them loaded.
    Get-ChildItem -LiteralPath $dir -Filter 'WakeCredentialProvider.dll.old-*' -Force -ErrorAction SilentlyContinue | ForEach-Object {
        try { Remove-Item -LiteralPath $_.FullName -Force } catch { Add-NativeMoveFile; [void][Wake.Native]::MoveFileEx($_.FullName, $null, 4) }
    }
    if (-not (Test-Path -LiteralPath $UnlockTarget)) { return }
    try {
        Remove-Item -LiteralPath $UnlockTarget -Force
        if (-not (Get-ChildItem -LiteralPath $dir -Force -ErrorAction SilentlyContinue)) { Remove-Item -LiteralPath $dir -Force }
    } catch {
        Add-NativeMoveFile
        # MOVEFILE_DELAY_UNTIL_REBOOT; the then-empty folder goes with it.
        if ([Wake.Native]::MoveFileEx($UnlockTarget, $null, 4)) {
            [void][Wake.Native]::MoveFileEx($dir, $null, 4)
            Write-Output "$UnlockTarget is in use; it will be deleted at the next reboot."
        } else {
            Write-Output "Couldn't remove $UnlockTarget (error $([Runtime.InteropServices.Marshal]::GetLastWin32Error())). Delete it after a reboot."
        }
    }
}

# Without admin rights Windows hides the LOCAL SERVICE task, so -Check falls back to the installed program.
function Test-SignIn {
    if (-not (Test-Path -LiteralPath $SignInExe)) { return $false }
    [bool](Get-ScheduledTask -TaskName $SignInTask -ErrorAction SilentlyContinue) -or -not (Test-Admin)
}

function Stop-SignIn {
    Stop-ScheduledTask -TaskName $SignInTask -ErrorAction SilentlyContinue
    Get-Process WakeSignIn -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 800
}

function Test-ReparsePoint($path) {
    $item = Get-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
    $item -and ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)
}

# Deletes a folder without ever following a link or junction inside it (or at the top): cmd's rd doesn't
# traverse reparse points, and a link at the top is removed as a link.
function Remove-DirNoFollow($path) {
    if (Test-ReparsePoint $path) { [IO.Directory]::Delete($path, $false); return }
    if (Test-Path -LiteralPath $path) { & cmd /c rd /s /q "`"$path`"" 2>$null | Out-Null }
}

# True when the folder exists but wasn't made by an administrator (anyone can create folders in ProgramData),
# or is a link. Its contents can't be trusted, and the link is removed.
function Reset-PlantedFolder($path) {
    if (-not (Test-Path -LiteralPath $path)) { return $false }
    if (Test-ReparsePoint $path) { [IO.Directory]::Delete($path, $false); return $true }
    $owner = try { (Get-Acl -LiteralPath $path).GetOwner([Security.Principal.SecurityIdentifier]).Value } catch { $null }
    $owner -notin 'S-1-5-18', 'S-1-5-32-544'
}

function Set-SignInAcl($dir, [string[]]$grants) {
    New-Item -ItemType Directory -Force $dir | Out-Null
    foreach ($step in @(
        # No /C: icacls must stop, and fail, at the first file it can't change.
        @('/setowner', '*S-1-5-32-544', '/T'),
        @('/reset', '/T'),
        @(@('/inheritance:r', '/grant:r') + $grants)
    )) {
        & icacls $dir @step | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "icacls $($step[0]) failed on $dir ($LASTEXITCODE)" }
    }
}

# Pre-login sign-in: an admin-only program, a startup task as LOCAL SERVICE with its own task SID, and grants
# that name only that task SID. The task SID exists once the task does, so the task comes before the ACLs.
function Install-SignIn($sourceDir) {
    $sameBuild = (Test-Path -LiteralPath $SignInExe) -and ((Get-FileHash (Join-Path $sourceDir 'app\wake-agent.jar')).Hash -eq
        (Get-FileHash (Join-Path $SignInDir 'app\wake-agent.jar') -ErrorAction SilentlyContinue).Hash)
    Stop-SignIn
    if (-not $sameBuild) {
        Remove-Item -LiteralPath $SignInDir -Recurse -Force -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force (Split-Path $SignInDir) | Out-Null
        Copy-Item $sourceDir $SignInDir -Recurse -Force
    }

    # At startup, plus every 5 minutes so a crash is back soon; IgnoreNew skips the repeat while it runs.
    $action = New-ScheduledTaskAction -Execute $SignInExe
    $triggers = @(
        (New-ScheduledTaskTrigger -AtStartup),
        (New-ScheduledTaskTrigger -Once -At (Get-Date) -RepetitionInterval (New-TimeSpan -Minutes 5))
    )
    $principal = New-ScheduledTaskPrincipal -UserId 'NT AUTHORITY\LOCAL SERVICE' -LogonType ServiceAccount `
        -ProcessTokenSidType Unrestricted -RequiredPrivilege 'SeChangeNotifyPrivilege'
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
        -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew -StartWhenAvailable
    Register-ScheduledTask -TaskName $SignInTask -Action $action -Trigger $triggers -Principal $principal -Settings $settings `
        -Description 'Lets your paired phone sign you in at the Windows sign-in screen.' -Force | Out-Null

    $taskSid = ([Security.Principal.NTAccount]$SignInAccount).Translate([Security.Principal.SecurityIdentifier]).Value
    $userSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $savedSid = (Get-ItemProperty $WakeKey -Name SignInUserSid -ErrorAction SilentlyContinue).SignInUserSid
    # Anyone can create folders in ProgramData, so one of these may already exist and belong to someone else,
    # or be a link. Links are removed; then take ownership for Administrators, wipe whatever ACL is there and
    # grant exactly what's needed. If any step fails, undo the install: a half-secured folder must never
    # receive the key.
    $planted = $false
    try {
        foreach ($dir in $SignInData, $SignInKeyDir, $SignInLogDir) { if (Reset-PlantedFolder $dir) { $planted = $true } }
        Set-SignInAcl $SignInData '*S-1-5-18:(OI)(CI)F', '*S-1-5-32-544:(OI)(CI)F', '*S-1-5-32-545:RX'
        Set-SignInAcl $SignInKeyDir '*S-1-5-18:(OI)(CI)F', '*S-1-5-32-544:(OI)(CI)F', "*${userSid}:(OI)(CI)M", "*${taskSid}:(OI)(CI)RX"
        Set-SignInAcl $SignInLogDir '*S-1-5-18:(OI)(CI)F', '*S-1-5-32-544:(OI)(CI)F', "*${taskSid}:(OI)(CI)M", "*${userSid}:(OI)(CI)RX"
    } catch {
        Remove-SignIn
        throw
    }
    # A key someone else could have read, or one made for another account, must not be used: the agent writes a
    # fresh one, and pairing the phone again makes any copied key useless.
    $keyFile = Join-Path $SignInKeyDir 'signin.key'
    if (($planted -or ($savedSid -and $savedSid -ne $userSid)) -and (Test-Path -LiteralPath $keyFile)) {
        Remove-Item -LiteralPath $keyFile -Force
    }
    if ($planted) {
        Write-Output "Another account had created Wake's ProgramData folder before setup. It's secured now; pair your phone again to be safe."
    }

    # The DLL serves this account's tile when the sign-in screen lists several.
    if (-not (Test-Path $WakeKey)) { New-Item $WakeKey | Out-Null }
    Set-ItemProperty $WakeKey 'SignInUserSid' $userSid -Type String

    $rule = Get-NetFirewallRule -DisplayName $SignInRule -ErrorAction SilentlyContinue
    if (-not $rule -or ($rule | Get-NetFirewallApplicationFilter).Program -ne $SignInExe) {
        if ($rule) { $rule | Remove-NetFirewallRule }
        New-NetFirewallRule -DisplayName $SignInRule -Direction Inbound -Action Allow -Protocol TCP -LocalPort $SignInPort `
            -RemoteAddress LocalSubnet -Profile Private, Public -Program $SignInExe | Out-Null
    }
    Start-ScheduledTask -TaskName $SignInTask
}

function Remove-SignIn {
    Stop-SignIn
    Unregister-ScheduledTask -TaskName $SignInTask -Confirm:$false -ErrorAction SilentlyContinue
    Remove-NetFirewallRule -DisplayName $SignInRule -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $SignInDir -Recurse -Force -ErrorAction SilentlyContinue  # under Program Files: admin-only
    Remove-DirNoFollow $SignInData  # under ProgramData: may hold links someone planted
    if (Test-Path $WakeKey) { Remove-Item $WakeKey -Recurse -Force }
}

# Exact keyword match: '*' in keywords like '*EEE' would otherwise act as a wildcard.
function Get-AdapterProperty($adapterName, $keyword) {
    Get-NetAdapterAdvancedProperty -Name $adapterName -ErrorAction SilentlyContinue |
        Where-Object { $_.RegistryKeyword -eq $keyword } | Select-Object -First 1
}

$results = New-Object System.Collections.Generic.List[object]
function Add-Result($item, $current, $wanted, $ok, $note = '') {
    $results.Add([pscustomobject]@{ Item = $item; Current = "$current"; Wanted = "$wanted"; OK = $(if ($null -eq $ok) { '?' } elseif ($ok) { 'yes' } else { 'NO' }); Note = $note })
}

if (-not $Check -and -not (Test-Admin)) {
    throw 'Run this from an elevated PowerShell (Run as administrator), or add -Check for a read-only report.'
}

if ($Uninstall) {
    Stop-Agent
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
    $RuleNames | ForEach-Object { Remove-NetFirewallRule -DisplayName $_ -ErrorAction SilentlyContinue }
    Remove-Item $InstallDir, $SecretDir -Recurse -Force -ErrorAction SilentlyContinue
    Remove-SignIn  # before the unlock module, which removes C:\Program Files\Wake only when it's empty
    Remove-UnlockModule
    Write-Output 'Wake agent removed: logon task, firewall rules, files, pairing, sign-in after shutdown and the fingerprint unlock module.'
    Write-Output 'Network adapter and Fast Startup settings were left as they are.'
    return
}

# Set up for another Windows account (run again from a different admin)? Installing here moves Wake to this
# account: the agent starts for it, and sign-in after shutdown serves its tile. Ask first, and change nothing
# on no (or when nobody can answer).
if (-not $Check) {
    $savedSid = (Get-ItemProperty $WakeKey -Name SignInUserSid -ErrorAction SilentlyContinue).SignInUserSid
    $me = [Security.Principal.WindowsIdentity]::GetCurrent()
    if ($savedSid -match '^S-1-[0-9-]+$' -and $savedSid -ne $me.User.Value) {
        $savedName = try { ([Security.Principal.SecurityIdentifier]$savedSid).Translate([Security.Principal.NTAccount]).Value } catch { 'another account' }
        $answer = try { Read-Host "Wake is set up for $savedName. Move it to $($me.Name)? $savedName's phone will need to pair again. (y/N)" } catch { '' }
        if ($answer -notmatch '^\s*y') {
            Write-Output 'Nothing was changed.'
            return
        }
    }
}

# Which agent are we installing, and what program will run it?
# Run from the unzipped download without -AgentDir (as the help tells people to for -Check): use its WakeAgent folder.
if (-not $AgentDir -and -not $PSBoundParameters.ContainsKey('AgentJar') -and (Test-Path (Join-Path $PSScriptRoot '..\WakeAgent\WakeAgent.exe'))) { $AgentDir = Join-Path $PSScriptRoot '..\WakeAgent' }
$bundled = [bool]$AgentDir
if ($bundled) {
    $sourceDir = Resolve-Path $AgentDir -ErrorAction SilentlyContinue
    if (-not $sourceDir -or -not (Test-Path (Join-Path $sourceDir 'WakeAgent.exe'))) { throw "WakeAgent.exe not found in $AgentDir." }
    $program = $InstalledExe
    $arguments = ''
} else {
    $program = Get-Javaw
    $arguments = "-jar `"$InstalledJar`""
}

# 1. Adapter wake settings
$adapter = Get-WakeAdapter (Get-LanAdapter)
$wanted = @{ '*WakeOnMagicPacket' = 'Enabled'; 'S5WakeOnLan' = 'Enabled' }
foreach ($keyword in $wanted.Keys) {
    $prop = Get-AdapterProperty $adapter.Name $keyword
    if (-not $prop) { Add-Result "$($adapter.Name): $keyword" 'absent' $wanted[$keyword] $true 'not offered by this adapter'; continue }
    $ok = $prop.DisplayValue -eq $wanted[$keyword]
    if (-not $ok -and -not $Check) {
        $prop | Set-NetAdapterAdvancedProperty -DisplayValue $wanted[$keyword]
        $ok = $true
    }
    Add-Result "$($adapter.Name): $($prop.DisplayName)" $prop.DisplayValue $wanted[$keyword] $ok
}
foreach ($keyword in 'EnableGreenEthernet', 'PowerSavingMode', '*EEE') {
    $prop = Get-AdapterProperty $adapter.Name $keyword
    if ($prop) { Add-Result "$($adapter.Name): $($prop.DisplayName)" $prop.DisplayValue 'report only' $true 'left as is' }
}

# 2. Fast Startup
$hiberboot = (Get-ItemProperty $PowerKey -Name HiberbootEnabled -ErrorAction SilentlyContinue).HiberbootEnabled
$ok = $hiberboot -eq 0
if (-not $ok -and -not $Check) {
    Set-ItemProperty $PowerKey -Name HiberbootEnabled -Value 0 -Type DWord
    $ok = $true
}
Add-Result 'Fast Startup (HiberbootEnabled)' $hiberboot 0 $ok

# 3. Agent files (stop the running agent first so its files can be replaced)
if ($bundled) {
    $sourceExe = Join-Path $sourceDir 'WakeAgent.exe'
    $sameBuild = (Test-Path $InstalledExe) -and ((Get-FileHash (Join-Path $sourceDir 'app\wake-agent.jar') -ErrorAction SilentlyContinue).Hash -eq
        (Get-FileHash (Join-Path $InstalledApp 'app\wake-agent.jar') -ErrorAction SilentlyContinue).Hash)
    if (-not $sameBuild -and -not $Check) {
        Stop-Agent
        New-Item -ItemType Directory -Force $InstallDir | Out-Null
        Remove-Item $InstalledApp -Recurse -Force -ErrorAction SilentlyContinue
        Copy-Item $sourceDir $InstalledApp -Recurse -Force
    }
    Add-Result 'Agent installed' $(if (Test-Path $InstalledExe) { 'present' } else { 'missing' }) $InstalledExe ($sameBuild -or -not $Check) $(if ($Check -and -not $sameBuild) { 'install or update needed' } else { '' })
} else {
    $jarSource = Resolve-Path $AgentJar -ErrorAction SilentlyContinue
    $installedHash = if (Test-Path $InstalledJar) { (Get-FileHash $InstalledJar).Hash } else { $null }
    $sourceHash = if ($jarSource) { (Get-FileHash $jarSource).Hash } else { $null }
    $ok = $installedHash -and ($installedHash -eq $sourceHash -or -not $sourceHash)
    if (-not $ok -and -not $Check) {
        if (-not $jarSource) { throw "Agent jar not found at $AgentJar. Build it with: .\gradlew :agent:agentJar" }
        Stop-Agent
        New-Item -ItemType Directory -Force $InstallDir | Out-Null
        Copy-Item $jarSource $InstalledJar -Force
        $ok = $true
    }
    Add-Result 'Agent installed' $(if ($installedHash) { 'present' } else { 'missing' }) $InstalledJar $ok $(if ($installedHash -and $sourceHash -and $installedHash -ne $sourceHash) { 'newer build available' } else { '' })
}

# 4. Firewall rules for the program that actually listens, local subnet only
$rules = @(
    @{ Name = $RuleNames[0]; Protocol = 'TCP'; Port = $HttpPort },
    @{ Name = $RuleNames[1]; Protocol = 'UDP'; Port = $DiscoveryPort }
)
foreach ($r in $rules) {
    $existing = Get-NetFirewallRule -DisplayName $r.Name -ErrorAction SilentlyContinue
    $current = if ($existing) { ($existing | Get-NetFirewallApplicationFilter).Program } else { $null }
    $ok = $current -eq $program
    if (-not $ok -and -not $Check) {
        if ($existing) { $existing | Remove-NetFirewallRule }
        New-NetFirewallRule -DisplayName $r.Name -Direction Inbound -Action Allow -Protocol $r.Protocol `
            -LocalPort $r.Port -RemoteAddress LocalSubnet -Profile Private, Public -Program $program | Out-Null
        $ok = $true
    }
    Add-Result "Firewall: $($r.Name)" $(if ($existing) { 'present' } else { 'missing' }) "allow $($r.Protocol) $($r.Port) from LocalSubnet" $ok
}

# 5. Logon task, re-registered if it points at a different program
$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
$ok = $task -and $task.Actions[0].Execute -eq $program -and "$($task.Actions[0].Arguments)" -eq $arguments
if (-not $ok -and -not $Check) {
    $action = if ($arguments) { New-ScheduledTaskAction -Execute $program -Argument $arguments } else { New-ScheduledTaskAction -Execute $program }
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERDOMAIN\$env:USERNAME"
    $principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) `
        -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null
    $ok = $true
}
Add-Result "Scheduled task '$TaskName'" $(if ($task) { 'present' } else { 'missing' }) 'runs agent at logon' $ok

# 6. Fingerprint unlock module (opt-in). Already installed counts as yes, so a re-run updates it.
$unlockInstalled = Test-UnlockModule
$wantUnlock = $Unlock -or ($unlockInstalled -and -not $NoUnlock)
if (-not $Check -and -not $Unlock -and -not $NoUnlock -and -not $unlockInstalled) {
    $wantUnlock = (Read-Host 'Turn on fingerprint unlock? (y/N)') -match '^\s*y'
}
$note = ''
if (-not $Check) {
    if ($wantUnlock -and $UnlockDll -and (Test-Path -LiteralPath $UnlockDll)) {
        try {
            Install-UnlockModule $UnlockDll
        } catch {
            $note = "couldn't copy the DLL (in use by the lock screen?): $($_.Exception.Message)"
        }
    } elseif ($wantUnlock) {
        $note = 'WakeCredentialProvider.dll not found; build it with credential-provider\build.cmd'
    } elseif ($NoUnlock -and $unlockInstalled) {
        Remove-SignIn  # sign-in needs the module; and the module's folder goes only when empty
        Remove-UnlockModule
    }
}
$nowInstalled = Test-UnlockModule
Add-Result 'Fingerprint unlock module' $(if ($nowInstalled) { 'installed' } else { 'not installed' }) `
    $(if ($wantUnlock) { 'installed' } else { 'optional' }) ($nowInstalled -or -not $wantUnlock) $note

# 7. Fingerprint sign-in after shutdown (opt-in). Needs the unlock module and the release download's WakeSignIn.exe.
$signInInstalled = Test-SignIn
$wantSignIn = $SignIn -or ($signInInstalled -and -not $NoSignIn)
$note = ''
if (-not $Check) {
    if (-not $nowInstalled) {
        if ($signInInstalled) { Remove-SignIn }
        if ($SignIn) { $note = 'needs fingerprint unlock (item 6)' }
        $wantSignIn = $false
    } elseif (-not $bundled -or -not (Test-Path (Join-Path $sourceDir 'WakeSignIn.exe'))) {
        if ($wantSignIn) { $note = 'needs the release download and the unlock module' }
    } else {
        if (-not $SignIn -and -not $NoSignIn -and -not $signInInstalled) {
            $wantSignIn = (Read-Host 'Allow fingerprint sign-in after shutdown? (y/N)') -match '^\s*y'
        }
        if ($wantSignIn) {
            try { Install-SignIn $sourceDir } catch { $note = "couldn't install: $($_.Exception.Message)" }
        } elseif ($NoSignIn -and $signInInstalled) {
            Remove-SignIn
        }
    }
}
$signInNow = Test-SignIn
Add-Result 'Sign-in after shutdown' $(if ($signInNow) { 'installed' } else { 'not installed' }) `
    $(if ($wantSignIn) { 'installed' } else { 'optional' }) $(if ($signInNow -and -not (Test-Admin)) { $null } else { $signInNow -or -not $wantSignIn }) $note
if ($signInNow) {
    $task = Get-ScheduledTask -TaskName $SignInTask -ErrorAction SilentlyContinue
    if ($task) {
        $principalOk = $task.Principal.UserId -match 'LOCAL ?SERVICE' -and "$($task.Principal.ProcessTokenSidType)" -eq 'Unrestricted'
        Add-Result "Task '$SignInTask'" "$($task.State), $($task.Principal.UserId)" 'LOCAL SERVICE with its task SID' ($principalOk -and $task.State -ne 'Disabled')
    } else {
        Add-Result "Task '$SignInTask'" 'unknown (needs admin)' 'LOCAL SERVICE with its task SID' $null
    }
    $rule = Get-NetFirewallRule -DisplayName $SignInRule -ErrorAction SilentlyContinue
    $ruleProgram = if ($rule) { ($rule | Get-NetFirewallApplicationFilter).Program } else { $null }
    Add-Result "Firewall: $SignInRule" $(if ($rule) { 'present' } else { 'missing' }) "allow TCP $SignInPort from LocalSubnet" ($ruleProgram -eq $SignInExe)
    $keyThere = Test-Path -LiteralPath (Join-Path $SignInKeyDir 'signin.key')
    Add-Result 'Sign-in key' $(if ($keyThere) { 'present' } else { 'missing' }) 'present once paired' $true `
        $(if ($keyThere) { '' } else { 'written by the agent once a phone is paired' })
    $savedSid = (Get-ItemProperty $WakeKey -Name SignInUserSid -ErrorAction SilentlyContinue).SignInUserSid
    $mySid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    Add-Result 'Sign-in account' $(if ($savedSid -eq $mySid) { 'this account' } elseif ($savedSid) { 'another account' } else { 'not set' }) 'this account' ($savedSid -eq $mySid)
}

# Reported only: BitLocker is what protects the sign-in key and pairing secret on a stolen drive.
$bitLocker = try { "$((Get-BitLockerVolume -MountPoint $env:SystemDrive -ErrorAction Stop).ProtectionStatus)" } catch { 'unknown (needs admin)' }
Add-Result "BitLocker on $env:SystemDrive" $bitLocker 'On (recommended)' $(if ($bitLocker -like 'unknown*') { $null } else { $true }) $(if ($bitLocker -eq 'Off') { 'report only: turn it on in Settings > Privacy & security > Device encryption' } else { '' })

# Reported only: "only allow Windows Hello sign-in for Microsoft accounts" can hide password-based tiles.
$helloOnly = (Get-ItemProperty $PasswordLessKey -Name DevicePasswordLessBuildVersion -ErrorAction SilentlyContinue).DevicePasswordLessBuildVersion -eq 2
Add-Result 'Windows Hello-only sign-in' $(if ($helloOnly) { 'on' } else { 'off' }) 'off' (-not ($helloOnly -and $nowInstalled)) `
    $(if ($helloOnly) { 'may hide the Wake tile: Settings > Accounts > Sign-in options, turn off "only allow Windows Hello sign-in"' } else { '' })

if (-not $Check) {
    Stop-Agent
    Start-ScheduledTask -TaskName $TaskName
}

$results | Format-Table -AutoSize | Out-String -Width 220 | Write-Output
if ($Check) { Write-Output 'Check only: nothing was changed.' }
elseif (($results | Where-Object OK -eq 'NO').Count -eq 0) { Write-Output 'All set. The Wake icon should be in the system tray.' }
