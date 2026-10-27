<#
.SYNOPSIS
    Installs the Wake unlock credential provider for testing. Run elevated, in a VM first.

.DESCRIPTION
    Copies the DLL to C:\Program Files\Wake (admin-only: it runs as SYSTEM inside LogonUI) and registers
    it as a credential provider. The normal password and PIN tiles are untouched. Undo with uninstall-dev.ps1.
#>
#Requires -RunAsAdministrator
param([Parameter(Mandatory)][string]$Dll)

$ErrorActionPreference = 'Stop'
$clsid = '{8C5D3B0E-6A41-4C7E-9F2B-5D1A7E3C9B04}'
$dir = Join-Path $env:ProgramFiles 'Wake'
$target = Join-Path $dir 'WakeCredentialProvider.dll'

New-Item -ItemType Directory -Force $dir | Out-Null
Copy-Item -LiteralPath $Dll -Destination $target -Force

$com = "HKLM:\SOFTWARE\Classes\CLSID\$clsid"
New-Item -Force $com | Out-Null
Set-ItemProperty $com '(default)' 'Wake unlock'
New-Item -Force "$com\InprocServer32" | Out-Null
Set-ItemProperty "$com\InprocServer32" '(default)' $target
Set-ItemProperty "$com\InprocServer32" 'ThreadingModel' 'Apartment'

$provider = "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\Credential Providers\$clsid"
New-Item -Force $provider | Out-Null
Set-ItemProperty $provider '(default)' 'Wake unlock'

Write-Host "Installed $target. Lock (Win+L) to see the Wake tile."
