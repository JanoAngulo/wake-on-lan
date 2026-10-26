# Wake: personal Wake-on-LAN remote for MY-PC

Date: 2026-10-26
Visual reference: [`docs/design/prototype.html`](../design/prototype.html) (open in a browser; clickable, includes motion)

## 1. Intent

John sleeps in a loft bed. The PC (MY-PC) is on the desk below. Climbing down to switch it off at night, or on in the morning, is the annoyance this app removes. From the phone in bed he taps to wake the PC and holds a button to shut it down, and the app tells him truthfully whether the PC is on or off so he never has to climb down to check.

This is a personal tool for one phone and one PC on one home network. It is sideloaded over ADB and never published. It replaces Wolow (a Flutter Wake-on-LAN app from the Play Store) and includes everything Wolow Premium offers that makes sense on a home LAN, without ads, billing, analytics or a cloud backend.

## 2. Goals, non-goals, anti-goals

**Goals**

1. Wake MY-PC from full shutdown (and from sleep) with one tap.
2. Shut down, reboot, sleep and lock MY-PC from the phone.
3. Show the PC's real state at a glance: off, waking, on, shutting down, plus the failure and edge states listed in §6.
4. Set up without typing a MAC address: the phone finds the PC's agent on the network.
5. Everything Wolow Premium had that applies here: awake status with latency, shutdown/reboot/sleep/lock, network scan, device folders and bulk wake, import/export of devices. "Network scan" means agent discovery (Find my PC, §5.1), which is what Wolow's scan was too: it only found machines running Wolow Companion. There is no general subnet sweep (Android can't read MACs from the ARP table anyway). MY-PC is the only device; there is nothing to migrate from Wolow, so no Wolow-format importer.
6. Feel deliberate and calm at night: dark theme, no glare, good motion, good accessibility.

**Non-goals**

- Waking or controlling the PC from outside the home network. Investigated and dropped: the ISP uses CGNAT (traceroute hop 2 is `100.64.0.1`), so port forwarding cannot work, and the user chose not to add an always-on relay device.
- Play Store distribution, billing, ads, analytics, crash reporting, accounts, cloud sync.
- Home-screen widgets, Quick Settings tiles, app shortcuts (user declined).
- Light theme, landscape layout, tablets, iOS.

**Anti-goals** (would make it a failure even if it technically works)

- Anyone else on the home Wi-Fi being able to shut down or reboot the PC.
- The app claiming success it didn't verify (showing "On" or "Off" based on a guess).
- An accidental tap in bed shutting the PC down.
- A bright screen blinding the user in a dark room.
- Waiting forever with a spinner when the wake didn't work.

## 3. Known environment

| Item | Value |
|---|---|
| Phone | Samsung Galaxy S26 Ultra, model SM-S948B, Android 17 (API 37), 1440×3120 at 560 dpi ≈ **412 × 891 dp** |
| Phone connection to dev PC | Wireless ADB (TLS), serial `<serial>` |
| PC | MY-PC, Windows 11 Pro 10.0.26200 |
| PC network adapter | Realtek PCIe GbE Family Controller, wired, 1 Gbps, MAC `0A:1B:2C:3D:4E:5F` (MSI OUI) |
| PC address | `192.168.1.14` (DHCP), gateway `192.168.1.1` |
| Home subnet | `192.168.1.0/24`, broadcast `192.168.1.255` |
| Router | ISP-supplied fiber router (ISP-supplied fiber ONT). No Wake-on-LAN feature. |
| Also on the PC | Radmin VPN adapter (`26.x.x.x`). Must be ignored when choosing the LAN interface. |
| Dev toolchain (same PC) | JDK 21 (`C:\Program Files\Java\jdk-21`), Android SDK at `%LOCALAPPDATA%\Android\Sdk` with platforms 34–36 and build-tools 34–36. No Android Studio, no global Gradle. `adb` is not on PATH: `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`. Node 26 present (unused). |

