# PC setup

One-time setup on MY-PC so the phone can wake it, check on it, and send commands.

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
- installs the agent to `%LOCALAPPDATA%\Wake` and starts it at every logon.

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

To remove everything: delete the scheduled task "Wake Agent", the two "Wake Agent" firewall rules, and the two folders above.
