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
    [string]$UnlockDll
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

function Get-LanAdapter {
    $gateway = Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway -and $_.NetAdapter.Status -eq 'Up' } |
        Where-Object { $_.InterfaceDescription -notmatch 'Radmin|VPN|Virtual|Hyper-V|TAP|Wi-?Fi|Wireless' } |
        Select-Object -First 1
    if (-not $gateway) { throw 'No wired network adapter with internet access was found. Wake-on-LAN needs a cable.' }
    Get-NetAdapter -InterfaceIndex $gateway.InterfaceIndex
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

function Install-UnlockModule($dll) {
    New-Item -ItemType Directory -Force (Split-Path $UnlockTarget) | Out-Null
    Copy-Item -LiteralPath $dll -Destination $UnlockTarget -Force
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
    if (-not (Test-Path -LiteralPath $UnlockTarget)) { return }
    try {
        Remove-Item -LiteralPath $UnlockTarget -Force
        Remove-Item (Split-Path $UnlockTarget) -ErrorAction SilentlyContinue
    } catch {
        Add-Type -Namespace Wake -Name Native -MemberDefinition @'
[DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
public static extern bool MoveFileEx(string existing, string replacement, int flags);
'@
        [void][Wake.Native]::MoveFileEx($UnlockTarget, $null, 4)  # MOVEFILE_DELAY_UNTIL_REBOOT
        Write-Output "$UnlockTarget is in use; it will be deleted at the next reboot."
    }
}

# Exact keyword match: '*' in keywords like '*EEE' would otherwise act as a wildcard.
function Get-AdapterProperty($adapterName, $keyword) {
    Get-NetAdapterAdvancedProperty -Name $adapterName -ErrorAction SilentlyContinue |
        Where-Object { $_.RegistryKeyword -eq $keyword } | Select-Object -First 1
}

$results = New-Object System.Collections.Generic.List[object]
function Add-Result($item, $current, $wanted, $ok, $note = '') {
    $results.Add([pscustomobject]@{ Item = $item; Current = "$current"; Wanted = "$wanted"; OK = $(if ($ok) { 'yes' } else { 'NO' }); Note = $note })
}

if (-not $Check -and -not (Test-Admin)) {
    throw 'Run this from an elevated PowerShell (Run as administrator), or add -Check for a read-only report.'
}

if ($Uninstall) {
    Stop-Agent
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
    $RuleNames | ForEach-Object { Remove-NetFirewallRule -DisplayName $_ -ErrorAction SilentlyContinue }
    Remove-Item $InstallDir, $SecretDir -Recurse -Force -ErrorAction SilentlyContinue
    Remove-UnlockModule
    Write-Output 'Wake agent removed: logon task, firewall rules, files, pairing and the fingerprint unlock module.'
    Write-Output 'Network adapter and Fast Startup settings were left as they are.'
    return
}

# Which agent are we installing, and what program will run it?
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
$adapter = Get-LanAdapter
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
        Remove-UnlockModule
    }
}
$nowInstalled = Test-UnlockModule
Add-Result 'Fingerprint unlock module' $(if ($nowInstalled) { 'installed' } else { 'not installed' }) `
    $(if ($wantUnlock) { 'installed' } else { 'optional' }) ($nowInstalled -or -not $wantUnlock) $note

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
