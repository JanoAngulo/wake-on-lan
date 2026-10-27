# Wake

Wake up, shut down, reboot, sleep and lock your Windows PC from your Android phone, over your home Wi-Fi.

## Download

From the [latest release](https://github.com/JanoAngulo/wake-on-lan/releases/latest):

| File | Install on |
|---|---|
| **Wake.apk** | your Android phone (Android 13 or newer) |
| **WakeAgent-Windows.zip** | your Windows 10/11 PC. Includes everything it needs, no Java install required |

## Set up (about 5 minutes)

### 1. On the PC

1. Unzip **WakeAgent-Windows.zip** anywhere, for example in Downloads.
2. Double-click **Install Wake Agent.cmd** and choose **Yes** when Windows asks for permission.
3. When it says **Done**, look for the **green power icon** in the system tray at the bottom right. Click the **^** arrow if it's hidden.

The installer:
- turns on Wake on Magic Packet for your wired network adapter,
- turns off Windows Fast Startup, so Shut down is a real shutdown the PC can wake from,
- allows the agent through Windows Firewall for your local network only,
- starts the agent every time you log in.

You only do this once. Run it again to update; run **Uninstall Wake Agent.cmd** to remove everything.

**Waking from shutdown also needs one BIOS/UEFI setting.** Restart, open the BIOS setup (usually Delete or F2 at startup), turn on **Wake on LAN** (sometimes called "Resume by PCI-E device" or "Power on by PCI-E"), and turn off **ErP Ready** or **Deep Sleep** if your board has it.

### 2. On the phone

1. Copy **Wake.apk** to the phone and open it. Allow installing from that source if Android asks.
2. Connect the phone to the **same Wi-Fi** as the PC and open **Wake**. It finds your PC on its own.
3. On the PC, right-click the green tray icon and choose **Pair phone…**. A 6-digit code appears for 2 minutes.
4. In the app, tap your PC, type the code and tap **Pair**.

That's it. The same steps are in the app under **Settings → How to connect**.

## Using it

<table>
  <tr>
    <td align="center" width="25%"><img src="docs/screenshots/off.png" alt="PC is off. The big power button says Tap to wake, and Response shows Off since 23:02." width="200"></td>
    <td align="center" width="25%"><img src="docs/screenshots/waking.png" alt="Waking up, 18 seconds in. A ring spins around the power button and Response shows Waiting." width="200"></td>
    <td align="center" width="25%"><img src="docs/screenshots/on.png" alt="PC is on for 2 hours 14 minutes. The button turns into a green ring and Sleep, Lock, Reboot and Shut down are enabled." width="200"></td>
    <td align="center" width="25%"><img src="docs/screenshots/undo.png" alt="After holding Shut down, a bar at the bottom says Shutting down in 3s with an Undo button." width="200"></td>
  </tr>
  <tr>
    <td align="center"><b>1. Tap to wake</b></td>
    <td align="center"><b>2. Wait for it</b></td>
    <td align="center"><b>3. It's on</b></td>
    <td align="center"><b>4. Shut down, or undo</b></td>
  </tr>
</table>

1. **Wake.** When the PC is off, tap the big power button.
2. **Wait.** The ring spins while the PC boots, usually 20 to 40 seconds. If it hasn't answered after 90 seconds, the app tells you and offers to retry.
3. **Use it.** Once the PC is on, the button turns into a green ring showing how long it's been up. **Sleep** and **Lock** work with a tap.
4. **Turn it off.** **Reboot** and **Shut down** need a 1-second hold so a stray tap can't close your apps. You then get 3 seconds to tap **Undo**.

Have more than one PC? Tap the PC name at the top to switch, or use **Settings** to add another one.

## What it does

- **Wake** the PC from full shutdown or sleep with one tap
- **Shut down and reboot** with hold-to-confirm and a 3-second Undo. These are forced, so open apps close.
- **Sleep and lock** with one tap
- **Live status**: on, off, waking, shutting down, with response time and uptime
- **Several PCs**, folders and "Wake all"
- **Import and export** your device list
- Works with TalkBack, large text and reduced motion

## Good to know

- **Home network only.** The phone and PC must be on the same network.
- **Use a network cable on the PC.** Most Wi-Fi cards can't wake a PC from shutdown.
- **Log in after waking.** Waking works any time, but shut down, reboot, sleep and lock work once you're logged in on the PC, because the agent runs in your session.
- **Only your phone can send commands.** Pairing uses a key exchange confirmed by the 6-digit code, and every command is signed. Other devices on your Wi-Fi can't shut your PC down.

## Troubleshooting

| Problem | Try this |
|---|---|
| The PC doesn't wake from shutdown | Check the BIOS setting above. On the PC, run `powershell -ExecutionPolicy Bypass -File pc-setup\setup.ps1 -Check` inside the unzipped folder; every line should say yes. |
| "No PC answered" when searching | Make sure the green tray icon is there and the phone is on the same Wi-Fi. |
| "On, waiting for agent" | The PC is on but nobody is logged in yet. Log in. |
| Pairing fails | Start pairing again from the tray icon; the code lasts 2 minutes and works once. |

The agent's log is at `%LOCALAPPDATA%\Wake\agent.log`.

---

## For developers

### How it works

```
Phone ──UDP broadcast, magic packet─────────▶ PC network card   (wake: works while the PC is off)
Phone ──UDP broadcast, discovery────────────▶ agent             (find PC: name, IP, MAC)
Phone ──HTTP, HMAC-signed requests──────────▶ agent             (status, shutdown, reboot, sleep, lock)
```

- **Pairing:** an X25519 key exchange confirmed by the 6-digit code. The phone proves the code first; the agent's proof comes back only afterwards, so the traffic gives nothing away for offline guessing.
- **Commands:** HMAC-SHA256 over the method, path, timestamp, a one-time nonce and the body. The agent rejects bad signatures, requests more than 30 seconds old and reused nonces. Clock corrections are accepted only with an HMAC proof from the agent.
- **Shutdown and reboot** run 3 seconds after the request, which is what makes Undo possible. **Sleep** never hibernates. **Lock** needs the agent running in the logged-in session.
- **Ports:** UDP 9 (Wake-on-LAN), TCP 47613 (agent API), UDP 47614 (discovery). TCP 135 is only used to check that the PC is up.

### Building

Requirements: JDK 21 and the Android SDK (platform 36). Gradle is included through the wrapper.

```bash
./gradlew test                    # unit tests for every module
./gradlew :app:assembleRelease    # app/build/outputs/apk/release/app-release.apk
./gradlew :agent:agentZip         # build/release/WakeAgent-Windows.zip (agent + bundled Java runtime)
./gradlew :agent:agentJar         # agent/build/libs/wake-agent.jar (runs on an installed JDK 21)
```

Release builds are signed with the keystore named in a `keystore.properties` file in the project root, which you create yourself and which is git-ignored. Without it, the debug key is used. See [docs/RUNBOOK.md](docs/RUNBOOK.md).

In a source checkout, **Install Wake Agent.cmd** builds and installs the jar version if the bundled `WakeAgent` folder isn't next to it.

### Project layout

| Path | What's in it |
|---|---|
| `protocol/` | Shared Kotlin: magic packet, wire messages, request signing, pairing crypto |
| `agent/` | Windows agent: HTTP API, discovery, tray icon, power actions, packaging |
| `app/` | Android app: Jetpack Compose UI, status logic, storage |
| `pc-setup/` | Install, update and uninstall script |
| `docs/` | Design spec, implementation plan, clickable design prototype, runbook |