The dev PC and the target PC are the same machine. Consequences:

- Wake-on-LAN from full shutdown is already proven on this PC (Wolow woke it), so BIOS and adapter settings are known-good; the setup script mostly verifies them.
- Anything that interrupts this PC is done by John from a checklist, never by the builder: running the elevated setup script (it resets the network adapter and drops wireless ADB briefly) and every real shutdown, reboot, sleep and lock test. The builder stops at those checkpoints and waits for John's result.

## 4. Architecture

Two programs, one Gradle project.

```
wake/
  protocol/   Kotlin (JVM) library shared by both: magic packet builder, message types, signing
  app/        Android app: Kotlin, Jetpack Compose, Material 3
  agent/      PC agent: Kotlin on the JVM (JDK 21), runs in the user's Windows session
```

*(My call: Kotlin for both so there is one language, one build, and shared protocol code. JDK 21 is already installed.)*

```
Phone (app) ──UDP broadcast magic packet──▶ MY-PC NIC  (wake; PC is off, no agent involved)
Phone (app) ──UDP broadcast discovery─────▶ agent         (find PC: name, IP, MAC, port)
Phone (app) ──HTTP, signed requests───────▶ agent         (status, uptime, commands)
Phone (app) ──TCP connect probe───────────▶ PC            (is the PC up even if the agent isn't)
```

### 4.1 Wake

- Magic packet: 6 × `0xFF` followed by the target MAC repeated 16 times (102 bytes), sent over UDP with `SO_BROADCAST` to the device's broadcast address and port (defaults `192.168.1.255:9`).
- Send it 3 times about 100 ms apart. *(my call: cheap insurance against a dropped datagram)*
- Wake works without the agent and without knowing the PC's current IP.

### 4.2 Status

The app needs two independent signals so it can tell "off" from "on but agent not running":

1. **Host up:** a TCP connect probe to the PC's IP. Either a completed connection or a connection refused counts as "up"; a timeout means "not answering". Probe the agent port plus one Windows port that normally answers (445 or 135). The connect time is the latency shown as "Response". (Android apps can't send raw ICMP, so TCP is the reliable probe.) Windows Firewall may silently drop 445/135 on this PC; the builder verifies what actually answers and, if needed, the setup script adds a Private-profile rule so the "on, agent not running" state stays detectable.
2. **Agent up:** `GET /status` on the agent succeeds and its signature verifies.

Polling:

- Every 2 s while the app is in the foreground. Stop in the background.
- "Off" only after 3 consecutive host-down results (about 6 s), so one dropped probe doesn't flash "Off".
- **Waking** ends when the host answers, or after **90 s** it becomes **Didn't wake**.
- **Shutting down** ends when the host stops answering (3 misses), then the state becomes Off.

The latency qualifier ("steady" or "slow") is a small rule: steady when the median of the last 5 probes is ≤ 20 ms. *(my call; builder may tune)*

### 4.3 PC agent

- Runs in the logged-in user's session, started at logon by Task Scheduler. It must run in the interactive session because Lock (`LockWorkStation`) only works there.
- System tray icon with a small window that shows pairing status, the 6-digit pairing code when pairing, and Quit.
- Listens on the LAN interface only, meaning the interface whose IPv4 is in the same subnet as the default gateway. It never binds to Radmin VPN or other virtual adapters.
- **Discovery:** answers a UDP broadcast probe with its hostname, IPv4, MAC of that interface, HTTP port and agent version.
- **HTTP API** (JSON):

| Method | Path | Purpose |
|---|---|---|
| GET | `/status` | hostname, ip, mac, uptime seconds, agent version, pending command if any |
| POST | `/pair` | X25519 key exchange confirmed with the 6-digit code shown on the PC (each side sends an HMAC of the exchange transcript keyed by the code), so a Wi-Fi sniffer never sees the secret. Derives the shared secret. Only open while the tray window is in pairing mode; one wrong code ends pairing mode. |
| POST | `/command` | body: `shutdown` \| `reboot` \| `sleep` \| `lock`. Shutdown and reboot are scheduled 3 s ahead and return a command id. |
| POST | `/command/cancel` | body: command id. Cancels a pending shutdown or reboot (the Undo button). |

