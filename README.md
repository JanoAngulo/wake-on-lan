# Wake

Wake up, shut down, reboot, sleep and lock your Windows PC from your Android phone, over your home Wi-Fi.

Wake is two small programs:

- **Wake for Android**: a dark, one-screen remote. Tap to wake the PC, hold to shut it down. It shows whether the PC is really on, how fast it answers, and when it has finished shutting down.
- **Wake agent for Windows**: a tray app that runs when you log in and carries out shutdown, reboot, sleep and lock. It only accepts commands from the phone you paired with.

Waking uses standard Wake-on-LAN (a "magic packet"), so it works even when the PC is fully shut down and the agent isn't running.

## Features

- Wake from full shutdown or sleep with one tap
- Hold-to-confirm shutdown and reboot, with a 3-second Undo
- Live status: on, off, waking, shutting down, with response time and uptime
- Finds your PC automatically: no typing MAC addresses
- Pairing with a 6-digit code; every command is signed, and replays are rejected
- Several PCs, folders and "Wake all"
- Import and export your device list as JSON
- TalkBack support, large-text support and reduced-motion support

## What you need

| | |
|---|---|
| PC | Windows 10 or 11, **wired Ethernet recommended** (most Wi-Fi cards can't wake from shutdown), [JDK 21](https://adoptium.net/) |
| Phone | Android 13 or newer |
| Network | Phone and PC on the same home network |

Wake only works on your home network. To wake the PC from outside, you'd need a VPN into your home network with another always-on device on it; that's not part of this project.

## Set up the PC

1. **Turn on Wake-on-LAN in the BIOS/UEFI.** Look for "Wake on LAN", "Resume by PCI-E device" or "Power on by PCI-E", and turn **off** "ErP Ready" or "Deep sleep" if your board has it. The names differ between manufacturers.
2. **Build the agent** (or download a release, if one is published):
   ```bash
   ./gradlew :agent:agentJar
   ```
3. **Double-click `Install Wake Agent.cmd`** and choose Yes when Windows asks for permission. The installer:
   - turns on Wake on Magic Packet for your wired network adapter,
   - turns off Windows Fast Startup, so Shut down is a real shutdown the PC can wake from,
   - allows the agent through Windows Firewall for your local network only,
   - starts the agent every time you log in.

   To see what it would change first, without changing anything:
   ```powershell
   powershell -ExecutionPolicy Bypass -File pc-setup\setup.ps1 -Check
   ```
4. Look for the **green power icon** in the system tray. Click the **^** arrow if it's hidden.

## Set up the phone

1. Build and install the app (see [Building](#building)), or install a release APK if one is published.
2. Join the same Wi-Fi as the PC and open Wake. It searches for your PC.
3. On the PC, right-click the green tray icon and choose **Pair phone…**. A 6-digit code appears for 2 minutes.
4. In the app, pick your PC, type the code and tap **Pair**.

The app has the same steps under **Settings → How to connect**.

## How it works

```
Phone ──UDP broadcast, magic packet─────────▶ PC network card   (wake: works while the PC is off)
Phone ──UDP broadcast, discovery────────────▶ agent             (find PC: name, IP, MAC)
Phone ──HTTP, HMAC-signed requests──────────▶ agent             (status, shutdown, reboot, sleep, lock)
```

- **Pairing:** an X25519 key exchange confirmed by the 6-digit code. The phone has to prove it knows the code before the agent sends anything derived from it, so nobody on the network can learn the code from the traffic.
- **Commands** are signed with HMAC-SHA256 over the method, path, timestamp, a one-time nonce and the body. The agent rejects bad signatures, requests more than 30 seconds old and reused nonces.
- **Shutdown and reboot** are forced, so open apps close. They run 3 seconds after the request, which is what makes Undo possible.
- **Sleep** never hibernates. **Lock** needs the agent running in your logged-in session, which is why the agent starts at login rather than as a Windows service. Until you log in after waking the PC, only wake and status work.

Ports used: UDP 9 (Wake-on-LAN), TCP 47613 (agent API), UDP 47614 (discovery). TCP 135 is only used to check that the PC is up.

## Building

Requirements: JDK 21 and the Android SDK (platform 36). Gradle is included through the wrapper.

```bash
./gradlew test                   # unit tests for every module
./gradlew :agent:agentJar        # agent/build/libs/wake-agent.jar
./gradlew :app:assembleRelease   # app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/release/app-release.apk
```

Release builds are signed with the keystore named in a `keystore.properties` file in the project root, which you create yourself and which is git-ignored. Without that file the release build uses the debug key. Details are in [docs/RUNBOOK.md](docs/RUNBOOK.md).

## Project layout

| Path | What's in it |
|---|---|
| `protocol/` | Shared Kotlin: magic packet, wire messages, request signing, pairing crypto |
| `agent/` | Windows agent: HTTP API, discovery, tray icon, power actions |
| `app/` | Android app: Jetpack Compose UI, status logic, storage |
| `pc-setup/` | PC setup script and notes |
| `docs/` | Design spec, implementation plan, clickable design prototype, runbook |

## Troubleshooting

- **The PC doesn't wake from shutdown.** Check the BIOS settings above and run `pc-setup\setup.ps1 -Check`. Every line should say yes.
- **"No PC answered" when searching.** Make sure the green tray icon is there and that the phone is on the same network as the PC.
- **"On, waiting for agent".** The PC is up but nobody is logged in yet. Log in and the commands become available.
- **Agent log:** `%LOCALAPPDATA%\Wake\agent.log`.
