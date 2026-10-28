# PC setup

One-time setup on your Windows PC so the phone can wake it, check on it, and send commands.

## 1. Check what's already set (safe, changes nothing)

```powershell
powershell -ExecutionPolicy Bypass -File pc-setup\setup.ps1 -Check
```

## 2. Apply (run PowerShell as administrator)

```powershell
.\gradlew :agent:agentJar
powershell -ExecutionPolicy Bypass -File pc-setup\setup.ps1 -AgentJar agent\build\libs\wake-agent.jar
```

The script:

- confirms Wake on Magic Packet and Shutdown Wake-On-Lan are enabled on the wired adapter;
- keeps Fast Startup off, so "Shut down" is a real shutdown the phone can wake the PC from;
- allows the agent's ports (47613 TCP, 47614 UDP) from your home network only;
- installs the agent to `%LOCALAPPDATA%\Wake` and starts it at every logon;
- asks `Turn on fingerprint unlock? (y/N)`. Yes copies `WakeCredentialProvider.dll` (pass it with `-UnlockDll`) to `C:\Program Files\Wake` and registers it as an extra lock-screen option. `-Unlock` or `-NoUnlock` answers for you; `-NoUnlock` also removes it if installed.

- with fingerprint unlock on and the release download, asks `Allow fingerprint sign-in after shutdown? (y/N)`. Yes copies `WakeSignIn.exe` to `C:\Program Files\Wake\SignIn` and adds the startup task "Wake Sign-in". The task runs it before anyone signs in, as LOCAL SERVICE with its own task SID and no extra privileges. It listens on 47615 TCP, from your home network only, and only while the sign-in or lock screen is up. `-SignIn` or `-NoSignIn` answers for you; `-NoSignIn` also removes it.

`-Check` also reports whether BitLocker protects C: (turn it on if it's off; it's what protects Wake's keys on a stolen drive), and whether *only allow Windows Hello sign-in for Microsoft accounts* is on, since it can hide password-based lock-screen options. Recovery steps if the lock screen misbehaves are in [docs/RUNBOOK.md](../docs/RUNBOOK.md#fingerprint-unlock-recovery).

It only changes what isn't already right, so running it again is safe. If it does change an adapter setting, the network drops for a few seconds.

## 3. BIOS (only if waking from shutdown stops working)

Wake-on-LAN from full shutdown already works on this PC, so you shouldn't need this. If it ever stops working, look in the BIOS (MSI: press Delete at startup) for:

- **Wake Up Event Setup → Resume By PCI-E Device (or LAN): Enabled**
- **ErP Ready: Disabled** (ErP cuts power to the network card when the PC is off)

## 4. Pair your phone

Right-click the Wake icon in the system tray, choose **Pair phone…**, and type the 6-digit code into the app.

## Files

- Agent: `%LOCALAPPDATA%\Wake\wake-agent.jar`
- Log: `%LOCALAPPDATA%\Wake\agent.log`
- Pairing secret: `%APPDATA%\Wake\secret.bin` (readable only by your Windows account)
- Sign-in after shutdown: program in `C:\Program Files\Wake\SignIn`; its key (derived from the pairing secret, DPAPI-encrypted) in `C:\ProgramData\Wake\signin`; its log at `C:\ProgramData\Wake\logs\signin.log`

To remove everything, run `setup.ps1 -Uninstall` as administrator.