- **Auth:** every request except discovery and `/pair` carries an HMAC-SHA256 signature over method, path, body, timestamp and nonce, keyed by the paired secret. The agent rejects bad signatures, timestamps more than 30 s off, and reused nonces. *(My call. Plain HTTP on the LAN with a bearer token would let anyone sniffing the Wi-Fi replay a shutdown. HMAC with a nonce prevents that without certificate management.)*
- **Commands:**
  - Shutdown: `shutdown /s /f /t 0` when the 3 s timer fires. Forced, as the user explicitly wants: open apps close, unsaved work is lost.
  - Reboot: `shutdown /r /f /t 0` when the 3 s timer fires.
  - Sleep: real sleep (S3/modern standby), not hibernate. `rundll32 powrprof.dll,SetSuspendState` hibernates when hibernation is enabled, so call `SetSuspendState(FALSE, FALSE, FALSE)` directly (JNA or a tiny helper) or otherwise make sure it doesn't hibernate.
  - Lock: `LockWorkStation()`.
- **Secret storage:** agent stores the secret under `%APPDATA%\Wake\` with user-only ACLs. The phone stores it with Android Keystore-backed encryption.
- Agent must be one paired phone at a time; re-pairing replaces the old secret. *(my call)*

### 4.4 Data on the phone

A device record: id, name, MAC, broadcast address, port, last known IP, subnet, folder id (nullable), pairing secret (nullable), agent port. Folders: id, name. Settings: poll interval (default 2 s), haptics on/off (default on).

- Store with DataStore or Room (builder's call).
- **Import/export:** a JSON file through Android's file picker (Storage Access Framework). Secrets are never exported. Imported devices need re-pairing for commands.

## 5. Screens and flows

### 5.1 First run: "Find my PC"

1. Empty state: a short explanation and a primary button, **Find my PC**. A secondary link, **Add manually**.
2. Find my PC sends the discovery broadcast and lists the agents that answer (name and IP). If none answer within 5 s, the empty result explains the two likely causes (agent not installed or not running; Windows Firewall) and offers **Try again** and **Add manually**.
3. Selecting MY-PC asks for the 6-digit code shown in the PC's tray window, then pairs.
4. Done: lands on the main screen.

Manual add: name, MAC, broadcast address (prefilled from the phone's current subnet), port (default 9), IP for status (optional). This is wake-only until the device is paired with an agent. Inline validation on blur: MAC accepts `:` or `-` separators and normalizes to `AA:BB:CC:DD:EE:FF`.

### 5.2 Main screen

Single scrolling column, edge-to-edge with system insets. Top to bottom:

1. **Header** (318 dp tall, curved bottom edge): device name (22 sp, bold) on the left, settings gear (48 dp touch target) on the right. Hero power control (148 dp circle) centered, caption below it.
2. **Status card:** two rows, each with an icon, label and value.
   - IP address: globe icon, `192.168.1.14`.
   - Response: activity icon, a pill, plus optional muted text.
3. **Actions,** grouped by risk in a 2-column grid with 12 dp gaps:
   - Shut down: full width, 68 dp, red, hold to confirm, right-side note.
   - Reboot: full width, 60 dp, red, hold to confirm, note "Hold 1 s".
   - Sleep and Lock: half width each, 60 dp, neutral, tap.
4. **Network section:** heading row with "Network" on the left and the subnet pill on the right (✓ Same subnet in green, ✕ Different subnet in yellow), then a card with rows: Subnet, This phone, PC link, PC MAC, Wake target (`192.168.1.255` + muted "port 9"), PC agent (pill).

On the S26 Ultra the Network heading and the first two rows sit above the fold, and the rest is one short scroll.

Device switcher: when there are 2 or more devices, the name in the header gets a chevron and opens a picker sheet (with folders and **Wake all** per folder). With one device there is no chevron.

### 5.3 Settings

- **Devices:** list with add, edit, delete and folders. Pairing status per device, with Re-pair and Unpair.
- **Import / Export.**
- **Advanced:** broadcast address, port, poll interval, haptics.
- **PC setup help:** the checklist from §8, in plain language.

Settings uses standard Material 3 list patterns in the same dark tokens. No custom visuals needed beyond the tokens.

## 6. Main screen states

Hero: "button" means the light disc that is tappable. "Indicator" means the green status ring that is **not** a button.

| State | Header | Hero | Caption | Response | Shut down note | Commands | Other |
|---|---|---|---|---|---|---|---|
| Off | Off gradient | Button (wake) | Tap to wake | `Off` pill (neutral) + "since 23:02" | PC is off | Disabled | |
| Waking | Mid gradient | Button with spinning ring | Waking up… 18 s | `Waiting` pill (yellow), no extra text | Not yet | Disabled | Timer counts up |
| On | On gradient + green glow | Indicator | On for 2h 14m | `2 ms` pill (green) + "steady" | Hold 1 s | Enabled | Shut down is the primary action |
| Holding | On | Indicator | (unchanged) | (unchanged) | Keep holding | Bar fills under the thumb | Haptic ticks |
| Undo window | On | Indicator | (unchanged) | (unchanged) | Sent | Disabled | Snackbar "Shutting down in 3 s" + **Undo** |
| Shutting down | Fading to off | Dim indicator + ring | Shutting down… / Restarting… / Going to sleep… | `Still answering` pill (yellow), no extra text | Sent | Disabled | Shutdown and sleep end at Off. Restart goes Off → Waking → On on its own (no magic packet needed) |
| Didn't wake | Off | Button, yellow ring | No answer after 90 s | `Off` pill + "no reply" | PC is off | Disabled | Yellow notice: "MY-PC didn't wake up. It may be unplugged, or Wake-on-LAN is off in the BIOS." Actions: **Retry**, **Check PC setup** |
| Not on home Wi-Fi | Off | Dim indicator | Away from home | `Unknown` pill | Unavailable | Disabled, stay in place | Yellow notice: "You're on mobile data. Join your home Wi-Fi to wake or control MY-PC." Subnet pill: ✕ Different subnet |
| On, agent not running | On | Indicator | On, waiting for agent | `3 ms` pill (green) | Needs agent | Disabled | Notice: "MY-PC is on but the agent isn't responding. Log in on the PC to enable commands." PC agent pill: Offline |

"Not on home Wi-Fi" means the phone is not on Wi-Fi, or its subnet differs from the device's saved subnet.

Rules:

- One primary action per state: Wake when off, Shut down when on.
- Disabled controls keep their place and say why (the note on the Shut down bar or the notice). The layout never jumps between states.
- A pill's secondary text sits beside it only when both fit on one line. In transitional states the pill stands alone.

## 7. Visual system

Source of truth for look and motion: `docs/design/prototype.html`. Values below are copied from it.

**Color (dark only)**

| Token | Hex | Use |
|---|---|---|
| background | `#0F1728` | screen |
| surface | `#18223A` | cards, neutral buttons |
| outline | `#26345A` | card borders, row dividers |
| onSurface | `#E8EEFA` | primary text |
| onSurfaceMuted | `#A3B1CC` | labels, secondary text |
| success | `#5BD69A` (text on dark `#7BE3AE`) | on state, ok pills |
| warning | `#F5C26B` | transitional and warning pills, notices |
| danger | `#FF9A9A` on `#33192A`, border `#55283B` | Shut down, Reboot |
| header off | gradient 165° `#111A31` → `#26385F` | |
| header on | gradient 165° `#1C2C55` → `#3A62AE` + radial green glow `rgba(91,214,154,.28)` | |
| hero disc | `#D3DCF0`, icon `#2A56B4` | toned down from white for a dark room |

