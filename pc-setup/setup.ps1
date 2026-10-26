<#
.SYNOPSIS
    One-time PC setup for the Wake agent. Safe to run more than once.

.DESCRIPTION
    -Check   Read-only. Shows each item as Current / Wanted / OK. No admin needed.
    (none)   Applies the changes. Run from an elevated PowerShell.

    Items:
      1. Wired adapter: Wake on Magic Packet and Shutdown Wake-On-Lan enabled.
      2. Fast Startup off, so shutdown is a real shutdown that Wake-on-LAN can wake from.
      3. Firewall: agent ports 47613/TCP and 47614/UDP allowed from the local subnet only.
      4. Agent copied to %LOCALAPPDATA%\Wake and started at logon by a scheduled task.

    Green Ethernet and Power Saving Mode are only reported. Wake-on-LAN already works on
    this PC with them enabled, and changing adapter settings resets the network link.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File pc-setup\setup.ps1 -Check
    powershell -ExecutionPolicy Bypass -File pc-setup\setup.ps1 -AgentJar agent\build\libs\wake-agent.jar
#>
param(
    [switch]$Check,
    [string]$AgentJar = "$PSScriptRoot\..\agent\build\libs\wake-agent.jar"
)

$ErrorActionPreference = 'Stop'
$HttpPort = 47613
$DiscoveryPort = 47614
$TaskName = 'Wake Agent'
$InstallDir = Join-Path $env:LOCALAPPDATA 'Wake'
$InstalledJar = Join-Path $InstallDir 'wake-agent.jar'
$PowerKey = 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Power'

function Test-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    (New-Object Security.Principal.WindowsPrincipal $id).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Get-LanAdapter {
    $gateway = Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway -and $_.NetAdapter.Status -eq 'Up' } |
        Where-Object { $_.InterfaceDescription -notmatch 'Radmin|VPN|Virtual|Hyper-V|TAP' } |
        Select-Object -First 1
    if (-not $gateway) { throw 'No wired LAN adapter with a default gateway was found.' }
    Get-NetAdapter -InterfaceIndex $gateway.InterfaceIndex
}

function Get-Javaw {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += Join-Path $env:JAVA_HOME 'bin\javaw.exe' }
    $candidates += 'C:\Program Files\Java\jdk-21\bin\javaw.exe'
    $onPath = Get-Command javaw.exe -ErrorAction SilentlyContinue
    if ($onPath) { $candidates += $onPath.Source }
    $found = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $found) { throw 'javaw.exe (JDK 21) not found.' }
    $found
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

# 3. Firewall rules, local subnet only
$javaw = Get-Javaw
$rules = @(
    @{ Name = 'Wake Agent HTTP'; Protocol = 'TCP'; Port = $HttpPort },
    @{ Name = 'Wake Agent Discovery'; Protocol = 'UDP'; Port = $DiscoveryPort }
)
foreach ($r in $rules) {
    $existing = Get-NetFirewallRule -DisplayName $r.Name -ErrorAction SilentlyContinue
    $ok = [bool]$existing
    if (-not $ok -and -not $Check) {
        New-NetFirewallRule -DisplayName $r.Name -Direction Inbound -Action Allow -Protocol $r.Protocol `
            -LocalPort $r.Port -RemoteAddress LocalSubnet -Profile Private, Public -Program $javaw | Out-Null
        $ok = $true
    }
    Add-Result "Firewall: $($r.Name)" $(if ($existing) { 'present' } else { 'missing' }) "allow $($r.Protocol) $($r.Port) from LocalSubnet" $ok
}

# 4. Agent install and logon task
$jarSource = Resolve-Path $AgentJar -ErrorAction SilentlyContinue
$installedHash = if (Test-Path $InstalledJar) { (Get-FileHash $InstalledJar).Hash } else { $null }
$sourceHash = if ($jarSource) { (Get-FileHash $jarSource).Hash } else { $null }
$ok = $installedHash -and ($installedHash -eq $sourceHash -or -not $sourceHash)
if (-not $ok -and -not $Check) {
    if (-not $jarSource) { throw "Agent jar not found at $AgentJar. Build it with: .\gradlew :agent:agentJar" }
    New-Item -ItemType Directory -Force $InstallDir | Out-Null
    Copy-Item $jarSource $InstalledJar -Force
    $ok = $true
}
Add-Result 'Agent jar installed' $(if ($installedHash) { 'present' } else { 'missing' }) $InstalledJar $ok $(if ($installedHash -and $sourceHash -and $installedHash -ne $sourceHash) { 'newer build available' } else { '' })

$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
$ok = [bool]$task
if (-not $ok -and -not $Check) {
    $action = New-ScheduledTaskAction -Execute $javaw -Argument "-jar `"$InstalledJar`""
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERDOMAIN\$env:USERNAME"
    $principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero)
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Principal $principal -Settings $settings | Out-Null
    Start-ScheduledTask -TaskName $TaskName
    $ok = $true
}
Add-Result "Scheduled task '$TaskName'" $(if ($task) { 'present' } else { 'missing' }) 'runs agent at logon' $ok

$results | Format-Table -AutoSize | Out-String -Width 220 | Write-Output
if ($Check) { Write-Output 'Check only: nothing was changed.' }
elseif (($results | Where-Object OK -eq 'NO').Count -eq 0) { Write-Output 'All set. The Wake icon should be in the system tray.' }
