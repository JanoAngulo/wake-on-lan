<#
.SYNOPSIS
    Removes the Wake unlock credential provider installed by install-dev.ps1. Run elevated.

.DESCRIPTION
    Deletes both registry keys, then the DLL. If LogonUI still has the DLL loaded, the file is
    scheduled for deletion at the next reboot instead.
#>
#Requires -RunAsAdministrator
$ErrorActionPreference = 'Stop'
$clsid = '{8C5D3B0E-6A41-4C7E-9F2B-5D1A7E3C9B04}'
$target = Join-Path $env:ProgramFiles 'Wake\WakeCredentialProvider.dll'

foreach ($key in "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\Credential Providers\$clsid",
                 "HKLM:\SOFTWARE\Classes\CLSID\$clsid") {
    if (Test-Path $key) { Remove-Item -Recurse -Force $key }
}

if (Test-Path -LiteralPath $target) {
    try {
        Remove-Item -LiteralPath $target -Force
        Write-Host "Removed $target."
    } catch {
        Add-Type -Namespace Wake -Name Native -MemberDefinition @'
[DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
public static extern bool MoveFileEx(string existing, string replacement, int flags);
'@
        $MOVEFILE_DELAY_UNTIL_REBOOT = 4
        if (-not [Wake.Native]::MoveFileEx($target, $null, $MOVEFILE_DELAY_UNTIL_REBOOT)) {
            throw "Couldn't schedule $target for deletion (error $([Runtime.InteropServices.Marshal]::GetLastWin32Error()))."
        }
        Write-Host "$target is in use; it will be deleted at the next reboot."
    }
}
Write-Host 'Wake unlock provider removed.'