Pills: tinted background at about 12–14% alpha of their color, a 1 dp border at about 35% alpha, text in the color. Contrast: all text ≥ 4.5:1, icons and large text ≥ 3:1.

**Type:** Manrope (bundle the font). Tabular figures for every number.

| Role | Size / weight |
|---|---|
| Device name | 22 sp / 700 |
| Hero caption | 16 sp / 500 |
| Button labels | 16–17 sp / 600 |
| Row values | 16 sp / 600 |
| Row labels, section heading | 14–15 sp / 500–700, muted |
| Pills | 14 sp / 700 |
| Notes on buttons | 13 sp / 500 |

**Shape and spacing:** cards 24 dp radius, buttons 20 dp, pills fully rounded. Screen margin 16 dp. 12 dp between grouped items, 20–32 dp between sections. Key-value rows: min 56 dp tall, 20 dp horizontal padding, label column 128 dp, values left-aligned in the second column.

**Icons:** one stroke icon family (2 dp stroke, rounded caps), for example Material Symbols Rounded or authored vectors matching the prototype. No emoji.

**Text scaling:** no fixed heights on text containers. Labels never wrap mid-word (single line). If a value doesn't fit beside its label, the whole value moves under the label. Verify at font scale 1.3 and at maximum.

## 8. PC setup (one-time)

