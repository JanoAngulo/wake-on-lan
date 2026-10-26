# Wake (Wake-on-LAN remote) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A sideloaded Android app that wakes, shuts down, reboots, sleeps and locks MY-PC on the home LAN, plus the Windows agent that executes the commands.

**Architecture:** One Gradle project, three modules. `protocol` (pure Kotlin/JVM) holds the magic packet, MAC parsing, wire messages, request signing and pairing crypto, and is shared by `agent` (Kotlin/JVM on JDK 21, runs in the user's Windows session, JDK HttpServer + UDP discovery + JNA) and `app` (Jetpack Compose, Material 3). The app's status logic is a pure state machine fed by TCP probes and agent status, so it is unit-tested on the JVM.

**Tech Stack:** Kotlin 2.2.20, Gradle 8.14.3 (wrapper), AGP 8.13.0, JDK 21, compileSdk/targetSdk 36, minSdk 33, Compose BOM 2025.09.01, Navigation Compose 2.9.5, DataStore 1.1.7, kotlinx-serialization 1.9.0, coroutines 1.10.2, OkHttp 5.1.0 (+ mockwebserver3), JNA 5.17.0, kotlin-test/JUnit. If a pinned version fails to resolve, use the newest stable release of the same major and note it in the commit message.

**Spec:** `docs/specs/2026-10-26-wake-on-lan-design.md`. Visual and motion reference: `docs/design/prototype.html`.

## Global Constraints

- Package / applicationId `dev.jano.wake`; app label `Wake`; agent main class `dev.jano.wake.agent.MainKt`.
- Ports: agent HTTP `47613`, discovery UDP `47614`, default Wake-on-LAN `9`. Defined once in `protocol` `Ports`.
- Default broadcast `192.168.1.255`; PC `MY-PC`, MAC `0A:1B:2C:3D:4E:5F`, IP `192.168.1.14` (DHCP, may change).
- Magic packet: 6 × `0xFF` + MAC × 16 = 102 bytes, sent 3 times ~100 ms apart.
- Status: poll every 2 s in foreground only; Off after 3 consecutive host-down probes; Waking times out to Didn't wake at 90 s; latency "steady" when median of last 5 ≤ 20 ms.
- Commands: shutdown = `shutdown /s /f /t 0`, reboot = `shutdown /r /f /t 0`, both fired by the agent 3 s after the request (Undo window); sleep must not hibernate; lock = `LockWorkStation`.
- Auth: HMAC-SHA256 over method, path, timestamp, nonce, SHA-256(body); reject skew > 30 s and reused nonces. Pairing: X25519 + code-confirmed tags; one wrong code ends pairing mode. One paired phone per agent.
- Agent binds HTTP to the LAN interface only (never Radmin VPN `26.x.x.x` or other virtual adapters).
- UI: dark only, portrait only, tokens and sizes from spec §7, states from spec §6, motion from spec §10, UX rules from spec §9. Copy strings exactly as in spec §6.
- Never run the elevated setup script or trigger real shutdown/reboot/sleep/lock on this PC; stop at the CHECKPOINT steps and wait for John.
- `adb` = `"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"`; phone serial from `adb devices` (currently `<serial>`).
- Commit messages: Conventional Commits, no AI attribution of any kind (no `Co-Authored-By` trailer, no tool mentions).

## Review Focus

1. **Android 17 local-network restrictions block UDP/TCP to the LAN** → the app must request whatever local-network permission the OS requires and explain it, not silently show Off. Pinned by Task 11 Step 4 (on-device broadcast check).
2. **PC gets a new DHCP IP** → status must follow it, not report Off forever. Pinned by Task 14 test `discoveryReplyWithNewIpUpdatesDevice`.
3. **Phone drops off home Wi-Fi mid-wake, then returns** → show Away, then resume the same Waking timer (not restart it, not jump to Didn't wake early). Pinned by Task 13 test `awayDuringWakingResumesTimer`.
4. **Phone and PC clocks differ by more than 30 s** → commands must still work after one automatic retry using the agent's clock. Pinned by Task 7 test `staleRequestReturnsAgentTime` and Task 11 test `retriesOnceWithAgentClockOffset`.
5. **Undo pressed after the command already fired** → phone shows "Too late to undo" instead of claiming it was cancelled. Pinned by Task 6 test `cancelAfterFireReturnsFalse` and Task 14 test `undoAfterFireReportsTooLate`.

---

## Phase A: Project and protocol

### Task 1: Repository and Gradle skeleton

**Files:**
- Create: `.gitignore`, `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, `gradlew`, `gradlew.bat`, `gradle/wrapper/*`
- Create: `protocol/build.gradle.kts`, `agent/build.gradle.kts`, `app/build.gradle.kts`
- Test: `protocol/src/test/kotlin/dev/jano/wake/protocol/SmokeTest.kt`

**Interfaces:**
- Produces: modules `:protocol` (kotlin-jvm + serialization, jvmToolchain 21, bytecode target 17 so Android can consume it), `:agent` (kotlin-jvm application, depends on `:protocol`), `:app` (android application + compose, depends on `:protocol`).

- [ ] **Step 1:** `git init`; `.gitignore` covers `.gradle/`, `build/`, `local.properties`, `*.keystore`, `*.jks`, `.superpowers/`, `*.xapk`, `.idea/`.
- [ ] **Step 2:** Bootstrap the wrapper without a global Gradle: download `https://services.gradle.org/distributions/gradle-8.14.3-bin.zip` to the scratchpad, unzip, run `<unzipped>/bin/gradle wrapper --gradle-version 8.14.3` in the repo root. Write `local.properties` with `sdk.dir` = `%LOCALAPPDATA%\Android\Sdk` (escaped).
- [ ] **Step 3:** Version catalog with the Tech Stack versions; module build files as described in Interfaces. `app`: namespace/applicationId `dev.jano.wake`, compileSdk 36, targetSdk 36, minSdk 33, compose enabled, portrait-only is set in the manifest later.
- [ ] **Step 4:** Write `SmokeTest` asserting `1 + 1 == 2`; run `./gradlew :protocol:test`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 5:** Run `./gradlew :app:assembleDebug` with an empty `MainActivity` showing a blank Compose surface. Expected: APK at `app/build/outputs/apk/debug/app-debug.apk`.
- [ ] **Step 6:** Commit `chore: scaffold gradle project with protocol, agent and app modules` (include `docs/` and `.impeccable/`).

### Task 2: MAC address and magic packet

**Files:**
- Create: `protocol/src/main/kotlin/dev/jano/wake/protocol/MacAddress.kt`, `MagicPacket.kt`, `Ports.kt`
- Test: `protocol/src/test/kotlin/dev/jano/wake/protocol/MacAddressTest.kt`, `MagicPacketTest.kt`

**Interfaces:**
- Produces: `data class MacAddress(val value: String)` (always `AA:BB:CC:DD:EE:FF`, uppercase) with `fun bytes(): ByteArray`; `fun parseMac(input: String): MacAddress?`; `fun magicPacket(mac: MacAddress): ByteArray`; `object Ports { const val HTTP = 47613; const val DISCOVERY = 47614; const val WOL = 9 }`.

- [ ] **Step 1: Tests**
  - `parsesColonDashAndBare`: `parseMac("0a:1b:2c:3d:4e:5f")`, `parseMac("0A-1B-2C-3D-4E-5F")`, `parseMac("0a1b2c3d4e5f")` all equal `MacAddress("0A:1B:2C:3D:4E:5F")`.
  - `rejectsInvalid`: `"0A:1B:2C:3D:4E"`, `"GG:1B:2C:3D:4E:5F"`, `""`, `"0A:1B:2C:3D:4E:5F:11"`, `" 0A:1B:2C:3D:4E:5F "` (trim is allowed: this one returns the MAC) → first four return `null`.
  - `magicPacketLayout`: size 102; bytes 0..5 all `0xFF`; for k in 0..15, bytes `6+6k..11+6k` equal `[0x0A,0x1B,0x2C,0x3D,0x4E,0x5F]`.
- [ ] **Step 2:** Run `./gradlew :protocol:test`. Expected: FAIL (unresolved references).
- [ ] **Step 3:** Implement the three files.
- [ ] **Step 4:** Run `./gradlew :protocol:test`. Expected: PASS.
- [ ] **Step 5:** Commit `feat(protocol): mac parsing and magic packet`.

### Task 3: Wire messages

**Files:**
- Create: `protocol/src/main/kotlin/dev/jano/wake/protocol/Messages.kt`
- Test: `protocol/src/test/kotlin/dev/jano/wake/protocol/MessagesTest.kt`

**Interfaces:**
- Produces (all `@Serializable`, JSON via `val WakeJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }`):
  - `enum class Action { @SerialName("shutdown") SHUTDOWN, @SerialName("reboot") REBOOT, @SerialName("sleep") SLEEP, @SerialName("lock") LOCK }`
  - `PendingCommand(id: String, action: Action, firesAtEpochMs: Long)`
  - `StatusResponse(hostname: String, ip: String, mac: String, uptimeSeconds: Long, agentVersion: String, serverTimeMs: Long, pending: PendingCommand? = null)`
  - `CommandRequest(action: Action)`; `CommandResponse(id: String? = null, firesAtEpochMs: Long? = null)`; `CancelRequest(id: String)`; `CancelResponse(cancelled: Boolean)`
  - `DiscoveryReply(hostname: String, ip: String, mac: String, httpPort: Int, agentVersion: String)`; `const val DISCOVERY_PROBE = "WAKE-DISCOVER-1"`
  - `PairStart(phonePublicKey: String)`; `PairChallenge(agentPublicKey: String, agentConfirm: String)`; `PairFinish(phonePublicKey: String, phoneConfirm: String)`
  - `ErrorBody(error: String, serverTimeMs: Long)`

- [ ] **Step 1: Tests:** `actionSerializesLowercase` (`CommandRequest(Action.SHUTDOWN)` encodes to `{"action":"shutdown"}`); `statusRoundTrips` (encode → decode equals original, with and without `pending`); `unknownKeysIgnored` (decoding `DiscoveryReply` JSON with an extra field succeeds).
- [ ] **Step 2:** Run `./gradlew :protocol:test`. Expected: FAIL.
- [ ] **Step 3:** Implement `Messages.kt`.
- [ ] **Step 4:** Run tests. Expected: PASS.
- [ ] **Step 5:** Commit `feat(protocol): wire messages`.

### Task 4: Request signing and verification

**Files:**
- Create: `protocol/src/main/kotlin/dev/jano/wake/protocol/Signing.kt`
- Test: `protocol/src/test/kotlin/dev/jano/wake/protocol/SigningTest.kt`

**Interfaces:**
- Produces:
  - header names `const val H_TIMESTAMP = "X-Wake-Timestamp"`, `H_NONCE = "X-Wake-Nonce"`, `H_SIGNATURE = "X-Wake-Signature"`
  - `data class SignedHeaders(val timestampMs: Long, val nonce: String, val signature: String)`
  - `fun sign(secret: ByteArray, method: String, path: String, body: ByteArray, nowMs: Long, nonce: String = newNonce()): SignedHeaders`
  - `fun newNonce(): String` (16 random bytes, base64url, no padding)
  - `enum class VerifyResult { OK, NOT_PAIRED, STALE, REPLAY, BAD_SIGNATURE }`
  - `class RequestVerifier(private val secret: () -> ByteArray?, private val clock: () -> Long, private val maxSkewMs: Long = 30_000)` with `fun verify(method: String, path: String, body: ByteArray, headers: SignedHeaders): VerifyResult`
- Canonical string (pin exactly): `"$METHOD\n$path\n$timestampMs\n$nonce\n" + hex(sha256(body))`, HMAC-SHA256 keyed by secret, signature base64url without padding. Compare in constant time. Nonces remembered for `2 × maxSkewMs`, then evicted. Check order: not paired → stale → bad signature → replay (a forged request never burns a nonce).

- [ ] **Step 1: Tests:** `validSignatureVerifies`; `tamperedBodyRejected` (BAD_SIGNATURE); `tamperedPathRejected`; `staleRejected` (timestamp 31 s old → STALE; 29 s → OK); `replayRejected` (same headers twice → second REPLAY); `forgedRequestDoesNotBurnNonce` (bad signature with nonce N, then a valid one with N → OK); `notPairedRejected` (secret provider returns null → NOT_PAIRED); `noncesEvictedAfterWindow` (advance clock 61 s; an old nonce with a fresh timestamp is accepted).
- [ ] **Step 2:** Run tests. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run tests. Expected: PASS.
- [ ] **Step 5:** Commit `feat(protocol): hmac request signing with replay protection`.

### Task 5: Pairing crypto

**Files:**
- Create: `protocol/src/main/kotlin/dev/jano/wake/protocol/Pairing.kt`
- Test: `protocol/src/test/kotlin/dev/jano/wake/protocol/PairingTest.kt`

**Interfaces:**
- Produces:
  - `fun generateX25519(): KeyPair` (`KeyPairGenerator.getInstance("X25519")`, falling back to `"XDH"` with `NamedParameterSpec.X25519` for Android providers)
  - `fun encodePublic(key: PublicKey): String` (base64 of X.509 encoding) / `fun decodePublic(b64: String): PublicKey`
  - `fun transcript(phonePub: String, agentPub: String): ByteArray` = UTF-8 of `"wake-pair-v1\n$phonePub\n$agentPub"`
  - `fun confirmTag(code: String, role: String, transcript: ByteArray): String` (HMAC-SHA256 key = UTF-8 code, message = `role + "\n"` + transcript; role is `"agent"` or `"phone"`; base64url)
  - `fun deriveSecret(own: PrivateKey, peerPublic: PublicKey, transcript: ByteArray): ByteArray` (X25519 agreement → HKDF-SHA256 with salt = SHA-256(transcript), info = `"wake-secret-v1"`, 32 bytes)
  - `fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray`

- [ ] **Step 1: Tests:** `bothSidesDeriveSameSecret`; `secretIs32Bytes`; `confirmTagDependsOnCode` (code `"123456"` vs `"123457"` differ); `confirmTagDependsOnRole`; `hkdfMatchesRfc5869Case1` (RFC 5869 test case 1: IKM 22 × `0x0b`, salt `000102…0c`, info `f0f1…f9`, L=42, OKM `3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865`).
- [ ] **Step 2:** Run tests. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run tests. Expected: PASS.
- [ ] **Step 5:** Commit `feat(protocol): x25519 pairing with code confirmation`.

## Phase B: PC agent

### Task 6: Command scheduler, LAN interface choice, pairing mode

**Files:**
- Create: `agent/src/main/kotlin/dev/jano/wake/agent/PowerActions.kt`, `CommandScheduler.kt`, `LanInterface.kt`, `PairingMode.kt`
- Test: `agent/src/test/kotlin/dev/jano/wake/agent/CommandSchedulerTest.kt`, `LanInterfaceTest.kt`, `PairingModeTest.kt`

**Interfaces:**
- Produces:
  - `interface PowerActions { fun shutdown(); fun reboot(); fun sleep(); fun lock() }`
  - `class CommandScheduler(actions: PowerActions, clock: () -> Long, timer: ScheduledExecutorService, delayMs: Long = 3_000)`: `fun submit(action: Action): CommandResponse` (SHUTDOWN/REBOOT scheduled, returns id + firesAt; SLEEP/LOCK run immediately on the timer thread, return empty response; a new shutdown/reboot replaces any pending one); `fun cancel(id: String): Boolean`; `fun pending(): PendingCommand?`
  - `data class NicInfo(val name: String, val displayName: String, val ipv4: String, val prefixLength: Int, val mac: String?, val up: Boolean)`; `fun isVirtual(nic: NicInfo): Boolean` (display name matches, case-insensitive, any of `radmin`, `vpn`, `virtual`, `hyper-v`, `vethernet`, `vmware`, `virtualbox`, `tap-`, `wsl`, `loopback`, `bluetooth`); `fun pickLanInterface(nics: List<NicInfo>, gatewayIp: String?): NicInfo?` (up, non-virtual, has MAC, subnet contains gateway; fallback: first up non-virtual with an RFC 1918 address)
  - `class PairingMode(clock: () -> Long, ttlMs: Long = 120_000, random: SecureRandom = SecureRandom())`: `fun begin(): String` (6 digits, zero-padded), `fun activeCode(): String?` (null when expired or ended), `fun end()`

- [ ] **Step 1: Tests:**
  - Scheduler (fake `PowerActions` recording calls; `ScheduledThreadPoolExecutor` with a controllable clock or a manual fake executor): `shutdownFiresAfterDelay`; `cancelBeforeFirePreventsAction`; `cancelAfterFireReturnsFalse`; `lockRunsImmediately`; `newShutdownReplacesPending`; `pendingReportsFireTime`.
  - LAN: given Ethernet `192.168.1.14/24` MAC `0A:1B:2C:3D:4E:5F` + Radmin VPN `26.10.20.30/8` + vEthernet `172.24.0.1/20` and gateway `192.168.1.1` → picks Ethernet; `radminIsVirtual`; `noGatewayFallsBackToPrivateNonVirtual`; `nothingSuitableReturnsNull`.
  - Pairing: `codeIsSixDigits`; `codeExpiresAfterTtl`; `endClearsCode`.
- [ ] **Step 2:** Run `./gradlew :agent:test`. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run tests. Expected: PASS.
- [ ] **Step 5:** Commit `feat(agent): command scheduler, lan interface choice, pairing mode`.

### Task 7: Agent HTTP server and discovery responder

**Files:**
- Create: `agent/src/main/kotlin/dev/jano/wake/agent/SecretStore.kt`, `AgentServer.kt`, `DiscoveryResponder.kt`, `HostInfo.kt`
- Test: `agent/src/test/kotlin/dev/jano/wake/agent/AgentServerTest.kt`, `DiscoveryResponderTest.kt`, `SecretStoreTest.kt`

**Interfaces:**
- Consumes: Task 3 messages, Task 4 `RequestVerifier`/`sign`, Task 5 pairing functions, Task 6 classes.
- Produces:
  - `data class HostInfo(val hostname: String, val ip: String, val mac: String, val uptimeSeconds: Long, val version: String)`
  - `class SecretStore(dir: Path)`: `fun load(): ByteArray?`, `fun save(secret: ByteArray)`, `fun clear()`; file `secret.bin`; on Windows restricts the file with `icacls <file> /inheritance:r /grant:r "<USERNAME>:F"`.
  - `class AgentServer(bind: InetSocketAddress, hostInfo: () -> HostInfo, scheduler: CommandScheduler, store: SecretStore, pairing: PairingMode, clock: () -> Long, onPaired: () -> Unit)`: `fun start()`, `fun stop()`, `val port: Int`. Routes:
    - `GET /status` (signed) → `StatusResponse` incl. `serverTimeMs` and `pending`
    - `POST /command` (signed) → `CommandResponse`
    - `POST /command/cancel` (signed) → `CancelResponse`
    - `POST /pair/start` (unsigned) → `PairChallenge`. 409 `ErrorBody("not_pairing")` when `pairing.activeCode()` is null.
    - `POST /pair/finish` (unsigned) → 204 on a correct `phoneConfirm`, then saves the secret, ends pairing and calls `onPaired`. Wrong confirm → 403 `ErrorBody("bad_code")` and pairing ends.
    - Verification failures → 401 `ErrorBody(<result lowercased>, serverTimeMs)`.
  - `class DiscoveryResponder(port: Int, lanPrefix: Pair<String, Int>, reply: () -> DiscoveryReply)`: `fun handle(payload: ByteArray, from: InetAddress): ByteArray?` (JSON reply only when payload == `DISCOVERY_PROBE` and `from` is inside the LAN prefix); `fun start()`/`fun stop()` (daemon thread, wildcard bind so broadcasts arrive).

- [ ] **Step 1: Tests** (real server on `127.0.0.1:0`, client built from `java.net.http.HttpClient` + `sign`):
  - `statusRequiresSignature` (unsigned → 401); `signedStatusReturnsHostInfo`; `commandSchedulesShutdown` (fake actions not called yet, `pending` present); `cancelWorks`.
  - `staleRequestReturnsAgentTime` (timestamp 60 s old → 401, body `error == "stale"` with `serverTimeMs` equal to the server clock).
  - `pairStartWithoutPairingModeIs409`; `fullPairingSavesSameSecretAsPhone` (simulate the phone with Task 5 functions; derived secrets equal; `store.load()` equals the phone's secret); `wrongCodeEndsPairing` (bad confirm → 403, a second `/pair/start` → 409).
  - Discovery: `probeFromLanGetsReply` (from `192.168.1.23` with prefix `192.168.1.0/24`); `probeFromOutsideIgnored` (from `26.1.2.3`); `wrongPayloadIgnored`.
  - SecretStore: `saveLoadClearRoundTrip` (temp dir).
- [ ] **Step 2:** Run `./gradlew :agent:test`. Expected: FAIL.
- [ ] **Step 3:** Implement with `com.sun.net.httpserver.HttpServer` and `DatagramSocket`.
- [ ] **Step 4:** Run tests. Expected: PASS.
- [ ] **Step 5:** Commit `feat(agent): signed http api, pairing endpoints and discovery responder`.

### Task 8: Windows integration, tray and runnable agent

**Files:**
- Create: `agent/src/main/kotlin/dev/jano/wake/agent/WindowsPowerActions.kt`, `WindowsSystem.kt`, `Tray.kt`, `Main.kt`
- Modify: `agent/build.gradle.kts` (fat jar task `agentJar` producing `agent/build/libs/wake-agent.jar` with runtime classpath; JNA dependency)
- Test: `agent/src/test/kotlin/dev/jano/wake/agent/WindowsSystemTest.kt`

**Interfaces:**
- Consumes: Tasks 6–7.
- Produces:
  - `class WindowsPowerActions : PowerActions`:
    - shutdown/reboot via `ProcessBuilder("shutdown", "/s"|"/r", "/f", "/t", "0")`
    - `sleep()` via JNA `PowrProf.SetSuspendState(false, false, false)` (no hibernate)
    - `lock()` via JNA `User32.LockWorkStation()`
  - `object WindowsSystem`: `fun uptimeSeconds(): Long` (JNA `Kernel32.GetTickCount64() / 1000`); `fun defaultGateway(): String?` (parse `route print -4 0.0.0.0`); `fun nics(): List<NicInfo>` (from `NetworkInterface`); `fun parseDefaultGateway(routePrint: String): String?` (pure, for tests)
  - `class Tray(onPair: () -> String, onQuit: () -> Unit)`: AWT `SystemTray` icon with menu items "Pair phone…" (opens a small undecorated window showing the 6-digit code in large type plus "Waiting for phone…", closes on pair or expiry), status line "Paired" / "Not paired", and "Quit".
  - `main()`: picks the LAN NIC, starts server on `<lanIp>:47613` and discovery on `47614`, logs to `%LOCALAPPDATA%\Wake\agent.log`, single-instance guard via a lock file in the same folder.

- [ ] **Step 1: Test:** `parsesGatewayFromRoutePrint` using a captured `route print -4 0.0.0.0` sample from this PC (gateway `192.168.1.1`). Capture it with `route print -4 0.0.0.0` and paste it into the test.
- [ ] **Step 2:** Run `./gradlew :agent:test`. Expected: FAIL. Implement. Run again. Expected: PASS.
- [ ] **Step 3: Manual verify (safe):**
  - Run `./gradlew :agent:agentJar`, then `javaw -jar agent/build/libs/wake-agent.jar`.
  - The tray icon appears.
  - `curl -s -o NUL -w "%{http_code}" http://192.168.1.14:47613/status` prints `401`.
  - `curl` on `127.0.0.1:47613` fails to connect, proving it's bound to the LAN only.
  - Do **not** call `/command`.
  - Quit from the tray.
- [ ] **Step 4:** Commit `feat(agent): windows power actions, tray and runnable jar`.

### Task 9: PC setup script

**Files:**
- Create: `pc-setup/setup.ps1`, `pc-setup/README.md`

**Interfaces:**
- Produces:
  - `setup.ps1 [-Check] [-AgentJar <path>]`
  - `-Check` is read-only and needs no admin. It prints a table of each item with Current / Wanted / OK.
  - Without `-Check` it requires admin and is idempotent. Items:
    1. Realtek adapter (wired, matching MAC `0A:1B:2C:3D:4E:5F` or the gateway-subnet NIC) advanced properties: Wake on Magic Packet = Enabled, Shutdown Wake-On-Lan = Enabled, Energy Efficient Ethernet / Green Ethernet = Disabled (match by display name, skip if absent).
    2. `Set-NetAdapterPowerManagement -WakeOnMagicPacket Enabled`.
    3. Fast Startup off: `HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Power` `HiberbootEnabled = 0`.
    4. Firewall rules, Private profile only, inbound: TCP 47613 and UDP 47614 for `javaw.exe`. Also a TCP 445 allow (Private, LocalSubnet only) only if the reachability check below shows 445 is silently dropped.
    5. Copies the jar to `%LOCALAPPDATA%\Wake\wake-agent.jar` and registers a Scheduled Task "Wake Agent" at user logon running `javaw.exe -jar` that path.
  - `README.md` gives the BIOS steps in plain language (enable wake from PCI-E/LAN; disable ErP) and the checkpoint instructions.

- [ ] **Step 1:** Write the script. Run `powershell -ExecutionPolicy Bypass -File pc-setup/setup.ps1 -Check`. Expected: the table prints without errors and nothing changes. Record which items are already OK (Wake-on-LAN is proven, so the NIC items should mostly be OK).
- [ ] **Step 2:** Reachability check for spec §4.2: from the phone run `adb shell "nc -z -w 1 192.168.1.14 445; echo \$?"` (or `toybox nc`). Record whether 445 answers. If neither 445 nor 135 answers, the script's optional 445 rule is enabled by default.
- [ ] **Step 3:** Commit `feat(pc-setup): idempotent setup script with check mode`.
- [ ] **Step 4: CHECKPOINT 1 (John):**
  - Ask John to run `pc-setup/setup.ps1 -AgentJar agent/build/libs/wake-agent.jar` from an elevated PowerShell. It will drop wireless ADB briefly.
  - Ask him to log off and on (or start the task), and confirm the tray icon appears.
  - Ask him to paste the script's output.
  - Then re-run `adb devices`. If the phone dropped, reconnect it by having John re-pair wireless debugging.

## Phase C: Android app

### Task 10: Theme, fonts, manifest

**Files:**
- Create: `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/dev/jano/wake/MainActivity.kt`, `app/src/main/kotlin/dev/jano/wake/ui/theme/{Color.kt,Type.kt,Shape.kt,Theme.kt}`, `app/src/main/res/font/manrope.ttf`, launcher icon (adaptive, power glyph on `#1C2C55`)

**Interfaces:**
- Produces:
  - `object WakeColors` with every token from spec §7 (names: `Background`, `Surface`, `Outline`, `OnSurface`, `OnSurfaceMuted`, `Success`, `SuccessText`, `Warning`, `Danger`, `DangerContainer`, `DangerOutline`, `HeaderOffStart`, `HeaderOffEnd`, `HeaderOnStart`, `HeaderOnEnd`, `Glow`, `HeroDisc`, `HeroIcon`)
  - `val ManropeFamily` (variable font, weights 400–700 via `FontVariation.weight`)
  - `object WakeType` with the spec §7 roles (`DeviceName`, `HeroCaption`, `Button`, `ButtonPrimary`, `RowValue`, `RowLabel`, `Section`, `Pill`, `Note`), all with `fontFeatureSettings = "tnum"`
  - `@Composable fun WakeTheme(content: @Composable () -> Unit)` (dark Material 3 scheme mapped from `WakeColors`)
  - `object Motion` with the easing curves and durations from spec §10 (`EmphasizedDecelerate`, `EmphasizedAccelerate`, `Standard`, `FOCAL_IN_MS = 600`, `FOCAL_OUT_MS = 400`, `PRESS_MS = 100`, `CROSSFADE_MS = 200`, `SNACK_IN_MS = 300`, `SNACK_OUT_MS = 200`, `HOLD_MS = 1000`, `TAP_MAX_MS = 250`)
- Manifest:
  - Permissions `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `VIBRATE`.
  - `MainActivity` with `android:screenOrientation="portrait"`, edge-to-edge, `enableOnBackInvokedCallback="true"`.

- [ ] **Step 1:** Download Manrope variable TTF from `https://github.com/google/fonts/raw/main/ofl/manrope/Manrope%5Bwght%5D.ttf` into `res/font/manrope.ttf`. Implement the theme files.
- [ ] **Step 2:** `./gradlew :app:assembleDebug`, then `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk`, then `adb -s <serial> shell am start -n dev.jano.wake/.MainActivity`. Expected: the app opens to the `#0F1728` background. Capture `adb -s <serial> exec-out screencap -p > %SCRATCH%/t10.png` and look at it.
- [ ] **Step 3:** Commit `feat(app): dusk theme, manrope, manifest`.

### Task 11: Phone-side networking

**Files:**
- Create: `app/src/main/kotlin/dev/jano/wake/net/{WakeSender.kt,HostProbe.kt,AgentClient.kt,Discovery.kt,PairingClient.kt,PhoneNetwork.kt}`
- Test: `app/src/test/kotlin/dev/jano/wake/net/{HostProbeTest.kt,AgentClientTest.kt,PhoneNetworkTest.kt}`

**Interfaces:**
- Consumes: protocol Tasks 2–5.
- Produces:
  - `suspend fun sendWake(mac: MacAddress, broadcast: String, port: Int = Ports.WOL, repeats: Int = 3, gapMs: Long = 100)`: `DatagramSocket` with broadcast enabled, on `Dispatchers.IO`.
  - `sealed interface ProbeResult { data class Up(val rttMs: Long) : ProbeResult; data object Down : ProbeResult }`; `suspend fun probeHost(ip: String, ports: List<Int>, timeoutMs: Int = 800): ProbeResult`. Up on connect or `ConnectException` refused (rtt = elapsed); Down when all ports time out or are unreachable; ports probed in parallel, first Up wins.
  - `class AgentClient(http: OkHttpClient, baseUrl: String, secret: ByteArray, clock: () -> Long)`: `suspend fun status(): StatusResponse`, `suspend fun command(action: Action): CommandResponse`, `suspend fun cancel(id: String): CancelResponse`. On 401 `stale` it stores `offset = serverTimeMs - clock()` and retries once. Throws `AgentUnavailable` on IO failure and `AgentRejected(error)` on other 4xx.
  - `suspend fun discoverAgents(broadcast: String, timeoutMs: Long = 5_000): List<DiscoveryReply>` (sends `DISCOVERY_PROBE` to `broadcast:47614`, collects distinct replies by MAC).
  - `class PairingClient(http: OkHttpClient)`: `suspend fun pair(baseUrl: String, code: String): ByteArray`. Throws `PairingFailed(reason)` where reason ∈ `NOT_PAIRING`, `BAD_CODE`, `AGENT_MISMATCH` (agent tag fails to verify), `NETWORK`.
  - `data class PhoneNet(val onWifi: Boolean, val ip: String?, val prefixLength: Int?)`; `fun readPhoneNet(context: Context): PhoneNet` (ConnectivityManager active network + LinkProperties); pure helpers `fun subnetOf(ip: String, prefixLength: Int): String` (e.g. `"192.168.1.0/24"`), `fun broadcastOf(ip: String, prefixLength: Int): String`, `fun isHome(phone: PhoneNet, deviceSubnet: String?): Boolean`.

- [ ] **Step 1: Tests:**
  - `HostProbeTest` (JVM, real sockets):
    - `openPortIsUp` (bound `ServerSocket` on 127.0.0.1)
    - `refusedPortIsUp` (an unbound localhost port)
    - `blackholeIsDown` (`10.255.255.1` with 300 ms timeout)
  - `AgentClientTest` with mockwebserver3:
    - `signsRequests`: server side recomputes with `RequestVerifier` → OK
    - `retriesOnceWithAgentClockOffset`: first response 401 `{"error":"stale","serverTimeMs":<T>}`, second 200. Exactly 2 requests, and the second timestamp is within 1 s of T.
    - `badSignatureSurfacesRejected`
  - `PhoneNetworkTest`:
    - `subnetOf("192.168.1.23", 24) == "192.168.1.0/24"`
    - `broadcastOf("192.168.1.23", 24) == "192.168.1.255"`
    - `isHome` false when `onWifi == false`, false on a different subnet, true on the same subnet.
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest`. Expected: FAIL. Implement. Run again. Expected: PASS.
- [ ] **Step 3:** Commit `feat(app): wake sender, host probe, agent and pairing clients`.
- [ ] **Step 4: On-device local network check (Review Focus 1):**
  - Add a debug-only `adb` entry point (`am start -n dev.jano.wake/.MainActivity --es debugAction discover`) that runs `discoverAgents("192.168.1.255")` and `probeHost("192.168.1.14", listOf(47613, 445))` and logs the results under tag `WakeDebug`.
  - With the agent running, check `adb logcat -s WakeDebug`. Expected: one `DiscoveryReply` for `MY-PC`, and `Up`.
  - If Android 17 blocks it (exception or empty result while the PC is reachable), find the required local-network permission for targetSdk 36 on API 37. Declare it, add a runtime request with a one-line rationale ("Wake needs local network access to find and wake your PC"), and re-check.
  - Commit `fix(app): request local network access` only if a change was needed.

### Task 12: Storage, secrets, import/export

**Files:**
- Create: `app/src/main/kotlin/dev/jano/wake/data/{Model.kt,DeviceRepository.kt,SecretVault.kt,BackupCodec.kt}`
- Test: `app/src/test/kotlin/dev/jano/wake/data/BackupCodecTest.kt`, `DeviceRepositoryTest.kt`

**Interfaces:**
- Produces:
  - `@Serializable data class Device(val id: String, val name: String, val mac: String, val broadcast: String = "192.168.1.255", val port: Int = Ports.WOL, val lastIp: String? = null, val subnet: String? = null, val folderId: String? = null, val agentPort: Int = Ports.HTTP, val paired: Boolean = false)`
  - `@Serializable data class Folder(val id: String, val name: String)`; `@Serializable data class AppSettings(val pollMs: Long = 2_000, val haptics: Boolean = true)`
  - `@Serializable data class Store(val devices: List<Device> = emptyList(), val folders: List<Folder> = emptyList(), val settings: AppSettings = AppSettings(), val selectedId: String? = null)`
  - `class DeviceRepository(dataStore: DataStore<Preferences>)`: `val store: Flow<Store>`, `suspend fun update(transform: (Store) -> Store)`. Convenience functions `upsertDevice`, `deleteDevice` (also clears folder refs), `upsertFolder`, `deleteFolder` (devices move to no folder), `select(id)`, `updateIp(deviceId, ip)`.
  - `class SecretVault(context: Context)`: AES-GCM key in AndroidKeyStore alias `wake-secrets`; `fun put(deviceId: String, secret: ByteArray)`, `fun get(deviceId: String): ByteArray?`, `fun remove(deviceId: String)`.
  - `object BackupCodec`: `fun export(store: Store): String` (`{"version":1,"devices":[…],"folders":[…]}` with every `paired = false`); `fun import(json: String): Result<Pair<List<Device>, List<Folder>>>` (rejects invalid MACs via `parseMac`, normalizes MACs, regenerates ids that collide).

- [ ] **Step 1: Tests:**
  - `exportNeverMarksPaired`
  - `exportImportRoundTrip`
  - `importRejectsBadMac` (message names the device)
  - `importNormalizesMac`
  - `deleteFolderUnassignsDevices`: repository on a temp-file `PreferenceDataStoreFactory`
  - `updateIpChangesOnlyThatDevice`
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest`. Expected: FAIL. Implement. Run again. Expected: PASS.
- [ ] **Step 3:** Commit `feat(app): device storage, keystore secrets, json import/export`.

### Task 13: Status state machine

**Files:**
- Create: `app/src/main/kotlin/dev/jano/wake/status/{PcState.kt,StatusMachine.kt}`
- Test: `app/src/test/kotlin/dev/jano/wake/status/StatusMachineTest.kt`

**Interfaces:**
- Produces:
  - `enum class StopKind { SHUTDOWN, REBOOT, SLEEP }`
  - `sealed interface PcState` with:
    - `data class Off(val sinceMs: Long?)`
    - `data class Waking(val startedMs: Long)`
    - `data class On(val uptimeSec: Long?, val latencyMs: Long, val steady: Boolean, val agent: Boolean)`
    - `data class Stopping(val kind: StopKind, val startedMs: Long)`
    - `data class DidntWake(val sinceMs: Long)`
    - `data class Away(val resume: PcState?)`
  - `class StatusMachine(clock: () -> Long, wakeTimeoutMs: Long = 90_000, missesForOff: Int = 3, steadyMs: Long = 20, window: Int = 5)` with:
    - `val state: StateFlow<PcState>`
    - `fun onWakeSent()`
    - `fun onProbe(host: ProbeResult, agent: StatusResponse?)`
    - `fun onStopSent(kind: StopKind)`
    - `fun onStopCancelled()`
    - `fun onNetwork(home: Boolean)`
    - `fun tick()`: evaluates timeouts
- Rules (pin exactly):
  - Initial state is `Off(null)`.
  - Down × `missesForOff` while On → Off.
  - Waking + Up → On.
  - Waking + no Up for `wakeTimeoutMs` → DidntWake.
  - DidntWake + `onWakeSent` → Waking.
  - Stopping(SHUTDOWN or SLEEP) + Down × `missesForOff` → Off.
  - Stopping(REBOOT) + Down × `missesForOff` → Waking(now), which then follows the Waking rules.
  - `onNetwork(false)` → Away(previous). `onNetwork(true)` restores the previous state (a resumed Waking keeps its original `startedMs`).
  - Up while Off → On (the PC was woken by other means).
  - `steady` = median of the last `window` Up rtts ≤ `steadyMs`.
  - `agent` = agent status present.

- [ ] **Step 1: Tests** (fake clock):
  - `startsOff`
  - `wakeThenUpGoesOn`
  - `wakeTimesOutAt90s` (89 s still Waking, 90 s DidntWake)
  - `oneMissDoesNotTurnOff`
  - `threeMissesTurnOff`
  - `shutdownEndsOff`
  - `rebootGoesOffThenWakingThenOn`
  - `awayDuringWakingResumesTimer` (Away at 30 s, home at 50 s, DidntWake at 90 s from the original start)
  - `upWhileOffGoesOn`
  - `onWithoutAgentFlagsAgentFalse`
  - `steadyFlagUsesMedian` (rtts 2, 3, 80, 4, 5 → steady; 30, 40, 25, 2, 60 → not)
  - `cancelReturnsToOn`
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest`. Expected: FAIL. Implement. Run again. Expected: PASS.
- [ ] **Step 3:** Commit `feat(app): pc status state machine`.

### Task 14: View model and polling

**Files:**
- Create: `app/src/main/kotlin/dev/jano/wake/main/{MainUiState.kt,MainViewModel.kt}`, `app/src/main/kotlin/dev/jano/wake/AppGraph.kt` (manual DI: repository, vault, OkHttp, clocks)
- Test: `app/src/test/kotlin/dev/jano/wake/main/MainViewModelTest.kt`

**Interfaces:**
- Consumes: Tasks 11–13.
- Produces:
  - `data class MainUiState(val device: Device?, val pc: PcState, val pending: PendingCommand?, val phone: PhoneNet, val home: Boolean, val message: UiMessage?)`
  - `sealed interface UiMessage` with `data class Undo(val id: String, val kind: StopKind, val firesAtMs: Long)`, `data class Info(val text: String)`, `data object TooLateToUndo`
  - `class MainViewModel(graph: AppGraph, clock: () -> Long, io: CoroutineDispatcher)` with:
    - `val ui: StateFlow<MainUiState>`
    - `fun startPolling(scope: CoroutineScope)`: the screen calls it inside `repeatOnLifecycle(STARTED)` so polling stops in the background
    - `fun wake()`, `fun shutdown()`, `fun reboot()`, `fun sleep()`, `fun lock()`, `fun undo()`
  - Each poll does `readPhoneNet` → `onNetwork`, then `probeHost(lastIp, [agentPort, 445])` and `AgentClient.status()` in parallel → `onProbe`.
  - A status reply whose `ip` differs from `lastIp` calls `updateIp`.
  - When the host is Down for 3 polls while home, it runs `discoverAgents` once and updates `lastIp` from the reply whose MAC matches.
  - Undo calls `cancel(id)`:
    - `true` → `onStopCancelled` + `Info("Cancelled")`
    - `false` → `TooLateToUndo`
  - Copy: `Info("Sleep sent")`, `Info("MY-PC locked")` use the device name.

- [ ] **Step 1: Tests** (fakes for network functions behind small interfaces `HostProber`, `AgentApi`, `Discoverer`, `PhoneNetReader` introduced in this task; `StandardTestDispatcher`):
  - `wakeSendsPacketAndEntersWaking`
  - `discoveryReplyWithNewIpUpdatesDevice`
  - `undoAfterFireReportsTooLate`
  - `pollingStopsWhenScopeCancelled`
  - `commandsIgnoredUnlessOnWithAgent`
- [ ] **Step 2:** Run tests. Expected: FAIL. Implement. Run again. Expected: PASS.
- [ ] **Step 3:** Commit `feat(app): main view model with lifecycle-bound polling`.

### Task 15: Main screen UI, motion and accessibility

**Files:**
- Create: `app/src/main/kotlin/dev/jano/wake/ui/main/{MainScreen.kt,Header.kt,Hero.kt,StatusCard.kt,ActionGrid.kt,HoldButton.kt,HoldTracker.kt,NetworkSection.kt,KeyValueRow.kt,Pill.kt,Notice.kt,UndoSnackbar.kt,Haptics.kt}`
- Test: `app/src/test/kotlin/dev/jano/wake/ui/main/HoldTrackerTest.kt`

**Interfaces:**
- Consumes: Task 10 tokens/Motion, Task 14 `MainUiState` and actions.
- Produces:
  - `class HoldTracker(holdMs: Long = Motion.HOLD_MS.toLong(), tapMaxMs: Long = Motion.TAP_MAX_MS.toLong())`: `fun progress(elapsedMs: Long): Float` (0..1), `fun quarterTicks(elapsedMs: Long): Int` (0..4), `fun release(elapsedMs: Long): HoldOutcome`
  - `enum class HoldOutcome { COMPLETED, CANCELLED, TAP }`
  - `@Composable fun MainScreen(state: MainUiState, actions: MainActions, onOpenSettings: () -> Unit)`, where `data class MainActions(val wake: () -> Unit, val shutdown: () -> Unit, val reboot: () -> Unit, val sleep: () -> Unit, val lock: () -> Unit, val undo: () -> Unit)`
- Behavior to pin:
  - Layout, sizes and copy per spec §5.2, §6 and §7.
  - Key-value rows: 128 dp label column, labels single-line, values wrap as a whole under the label when they don't fit (FlowRow).
  - Hero is a `Button` semantics node only in Off and DidntWake; otherwise its `contentDescription` is "MY-PC status: <state>" with no click.
  - The state caption is a polite live region.
  - Focal transition: animate disc color, ring color/width, glow alpha and header gradient alpha with `tween(FOCAL_IN_MS, EmphasizedDecelerate)` toward On and `tween(FOCAL_OUT_MS, EmphasizedAccelerate)` toward Off.
  - Waking/Stopping arc uses `rememberInfiniteTransition` (1300 ms linear) and only exists while in those states.
  - Pills and text use `AnimatedContent` with a crossfade of `CROSSFADE_MS`.
  - Press scale 0.98 (hero 0.96) over `PRESS_MS`.
  - HoldButton fill follows `HoldTracker.progress`, with a haptic tick on each quarter and a confirm haptic on COMPLETED. TAP → shake (3 oscillations, 320 ms) + note "Hold to shut down" / "Hold to reboot" for 1.6 s. CANCELLED → fill retracts over 200 ms.
  - When `AccessibilityManager.isTouchExplorationEnabled` is true, HoldButton is a plain click that opens an `AlertDialog`: "Force shut down MY-PC? Open apps will close." (or "Force reboot…") with buttons **Shut down**/**Reboot** and **Cancel**.
  - Reduced motion when `Settings.Global.ANIMATOR_DURATION_SCALE == 0f`: no arc, no shake, no snackbar slide; transitions become 150 ms crossfades. The waking timer text still counts.
  - Haptics respect `AppSettings.haptics`.
- Test:
  - `HoldTrackerTest.progressIsLinear`
  - `releaseBefore250IsTap`
  - `releaseBetweenIsCancelled`
  - `releaseAt1000IsCompleted`
  - `quarterTicksCountUp`
- Debug hook: `--es demoState <off|waking|on|holding|undo|stopping|didntwake|away|noagent>` on `MainActivity` (debug builds only) renders `MainScreen` with a fixed state for screenshots.

- [ ] **Step 1:** Write `HoldTrackerTest`, run, see it fail, implement `HoldTracker`, see it pass.
- [ ] **Step 2:** Implement the composables and wire `MainActivity` → `MainViewModel` → `MainScreen`.
- [ ] **Step 3: Verify on device:**
  - Install the debug build.
  - For each `demoState`, run `adb -s <serial> shell am start -n dev.jano.wake/.MainActivity --es demoState <s>` then `adb -s <serial> exec-out screencap -p > %SCRATCH%/s15-<s>.png`.
  - Compare against `docs/design/prototype.html` and spec §6. Fix every mismatch in one batch, then re-capture once.
- [ ] **Step 4:**
  - Run `adb -s <serial> shell settings put system font_scale 1.3` and re-capture `on` and `away`. Expected: no clipped or mid-word-wrapped labels.
  - Restore with `adb -s <serial> shell settings put system font_scale 1.0`.
- [ ] **Step 5:** Commit `feat(app): main screen with motion, hold-to-confirm and accessibility`.

### Task 16: First run, pairing, settings, navigation

**Files:**
- Create: `app/src/main/kotlin/dev/jano/wake/ui/setup/{FindPcScreen.kt,PairScreen.kt,ManualAddScreen.kt,SetupViewModel.kt}`, `app/src/main/kotlin/dev/jano/wake/ui/settings/{SettingsScreen.kt,DevicesScreen.kt,DeviceEditScreen.kt,FolderSheet.kt,DevicePickerSheet.kt,AdvancedScreen.kt,PcSetupHelpScreen.kt}`, `app/src/main/kotlin/dev/jano/wake/ui/Nav.kt`
- Test: `app/src/test/kotlin/dev/jano/wake/ui/setup/SetupViewModelTest.kt`

**Interfaces:**
- Consumes: Tasks 11, 12, 14.
- Produces:
  - Routes `setup/find`, `setup/pair/{ip}/{port}`, `setup/manual`, `main`, `settings`, `settings/devices`, `settings/device/{id}`, `settings/advanced`, `settings/help`. Start at `setup/find` when the store has no devices, else `main`.
  - `class SetupViewModel`: `fun find()`, `fun pair(reply: DiscoveryReply, code: String)`, `fun saveManual(name: String, mac: String, broadcast: String, port: String, ip: String): List<FieldError>`
- Copy (exact):
  - Find empty result: "No PC answered. Make sure the Wake agent is running on your PC and that Windows Firewall allows it on private networks." Buttons **Try again** and **Add manually**.
  - Pair errors:
    - NOT_PAIRING: "Open the Wake tray icon on your PC and choose Pair phone first."
    - BAD_CODE: "That code didn't match. Start pairing again on the PC."
    - AGENT_MISMATCH: "Couldn't verify the PC. Start pairing again on the PC."
    - NETWORK: "Couldn't reach the PC. Check you're on home Wi-Fi."
  - Manual form errors are shown under each field on blur: "Enter a MAC like 0A:1B:2C:3D:4E:5F", "Enter an IPv4 address", "Port must be 1–65535".
- Settings:
  - Devices list (paired badge, Re-pair, Unpair).
  - Folders (create, rename, delete, assign) and device picker sheet with **Wake all** per folder. The header chevron shows only with ≥ 2 devices.
  - Import / Export through the SAF `CreateDocument("application/json")` / `OpenDocument` contracts.
  - Advanced: broadcast, port, poll interval, haptics.
  - PC setup help: the README checklist text.
- Predictive back on every screen.

- [ ] **Step 1: Tests:**
  - `manualRejectsBadMacWithCopy`
  - `manualNormalizesMacAndSaves`
  - `pairSuccessStoresSecretAndMarksPaired`, with fake `PairingClient` and `SecretVault` behind interfaces
  - `pairBadCodeShowsCopy`
- [ ] **Step 2:** Run tests. Expected: FAIL. Implement. Run again. Expected: PASS.
- [ ] **Step 3: Verify on device:**
  - `adb shell pm clear dev.jano.wake` → the app opens on Find my PC.
  - With the agent running, Find lists MY-PC.
  - Choose "Pair phone…" in the tray, then enter the code on the phone. Expected: lands on the main screen showing the On state and the PC agent "Connected" pill.
  - Screenshot the find, pair and settings screens. Fix mismatches in one batch.
- [ ] **Step 4:** Commit `feat(app): first-run discovery, pairing, settings and navigation`.

### Task 17: Release build and full verification

**Files:**
- Modify: `app/build.gradle.kts` (release signing from `keystore.properties`, which is git-ignored; minify off)
- Create: `docs/RUNBOOK.md` (build, install, agent update, re-pair, checkpoint steps)

- [ ] **Step 1:** Generate a keystore with `keytool -genkeypair -v -keystore %USERPROFILE%\.android\wake-release.jks -alias wake -keyalg RSA -keysize 4096 -validity 36500` (John types the passwords). Run `./gradlew :app:assembleRelease :agent:agentJar test`. Expected: all tests pass and the release APK is built.
- [ ] **Step 2:** `adb -s <serial> install -r app/build/outputs/apk/release/app-release.apk`. If the debug signature conflicts, uninstall the debug build first (data is re-created by pairing).
- [ ] **Step 3:** TalkBack pass: enable it with `adb shell settings put secure enabled_accessibility_services com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService` (or Samsung's equivalent), walk through the main screen, and confirm the hero label, the live caption and that Shut down opens the confirm dialog. Cancel it. Disable TalkBack.
- [ ] **Step 4: CHECKPOINT 2 (John), real end-to-end.** Give John this checklist and wait for his results:
  1. From the phone, Lock → the PC locks.
  2. Sleep → the PC sleeps and the app reaches Off. Then tap Wake → the PC wakes and the app reaches On.
  3. Hold Shut down, then tap Undo within 3 s → nothing happens and the app stays On.
  4. Hold Reboot → the PC restarts, the app goes Off → Waking → On, and the agent reconnects after login.
  5. Hold Shut down and let it fire → the app reaches Off within about 10 s.
  6. Tap Wake → the PC boots and the app reaches On within 90 s.
  7. Turn off Wi-Fi on the phone → Away notice. Turn it back on → the state recovers.
- [ ] **Step 5:** Fix anything John reports, then commit `chore: release signing, runbook` and any fixes.

---

## Self-review notes

- Spec coverage:
  - §4.1 → T2/T11
  - §4.2 → T9 (probe check), T11, T13, T14
  - §4.3 → T6–T8
  - §4.4 → T12
  - §5.1 → T16
  - §5.2 → T15
  - §5.3 → T16
  - §6 → T13/T15
  - §7 → T10/T15
  - §8 → T9
  - §9 → T15/T16
  - §10 → T10/T15
  - §11 → T17
- The spec's single `/pair` route is implemented as `/pair/start` + `/pair/finish` (one exchange each way). This is a builder-level detail.
