<#
.SYNOPSIS
    Spike client: sends a password to the Wake unlock provider's pipe, the way the agent will.

.DESCRIPTION
    Asks for the password, waits -DelaySeconds (lock the PC with Win+L meanwhile and leave the
    lock screen curtain down), then writes one request frame to \\.\pipe\wake-unlock and logs
    the provider's reply and the pipe's owner SID to -Log. Never logs the password.
#>
param(
    [int]$DelaySeconds = 20,
    [Parameter(Mandatory)][string]$Log
)

$ErrorActionPreference = 'Stop'
$secure = Read-Host 'Windows password' -AsSecureString
Start-Sleep -Seconds $DelaySeconds

function Write-Log([string]$line) { Add-Content -LiteralPath $Log "$(Get-Date -Format s) $line" }

$frame = $null
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
$pipe = New-Object System.IO.Pipes.NamedPipeClientStream('.', 'wake-unlock', [System.IO.Pipes.PipeDirection]::InOut)
try {
    $pipe.Connect(30000)  # lock screen may take a while to load the provider
    $pipe.ReadMode = [System.IO.Pipes.PipeTransmissionMode]::Message

    # Windows PowerShell 5.1 has PipeStream.GetAccessControl(); PowerShell 7 moved it to an extension class.
    $acl = if ($pipe.PSObject.Methods['GetAccessControl']) { $pipe.GetAccessControl() }
           else { [System.IO.Pipes.PipesAclExtensions]::GetAccessControl($pipe) }
    $owner = $acl.GetOwner([System.Security.Principal.SecurityIdentifier]).Value

    # Frame: u32 version = 1, u32 length, password as UTF-16LE (BSTR is already UTF-16LE).
    $length = [Runtime.InteropServices.Marshal]::ReadInt32($bstr, -4)
    $frame = New-Object byte[] (8 + $length)
    [BitConverter]::GetBytes([uint32]1).CopyTo($frame, 0)
    [BitConverter]::GetBytes([uint32]$length).CopyTo($frame, 4)
    [Runtime.InteropServices.Marshal]::Copy($bstr, $frame, 8, $length)
    $pipe.Write($frame, 0, $frame.Length)
    $pipe.Flush()

    $reply = New-Object byte[] 8
    $read = $pipe.Read($reply, 0, 8)
    if ($read -ne 8) {
        Write-Log "short reply ($read bytes) owner=$owner"
    } else {
        $status = [BitConverter]::ToUInt32($reply, 0)
        $substatus = [BitConverter]::ToUInt32($reply, 4)
        Write-Log ('status=0x{0:X8} substatus=0x{1:X8} owner={2}' -f $status, $substatus, $owner)
    }
} catch {
    Write-Log "failed: $($_.Exception.Message)"
    throw
} finally {
    if ($frame) { [Array]::Clear($frame, 0, $frame.Length) }
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    $pipe.Dispose()
}