Provide a PowerShell script (run as admin) plus written BIOS steps. The script:

1. Enables on the Realtek adapter: **Wake on Magic Packet**, **Shutdown Wake-On-Lan**, and "Allow this device to wake the computer". Disables **Energy Efficient Ethernet** / **Green Ethernet**, which can cut the link when the PC is off.
2. Turns off **Fast Startup** (`HiberbootEnabled = 0`) so a shutdown is a real shutdown that Wake-on-LAN can wake from.
3. Adds Windows Firewall rules, **Private profile only**, for the agent's HTTP and discovery ports.
4. Registers the agent's Task Scheduler logon task.
5. Prints what it changed and what it found already set. It's idempotent.

BIOS (MSI board, by MAC prefix): enable wake from PCI-E / LAN, and disable **ErP Ready** (ErP cuts standby power to the NIC). Exact menu names vary, so the help screen describes what to look for.

The script and the steps are also what "Check PC setup" links to.

## 9. UX requirements

- **Touch:** every target ≥ 48 dp. Settings gear has a 48 dp hit area.
- **Hold to confirm** (Shut down, Reboot): 1 s hold.
  - The fill follows the finger. Releasing early retracts the fill in 200 ms with no action.
  - A tap shorter than 250 ms shakes the bar and changes its note to "Hold to shut down" / "Hold to reboot" for about 1.6 s.
- **Accessibility alternative to holding:** with TalkBack on, or keyboard/switch access, activating Shut down or Reboot opens a Material confirm dialog: "Force shut down MY-PC? Open apps will close." with **Shut down** and **Cancel**.
- **Undo:** a 3 s window after Shut down and Reboot, enforced by the agent's delayed execution. *(My call to make the forced action recoverable. The user didn't object after trying it in the prototype.)*
- **TalkBack:**
  - Hero labeled "Wake MY-PC" when off and "MY-PC status: on" when on.
  - State changes announced politely: "MY-PC is on", "MY-PC is off", "MY-PC didn't wake up".
  - Pills read as their full meaning, for example "Response 2 milliseconds, steady".
- **Haptics:**
  - Light click on Wake press.
  - Ticks every quarter while holding.
  - Firm confirm when a hold completes.
  - Success pattern when the PC comes on, only if the app is in the foreground.
  - Nothing else. Toggle in settings.
- **Feedback latency:** visible response within 100 ms of any tap.
- **Back:** support predictive Back. Settings and sheets return to the main screen. The main screen exits the app.
- **Orientation:** portrait only. *(my call)*

## 10. Motion

Thesis: one authored moment, "the light comes on". Everything else is quiet feedback.

- **Focal:**
  - Off → On, when the PC first answers: the hero disc's fill melts away while a green ring draws in, the header crossfades from the off gradient to the on gradient and the green glow fades in. 600 ms, emphasized decelerate `cubic-bezier(0.05, 0.7, 0.1, 1)`.
  - On → Off: the reverse, 400 ms, emphasized accelerate `cubic-bezier(0.3, 0, 0.8, 0.15)`.
- **Waking:** an arc spins around the disc (1.3 s per turn, linear) only while waking or shutting down. It must stop when the app is backgrounded.
- **Press:** buttons scale to 0.98 and the hero to 0.96 for 100 ms, standard easing `cubic-bezier(0.2, 0, 0, 1)`.
- **Text and pills:** crossfade 150–250 ms. Never jump-cut a state change.
- **Undo snackbar:** rises in 300 ms (decelerate), leaves in 200 ms (accelerate).
- **Reduced motion** (system "Remove animations"):
  - No spin, shake or slide.
  - State changes become 150 ms crossfades.
  - The waking timer text still counts.
  - Haptics stay.

## 11. Build, install, verify

- Gradle wrapper (no global Gradle). compileSdk / targetSdk 36 (installed); minSdk 33 *(my call: X25519 for pairing is available on Android 13+)*. Package id `dev.jano.wake`, app label **Wake** *(both my call; easy to change)*.
- Sign with a local release keystore kept out of the repo, or debug signing. It's a personal sideload.
- Install: `"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" -s <serial> install -r app-release.apk`.
- Agent: a runnable distribution launched with `javaw` from JDK 21, installed by the setup script. A bundled runtime (jlink) is optional.
- **Verify on the real phone, not a browser:**
  - `adb exec-out screencap -p > shot.png` for every main-screen state.
  - Repeat at `adb shell settings put system font_scale 1.3` (restore to `1.0`).
  - TalkBack walk-through of wake, hold-alternative dialog and undo.
  - Real end-to-end test: shut down MY-PC from the phone, confirm it reaches Off, wake it from full shutdown, confirm it reaches On within 90 s.
- Unit tests for the magic packet bytes, MAC parsing/normalization, request signing and verification (including replay rejection), and the status state machine (timeouts, 3-miss rule).

## 12. Left to the builder

- HTTP and discovery port numbers. Pick unprivileged, unlikely-to-collide ports and keep them in one place.
- Libraries: HTTP server and client, JSON, persistence, DI (or none).
- How the agent draws its tray UI and pairing window.
- The exact Windows call for sleep without hibernate.
- Icon source, as long as it matches the stroke style.
- Settings and first-run screen layouts, within the tokens and patterns above.
- Packaging details for the agent.

## 13. Decisions made without explicit user input

These are marked *(my call)* above, collected here for review:

1. Kotlin for both the app and the agent, in one Gradle project with a shared protocol module.
2. HMAC-signed requests with timestamp and nonce instead of a plain token.
3. A 3 s server-side delay on shutdown and reboot to power the Undo button.
4. Magic packet sent 3 times.
5. "Off" declared after 3 missed probes. Waking times out at 90 s.
6. One paired phone per agent.
7. Portrait only. minSdk 33. Package `dev.jano.wake`, label "Wake".
11. Pairing uses an X25519 exchange confirmed by the 6-digit code (reviewer catch: a plain `/pair` response would leak the secret to a Wi-Fi sniffer).
8. Reboot also requires hold to confirm (it is also forced).
9. Hero disc toned down to `#D3DCF0` for night use.
10. Secrets are excluded from export.
