# Cold-Boot Fingerprint Sign-In Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After waking the PC from full shutdown, the phone's Unlock button and fingerprint sign the user in at the Windows sign-in screen.

**Architecture:** A new program, `WakeSignIn.exe` ("the service" below), is started at boot by the scheduled task `Wake Sign-in`. The task runs as `LOCAL SERVICE` with its own task SID, `NT TASK\Wake Sign-in`. The program is built from the `agent` module with its own entry point and shipped as a second jpackage launcher. It listens on TCP 47615 only while the credential provider's pipe exists, serves just `/signin/status` and `/signin/unlock`, and reuses `PipeUnlockRelay`. It authenticates with `signInKey`, an HKDF-derived subkey of the pairing secret; the tray agent writes it to ProgramData, encrypted with DPAPI machine scope. The DLL adds the service SID to its pipe DACL and picks the installing user from an admin-only registry value.

**Tech Stack:** Existing: Kotlin 2.2.20, JDK 21 + jpackage, JNA 5.17.0 (jna-platform: `Advapi32`, `Crypt32Util`, `Winsvc`), `com.sun.net.httpserver`, kotlin-test, Compose/OkHttp on the phone, C++20/MSVC/CMake for the DLL, PowerShell for `setup.ps1`. Nothing new.

**Spec:** `docs/specs/2026-10-28-cold-boot-sign-in-design.md` (builds on `docs/specs/2026-10-27-fingerprint-unlock-design.md`).

## Global Constraints

- Scheduled task `\Wake Sign-in` runs `C:\Program Files\Wake\SignIn\WakeSignIn.exe` as `NT AUTHORITY\LOCAL SERVICE` (`-LogonType ServiceAccount`), with `-ProcessTokenSidType Unrestricted` and `-RequiredPrivilege SeChangeNotifyPrivilege`. Triggers: at startup, plus a repeat every 5 minutes, indefinitely; `MultipleInstances IgnoreNew`; no execution time limit. Every grant names the task SID `NT TASK\Wake Sign-in`, never `LOCAL SERVICE`. Never SYSTEM, never a user, never admin.
- `signInKey = HKDF-SHA256(secret, salt = empty, info = "wake-signin-v1", 32)`. The service never sees the pairing secret.
- Port `Ports.SIGNIN = 47615`. Paths exactly `/signin/status` and `/signin/unlock`. Every other path returns 404.
- Request body cap 4096 bytes → 413 `too_large`, on both the agent and the service.
- Service listens only while `\\.\pipe\wake-unlock` exists; pipe checked every 1000 ms; port closed 5000 ms after the pipe disappears and no request is in flight.
- Service binaries: `C:\Program Files\Wake\SignIn\` (admin-only write). Key: `%ProgramData%\Wake\signin\signin.key`, DPAPI flags `CRYPTPROTECT_LOCAL_MACHINE (0x4) | CRYPTPROTECT_UI_FORBIDDEN (0x1)`. Service log: `%ProgramData%\Wake\logs\signin.log`.
- Registry: `HKLM\SOFTWARE\Wake`, value `SignInUserSid` (REG_SZ).
- Pipe owner must be `S-1-5-18` (unchanged). Pipe DACL: `O:SYD:(A;;GA;;;SY)(A;;GRGW;;;<user SID>)` plus `(A;;GRGW;;;<service SID>)` when the service account resolves.
- Firewall rule `Wake Sign-in`: inbound TCP 47615, `RemoteAddress LocalSubnet`, `-Program C:\Program Files\Wake\SignIn\WakeSignIn.exe`.
- Opt-in prompt, exact copy: `Allow fingerprint sign-in after shutdown? (y/N)`. Switches `-SignIn` / `-NoSignIn`. Asked only when the unlock module is installed.
- Phone copy, exact: caption `On, at sign-in`; success `<name> signed in`.
- Passwords stay `ByteArray`, are zeroed after use and are never logged. One attempt per tap, never retried. `wrong_password` deletes the phone's saved password.
- Version 1 behavior is unchanged when sign-in isn't opted in.
- Never install the service or DLL, run the elevated setup, or sign in or lock this PC. Stop at CHECKPOINT steps and wait for John. Service and DLL runs happen in the Hyper-V VM first.
- Public repo: only generic placeholders (`MY-PC`, `0A:1B:2C:3D:4E:5F`, `192.168.1.x`). Dummy passwords only.
- Commit messages: Conventional Commits, no AI attribution of any kind.
- Controlled Folder Access may block new files: if Write fails with ENOENT, `touch` the file in Bash first, or write in the scratchpad and `cp`.

## Review Focus

1. **More than one Windows account on the PC** → the sign-in goes to the account that installed Wake, not to whichever tile LogonUI lists first. Pinned by Task 3 tests `picks_preferred_user` and `falls_back_to_first_when_not_listed`.
2. **A request captured on the agent's port and replayed to the service (or the other way)** → rejected. Pinned by Task 2 `pairingSecretSignatureFailsUnderSignInKey` and Task 5 `requestSignedWithPairingSecretIsRejected`, `agentPathsAreNotServed`.
3. **Large or garbage body sent to the pre-login port** → 413 without reading it into memory. Pinned by Task 5 `oversizedBodyIsRefused` (service) and `oversizedBodyIsRefusedByAgent`.
4. **User signs in by hand while the phone is mid-request, or the session unlocks** → the request finishes, then the port closes within 5 s. Pinned by Task 6 `waitsForInFlightRequest` and `closesAfterGraceWhenIdle`.
5. **Lock screen while the agent is running (version 1 case)** → the phone still unlocks through the agent, not the service. Pinned by Task 10 `unlockPrefersAgentWhenAgentAnswers`.

---

## Phase A: Spike the startup task (answers the risky questions first)

### Task 1: Does a startup task with its own task SID run before sign-in and see the pipe?

**Files:**
- Create: `agent/src/main/kotlin/dev/jano/wake/agent/signin/SignInMain.kt` (spike version: single-instance lock on `%ProgramData%\Wake\logs\signin.lock`, logs `started` with the process's user and group SIDs from `WindowsIdentity` via JNA `Advapi32Util.getCurrentUserGroups()`, then a 1 s loop that logs `pipe=<true|false>` from `WindowsPipeHost().pipeExists()` whenever the value changes)
- Create: `agent/jpackage/WakeSignIn.properties` (`main-class=dev.jano.wake.agent.signin.SignInMainKt`, `win-console=false`)
- Modify: `agent/build.gradle.kts` (`agentImage`: add `--add-launcher`, `WakeSignIn=<absolute path of agent/jpackage/WakeSignIn.properties>`)
- Create in the scratchpad, not the repo: `spike-install.ps1`, run by John in the VM as admin

**Interfaces:**
- Produces: `fun main(args: Array<String>)` in `SignInMainKt`. `fun signInLogDir(): Path = Path(System.getenv("ProgramData") ?: "C:\\ProgramData", "Wake", "logs")`.
- Produces: `WakeAgent\WakeSignIn.exe` next to `WakeAgent.exe` in the image, sharing its runtime.

- [ ] **Step 1: Write the spike** as above. A second instance exits at once with exit code 0.
- [ ] **Step 2: Build the image.** Run `.\gradlew :agent:agentImage`. Expected: `agent\build\agent-image\WakeAgent\WakeSignIn.exe` exists.
- [ ] **Step 3: Write `spike-install.ps1`.** It copies the image folder to `C:\Program Files\Wake\SignIn`. It registers task `Wake Sign-in` exactly as in Global Constraints. It then grants the task SID modify on `C:\ProgramData\Wake\logs` with `icacls … /grant "NT TASK\Wake Sign-in:(OI)(CI)M"`; the task must exist before its SID resolves. It prints `([Security.Principal.NTAccount]'NT TASK\Wake Sign-in').Translate([Security.Principal.SecurityIdentifier]).Value`. `-Remove` undoes all of it.
- [ ] **Step 4: CHECKPOINT (John, in the VM: release 1.1 installed with fingerprint unlock, then `spike-install.ps1`).** Check each:
  1. The script prints a SID starting `S-1-5-87-`, and `icacls` accepted the name.
  2. Shut the VM down and Start it again. Without signing in, wait 1 minute, then sign in and open the log. It shows `started` with a time before the sign-in, and the user `S-1-5-19` with the `S-1-5-87-…` SID among the groups.
  3. The log shows `pipe=true` from the sign-in screen **without anything pressed in the VM**, then `pipe=false` after sign-in. If `pipe=true` only shows after a key press or click, write that down: the phone flow then needs one, and the spec changes.
  4. In Task Manager → Details, end `WakeSignIn.exe`. A new one starts within 5 minutes (log shows `started` again).
  5. Starting `WakeSignIn.exe` a second time by hand doesn't create a second process.

  If item 1, 2 or 4 fails, stop and bring the spec's §9 fallback to John.
- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/dev/jano/wake/agent/signin/SignInMain.kt agent/jpackage/WakeSignIn.properties agent/build.gradle.kts
git commit -m "feat(signin): pre-login sign-in program, started by a startup task (spike)"
```

## Phase B: Protocol

### Task 2: Sign-in key, port, paths and status message

**Files:**
- Create: `protocol/src/main/kotlin/dev/jano/wake/protocol/SignIn.kt`
- Modify: `protocol/src/main/kotlin/dev/jano/wake/protocol/Ports.kt`
- Test: `protocol/src/test/kotlin/dev/jano/wake/protocol/SignInTest.kt`

**Interfaces:**
- Produces: `Ports.SIGNIN = 47615`; `const val PATH_SIGNIN_STATUS = "/signin/status"`; `const val PATH_SIGNIN_UNLOCK = "/signin/unlock"`; `fun signInKey(secret: ByteArray): ByteArray`; `@Serializable data class SignInStatus(val mac: String, val serverTimeMs: Long, val signInScreen: Boolean)`.
- Consumes: `hkdfSha256` (Pairing.kt), `sign`, `RequestVerifier`, `sealPassword`, `openPassword`.

- [ ] **Step 1: Write the failing tests**
  - `signInKeyIsHkdfOfSecret`: `assertContentEquals(hkdfSha256(secret, ByteArray(0), "wake-signin-v1".toByteArray(), 32), signInKey(secret))`, with `secret = ByteArray(32) { it.toByte() }`.
  - `signInKeyDiffersFromSecretAndFromUnlockKeyInput`: `assertFalse(signInKey(secret).contentEquals(secret))`.
  - `pairingSecretSignatureFailsUnderSignInKey`: headers from `sign(secret, "POST", PATH_SIGNIN_UNLOCK, body, now)` verified by `RequestVerifier({ signInKey(secret) }, { now })` → `VerifyResult.BAD_SIGNATURE`.
  - `passwordSealedWithSignInKeyOpensOnlyWithIt`: `openPassword(signInKey(secret), nonce, sealPassword(signInKey(secret), nonce, pw))` equals `pw`; `openPassword(secret, nonce, …)` is null.
  - `signInStatusRoundTrips` through `WakeJson`.
- [ ] **Step 2: Run** `.\gradlew :protocol:test --tests "*SignInTest*"`. Expected: FAIL, unresolved references.
- [ ] **Step 3: Implement** `SignIn.kt` and the port constant.
- [ ] **Step 4: Run** `.\gradlew :protocol:test`. Expected: PASS, all protocol tests.
- [ ] **Step 5: Commit** `feat(protocol): sign-in key, port and status message`

## Phase C: Credential provider

### Task 3: Pick the installing user; let the service open the pipe

**Files:**
- Create: `credential-provider/src/SignInConfig.h`, `credential-provider/src/SignInConfig.cpp`
- Create: `credential-provider/tests/sign_in_config_test.cpp`
- Modify: `credential-provider/CMakeLists.txt` (DLL source + new test executable, registered with `add_test` per case like the existing ones)
- Modify: `credential-provider/src/PipeServer.h`, `PipeServer.cpp` (`Start` signature, SDDL from `BuildPipeSddl`)
- Modify: `credential-provider/src/Provider.cpp` (`SetUserArray` collects every user's SID and picks one; `StartPipeIfReady` passes the service SID)

**Interfaces:**
- Produces (`SignInConfig.h`, the pure parts with no Windows calls so they unit-test):
  - `size_t PickUser(const std::vector<std::wstring>& sids, const std::optional<std::wstring>& preferred)`: index of a case-insensitive match, else 0.
  - `bool LooksLikeSid(const std::wstring& s)`: `S-1-` prefix, then only digits and `-`, at most 184 chars.
  - `std::wstring BuildPipeSddl(bool systemOwner, const std::wstring& userSid, const std::optional<std::wstring>& serviceSid)`.
- Produces (Windows parts, same files): `std::optional<std::wstring> ReadPreferredUserSid()` (`RegGetValueW(HKEY_LOCAL_MACHINE, L"SOFTWARE\\Wake", L"SignInUserSid", RRF_RT_REG_SZ, …)`, nullopt unless `LooksLikeSid`); `std::optional<std::wstring> LookupServiceSid()` (`LookupAccountNameW(nullptr, L"NT TASK\\Wake Sign-in", …)` → `ConvertSidToStringSidW`, nullopt on any failure; the account name is one `constexpr` so the §9 fallback changes one line).
- Produces: `HRESULT PipeServer::Start(IUnlockSink* sink, const std::wstring& lockedUserSid, const std::optional<std::wstring>& serviceSid = std::nullopt)`.

- [ ] **Step 1: Write the failing native tests:**
  - `picks_preferred_user`: `PickUser({L"S-1-5-21-1-1001", L"S-1-5-21-1-1002"}, L"S-1-5-21-1-1002") == 1`.
  - `picks_preferred_case_insensitive`: lower-case `s-1-…` preferred still matches.
  - `falls_back_to_first_when_absent`: preferred `nullopt` → 0.
  - `falls_back_to_first_when_not_listed`: preferred `S-1-5-21-1-1003` → 0.
  - `sddl_without_service`: `BuildPipeSddl(true, L"S-1-5-21-1-1001", std::nullopt) == L"O:SYD:(A;;GA;;;SY)(A;;GRGW;;;S-1-5-21-1-1001)"`.
  - `sddl_with_service`: same plus `(A;;GRGW;;;S-1-5-87-1-2-3-4-5)`.
  - `sddl_drops_invalid_service_sid`: service `L"S-1-5-87)(A;;GA;;;WD"` → output equals `sddl_without_service`.
  - `rejects_non_sid_strings`: `LooksLikeSid(L"")`, `LooksLikeSid(L"WD")` and `LooksLikeSid(L"S-1-5-21-1)(A")` are false.
- [ ] **Step 2: Run** `credential-provider\build.cmd`. Expected: build fails or the new tests fail.
- [ ] **Step 3: Implement** `SignInConfig`, then switch `PipeServer::Start` to `BuildPipeSddl`. `Provider::SetUserArray` reads every user's SID, calls `PickUser(sids, ReadPreferredUserSid())`, and uses that user for name, SID and `isLocal`. `StartPipeIfReady` passes `LookupServiceSid()`. Keep the existing `try/catch (...)` around every COM method.
- [ ] **Step 4: Run** `credential-provider\build.cmd`. Expected: all ctest cases pass, including the existing `pipe_server_test` cases (they keep `systemOwner = false`, which `BuildPipeSddl` honors).
- [ ] **Step 5: Commit** `feat(unlock): pipe opens to the sign-in service; tile follows the installing user`

## Phase D: PC side

### Task 4: Tray agent writes the sign-in key

**Files:**
- Create: `agent/src/main/kotlin/dev/jano/wake/agent/signin/SignInKeyStore.kt`
- Modify: `agent/src/main/kotlin/dev/jano/wake/agent/Main.kt` (call `syncSignInKey` at startup and in `onPaired`)
- Test: `agent/src/test/kotlin/dev/jano/wake/agent/signin/SignInKeyStoreTest.kt`

**Interfaces:**
- Produces: `interface KeyProtector { fun protect(plain: ByteArray): ByteArray; fun unprotect(blob: ByteArray): ByteArray? }`; `object DpapiMachineProtector : KeyProtector` (`Crypt32Util.cryptProtectData(plain, null, 0x5, "", null)` / `cryptUnprotectData(blob, null, 0x1, null)`, null on `Win32Exception`); `class SignInKeyStore(dir: Path, protector: KeyProtector)` with `fun load(): ByteArray?`, `fun save(key: ByteArray): Boolean` (false when `dir` doesn't exist: the agent **never creates** it, because only the installer sets its ACL) and `fun clear()`; `fun syncSignInKey(secret: ByteArray?, keys: SignInKeyStore)`.
- Produces: `fun signInDataDir(): Path = Path(System.getenv("ProgramData") ?: "C:\\ProgramData", "Wake", "signin")`, used by Task 6 too.
- Consumes: `signInKey` (Task 2).

- [ ] **Step 1: Write the failing tests** (temp dir; a fake `KeyProtector` that XORs with `0x5A`):
  - `writesDerivedKeyWhenPaired`: after `syncSignInKey(secret, store)`, `store.load()` equals `signInKey(secret)` and the file bytes are not the plain key.
  - `rewritesKeyAfterRepair`: sync with secret A, then B → load equals `signInKey(B)`.
  - `leavesFileAloneWhenUnchanged`: second sync with the same secret doesn't change the file's last-modified time.
  - `deletesKeyWhenNotPaired`: sync with `null` → file gone.
  - `saveDoesNothingWhenFolderMissing`: `dir` not created → `save` returns false and nothing is created.
  - `unreadableBlobLoadsNull`: garbage file and `unprotect` returning null → `load()` null.
- [ ] **Step 2: Run** `.\gradlew :agent:test --tests "*SignInKeyStoreTest*"`. Expected: FAIL.
- [ ] **Step 3: Implement**, then wire into `Main.kt`: `val signInKeys = SignInKeyStore(signInDataDir(), DpapiMachineProtector)`, call `syncSignInKey(store.load(), signInKeys)` after `store` is created and inside `onPaired`, wrapped in `runCatching` with a log line on failure (it must never stop the agent).
- [ ] **Step 4: Run** `.\gradlew :agent:test`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(agent): keep the sign-in key in ProgramData for the sign-in service`

### Task 5: Shared signed-request handling, body cap, and the sign-in server

**Files:**
- Create: `agent/src/main/kotlin/dev/jano/wake/agent/SignedExchange.kt` (the `signed`, `error` and `json` logic moved out of `AgentServer`, parameterized by key)
- Modify: `agent/src/main/kotlin/dev/jano/wake/agent/AgentServer.kt` (use `SignedExchange`; read bodies with `readCapped`)
- Create: `agent/src/main/kotlin/dev/jano/wake/agent/signin/SignInServer.kt`
- Test: `agent/src/test/kotlin/dev/jano/wake/agent/signin/SignInServerTest.kt`; modify `AgentServerTest.kt` (one new test)

**Interfaces:**
- Produces: `const val MAX_BODY_BYTES = 4096`; `fun readCapped(exchange: HttpExchange, max: Int = MAX_BODY_BYTES): ByteArray?` (null when `Content-Length` > max or more than max bytes arrive; reads at most max + 1 bytes); `class SignedExchange(key: () -> ByteArray?, clock: () -> Long)` with `fun <T> handle(exchange: HttpExchange, body: ByteArray, handler: () -> Pair<T, KSerializer<T>>)`, `fun error(exchange: HttpExchange, status: Int, code: String)`, `fun json(exchange: HttpExchange, status: Int, text: String)`.
- Produces: `class SignInServer(bind: InetSocketAddress, mac: () -> String, keys: () -> ByteArray?, clock: () -> Long, unlock: UnlockRelay)` with `start()`, `stop()`, `val port: Int`, `fun busy(): Boolean` (true while any request is being handled). Executor: `Executors.newFixedThreadPool(2)`.
- Consumes: `PATH_SIGNIN_*`, `SignInStatus` (Task 2); `UnlockRelay` (existing).

- [ ] **Step 1: Write the failing tests** in `SignInServerTest`, built like `AgentServerTest` (bound to `127.0.0.1:0`, fake `UnlockRelay`, `key = signInKey(secret)`):
  - `statusSignedWithSignInKeyAnswers`: 200; `signInScreen` mirrors the fake's `lockScreen() == true`; `mac == "0A:1B:2C:3D:4E:5F"`.
  - `requestSignedWithPairingSecretIsRejected`: signed with `secret` → 401 `bad_signature`.
  - `agentPathsAreNotServed`: `/status`, `/command`, `/unlock`, `/pair/start` → 404.
  - `unlockOpensPasswordAndRelays`: relay receives the exact dummy bytes `"correct horse"`; reply `unlocked`.
  - `unlockSealedForAnotherNonceIsBadRequest`: 400 `bad_request`, relay not called.
  - `oversizedBodyIsRefused`: 4097-byte body → 413 `too_large`, relay not called.
  - `notPairedWhenNoKey`: `keys = { null }` → 401 `not_paired`.
  - `staleProofUsesSignInKey`: timestamp 60 s old → 401 `stale` with `proof == staleProof(signInKey(secret), nonce, serverTimeMs)`.
  - `busyWhileUnlockInFlight`: the fake relay blocks on a latch; `busy()` is true until it's released.
  - In `AgentServerTest`: `oversizedBodyIsRefusedByAgent`: 4097-byte `/command` → 413 `too_large`.
- [ ] **Step 2: Run** `.\gradlew :agent:test`. Expected: the new tests fail; the existing ones still pass.
- [ ] **Step 3: Extract `SignedExchange`** from `AgentServer` with no behavior change, and add `readCapped` to both routers.
- [ ] **Step 4: Implement `SignInServer`.** `/signin/unlock` does what `AgentServer`'s `/unlock` does, with `keys()` in place of `store.load()`.
- [ ] **Step 5: Run** `.\gradlew :agent:test`. Expected: PASS, old and new.
- [ ] **Step 6: Commit** `feat(signin): sign-in server with a 4 KiB body cap (agent gets the cap too)`

### Task 6: Listen only while the lock or sign-in screen is up; finish the service

**Files:**
- Create: `agent/src/main/kotlin/dev/jano/wake/agent/signin/SignInGate.kt`
- Modify: `agent/src/main/kotlin/dev/jano/wake/agent/signin/SignInMain.kt` (spike → real)
- Test: `agent/src/test/kotlin/dev/jano/wake/agent/signin/SignInGateTest.kt`

**Interfaces:**
- Produces: `class SignInGate(pipeExists: () -> Boolean, inFlight: () -> Boolean, clock: () -> Long, open: () -> Unit, close: () -> Unit, graceMs: Long = 5_000)` with `fun tick()` and `val isOpen: Boolean`.
- Consumes: `SignInServer` (Task 5), `SignInKeyStore` + `signInDataDir()` (Task 4), `pickLanInterface`, `WindowsSystem`, `PipeUnlockRelay`, `WindowsPipeHost`.

- [ ] **Step 1: Write the failing tests** (fake clock, counters for open and close):
  - `opensWhenPipeAppears`: pipe true → `tick()` → open called once, `isOpen`.
  - `noOpenTwice`: two ticks with pipe true → open called once.
  - `staysOpenWithinGrace`: pipe goes false; ticks at +1 s and +4.9 s → close not called.
  - `closesAfterGraceWhenIdle`: pipe false at t0; tick at t0 + 5 s → close called once.
  - `waitsForInFlightRequest`: grace passed, `inFlight` true → no close; `inFlight` false on the next tick → close.
  - `pipeReturningDuringGraceCancelsClose`: false, then true at +3 s, then ticks at +6 s → no close.
  - `reopensWhenPipeReturns`: closed, pipe true again → open called a second time.
- [ ] **Step 2: Run** `.\gradlew :agent:test --tests "*SignInGateTest*"`. Expected: FAIL.
- [ ] **Step 3: Implement `SignInGate`.**
- [ ] **Step 4: Finish `SignInMain`.** Keep the single-instance lock. Every 1000 ms: `gate.tick()`. Every 30 s: re-pick the LAN adapter; if the IP changed while open, close and reopen on the new IP. `open` builds a `SignInServer` on `InetSocketAddress(nic.ipv4, Ports.SIGNIN)` with `mac = { nic.mac!! }`, `keys = signInKeys::load` and `PipeUnlockRelay(WindowsPipeHost(), ::log)`. No adapter means stay closed. Log to `%ProgramData%\Wake\logs\signin.log`: start, stop, open on `<ip>`, closed, relay results (never the password). A JVM shutdown hook closes the server. Keep `pipe=` changes in the log, and drop the spike's SID dump.
- [ ] **Step 5: Run** `.\gradlew :agent:test :agent:agentImage`. Expected: tests PASS; `WakeSignIn.exe` is in the image.
- [ ] **Step 6: Commit** `feat(signin): service listens only while the lock or sign-in screen is up`

### Task 7: Installer, uninstaller and -Check

**Files:**
- Modify: `pc-setup/setup.ps1`
- Modify: `pc-setup/README.md` (new item 7 and its switches)

**Interfaces:**
- Consumes: the image's `WakeSignIn.exe` (Task 6); `HKLM\SOFTWARE\Wake\SignInUserSid` read by the DLL (Task 3); `%ProgramData%\Wake\signin` written by the agent (Task 4).
- Produces: functions `Install-SignIn($sourceDir)`, `Remove-SignIn`, `Test-SignIn`, plus parameters `-SignIn` and `-NoSignIn`.

- [ ] **Step 1: Add item 7, "Fingerprint sign-in after shutdown",** after item 6. It's asked only when the unlock module is now installed and the run is a bundled one (`-AgentDir`); otherwise the note reads `needs the release download and the unlock module`. `Install-SignIn` runs in this order (the task SID only resolves once the task exists):
  1. If the task exists, `Stop-ScheduledTask` it and stop any `WakeSignIn.exe`. Copy `$sourceDir` to `C:\Program Files\Wake\SignIn` when the `app\wake-agent.jar` hash differs.
  2. Register task `Wake Sign-in` exactly as in Global Constraints (`-Force` replaces it), with description `Lets your paired phone sign you in at the Windows sign-in screen.`
  3. Folders with `icacls /inheritance:r /grant:r`: `%ProgramData%\Wake\signin` → `*S-1-5-18:(OI)(CI)F`, `*S-1-5-32-544:(OI)(CI)F`, `<installing user>:(OI)(CI)M`, `NT TASK\Wake Sign-in:(OI)(CI)RX`. `%ProgramData%\Wake\logs` → SYSTEM F, Administrators F, `NT TASK\Wake Sign-in:(OI)(CI)M`, user `(OI)(CI)RX`.
  4. `HKLM:\SOFTWARE\Wake` `SignInUserSid` = `[Security.Principal.WindowsIdentity]::GetCurrent().User.Value`.
  5. Firewall rule `Wake Sign-in` as in Global Constraints, replaced when its program differs.
  6. `Start-ScheduledTask 'Wake Sign-in'`. The existing final `Stop-Agent` / `Start-ScheduledTask` restarts the tray agent, which writes the key.
- [ ] **Step 2: `-NoSignIn` and `-Uninstall` call `Remove-SignIn`:** stop and unregister the task, stop any `WakeSignIn.exe`, remove the rule, `C:\Program Files\Wake\SignIn`, `%ProgramData%\Wake` and `HKLM:\SOFTWARE\Wake`. Call it **before** `Remove-UnlockModule`, which removes `C:\Program Files\Wake` only when it's empty. Turning unlock off (`-NoUnlock`) also removes sign-in.
- [ ] **Step 3: `-Check` rows:** sign-in task (missing / ready / running, plus its principal), firewall rule, key file present, `SignInUserSid` matches the current user. Add a report-only `BitLocker on C:` row from `Get-BitLockerVolume -MountPoint C:` (`ProtectionStatus`). Without admin, show `unknown (needs admin)` and count it as OK.
- [ ] **Step 4: Parse check.** Run `powershell -NoProfile -Command "[void][System.Management.Automation.Language.Parser]::ParseFile('pc-setup\setup.ps1',[ref]$null,[ref]$e); $e"`. Expected: no output. Then run `powershell -ExecutionPolicy Bypass -File pc-setup\setup.ps1 -Check` (read-only, safe on this PC). Expected: the new rows appear, nothing changes.
- [ ] **Step 5: CHECKPOINT (John, in the VM).** Run the release zip's `Install Wake Agent.cmd` and answer `y` to both questions. Then check:
  1. `Get-ScheduledTask 'Wake Sign-in' | Select -Expand Principal` shows `LOCAL SERVICE`, `ProcessTokenSidType Unrestricted` and only `SeChangeNotifyPrivilege`.
  2. `icacls` on both folders matches Step 1.
  3. `signin.key` appears within 30 s of the tray agent starting.
  4. While signed in and unlocked, `Test-NetConnection <vm-ip> -Port 47615` from another machine fails. After Win+L it succeeds within 2 s. After unlocking, it fails again within about 6 s.
  5. `-Check` is all yes. `-Uninstall` leaves no task, process, rule, folder or registry key.
- [ ] **Step 6: Commit** `feat(setup): install the sign-in task, locked down and opt-in`

## Phase E: Phone

### Task 8: Sign-in client

**Files:**
- Modify: `app/src/main/kotlin/dev/jano/wake/net/AgentClient.kt`
- Modify: `app/src/main/kotlin/dev/jano/wake/main/AppGraph.kt`
- Test: `app/src/test/kotlin/dev/jano/wake/net/AgentClientTest.kt`; update the test `AppGraph` builders in `MainViewModelTest.kt` and `SettingsViewModelTest.kt` with a fake `signIns`

**Interfaces:**
- Produces: `AgentClient.signInStatus(): SignInStatus` (GET `PATH_SIGNIN_STATUS`) and `AgentClient.signInUnlock(password: ByteArray): UnlockResponse` (POST `PATH_SIGNIN_UNLOCK`, sealed per attempt, `unlockHttp`). The caller constructs the client with `signInKey(secret)` and port 47615.
- Produces: `interface SignInApi { suspend fun status(): SignInStatus; suspend fun unlock(password: ByteArray): UnlockResponse }`; `fun interface SignInApiFactory { fun create(device: Device, secret: ByteArray): SignInApi }`; a new `AppGraph` constructor parameter `signIns: SignInApiFactory`. The real one: `AgentClient(http, "http://${device.lastIp}:${Ports.SIGNIN}", signInKey(secret), System::currentTimeMillis)`.

- [ ] **Step 1: Write the failing tests** in `AgentClientTest` (same server harness as its existing tests):
  - `signInStatusIsSignedWithSignInKeyOnSignInPath`: the recorded request's path is `/signin/status`, and its signature verifies with `RequestVerifier({ signInKey(secret) }, …)` when the client was built with `signInKey(secret)`.
  - `signInUnlockSealsWithClientKey`: the server-side `openPassword(signInKey(secret), nonce, body)` yields the dummy password.
- [ ] **Step 2: Run** `.\gradlew :app:testDebugUnitTest --tests "*AgentClientTest*"`. Expected: FAIL.
- [ ] **Step 3: Implement** both methods and the factory.
- [ ] **Step 4: Run** `.\gradlew :app:testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(app): client for the sign-in service`

### Task 9: Show Unlock at the sign-in screen and route it to the service

**Files:**
- Modify: `app/src/main/kotlin/dev/jano/wake/main/MainViewModel.kt`, `MainUiState.kt`
- Modify: `app/src/main/kotlin/dev/jano/wake/ui/main/Presentation.kt`, `MainScreen.kt`
- Test: `app/src/test/kotlin/dev/jano/wake/main/MainViewModelTest.kt`, `app/src/test/kotlin/dev/jano/wake/ui/main/PresentationTest.kt`

**Interfaces:**
- Produces: `MainUiState.signInScreen: Boolean = false`, true when `pc is PcState.On && !pc.agent` and the last `SignInStatus.signInScreen` was true. `Presentation.unlockEnabled(state: MainUiState): Boolean`: no Undo showing, and either (agent present and `lockScreen`) or `signInScreen`.
- Consumes: `SignInApiFactory` (Task 8).

- [ ] **Step 1: Write the failing tests.**
  - `MainViewModelTest`:
    - `signInScreenShownWhenAgentSilentAndServiceSaysSo`: host up, agent status throws, sign-in fake returns `signInScreen = true` with the device's MAC → `ui.signInScreen`.
    - `signInReplyFromOtherMacIgnored`: MAC `0A:1B:2C:3D:4E:60` → `signInScreen` false.
    - `signInNotAskedWhenAgentAnswers`: the agent answers → the sign-in fake's `status()` call count is 0.
    - `unlockWithoutAgentGoesToSignInService`: only the sign-in fake's `unlock` is called; message `MY-PC signed in`.
    - `unlockPrefersAgentWhenAgentAnswers`: agent `lockScreen = true` and sign-in also true → only the agent's `unlock` is called.
    - `wrongPasswordViaSignInDeletesSavedPassword`: the saved password is removed; `OpenUnlockSetup` with the existing copy.
    - `signInUnreachableShowsMessageAndDoesNotRetry`: unlock throws → one call, existing unreachable message.
  - `PresentationTest`:
    - `captionAtSignIn`: `On(agent = false)` + `signInScreen` → `On, at sign-in`.
    - `unlockEnabledAtSignInWhileOtherCommandsDisabled`: `unlockEnabled` true, `commandsEnabled` false.
- [ ] **Step 2: Run** `.\gradlew :app:testDebugUnitTest`. Expected: the new tests FAIL.
- [ ] **Step 3: Implement.**
  - `pollOnce`: when `host is Up`, `secret != null` and `status == null`, query `graph.signIns.create(device, secret).status()` (`runCatching`, MAC-checked). Otherwise clear it.
  - `unlock()` guard becomes `agentReady() || signInReady()`. `runUnlock` picks the agent API when `agentReady()`, else the sign-in API. The success copy follows the route. On `UNLOCKED` or `NOT_LOCKED` through sign-in, clear the sign-in status.
  - `MainScreen`: Unlock replaces Lock when `state.lockScreen || state.signInScreen || state.unlocking`, enabled by `Presentation.unlockEnabled(state)`. The other buttons keep `commandsEnabled`.
- [ ] **Step 4: Run** `.\gradlew :app:testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(app): sign in from the phone after a cold boot`

## Phase F: Prove it end to end, then document

### Task 10: End-to-end in the VM, docs, version

**Files:**
- Modify: `README.md` (Fingerprint unlock section: replace "Not after a restart" with how sign-in after shutdown works, the opt-in question, and a BitLocker recommendation; add a Troubleshooting row for "Unlock doesn't show after a cold boot": run `-Check`, look at the service row and `%ProgramData%\Wake\logs\signin.log`)
- Modify: `docs/specs/2026-10-27-fingerprint-unlock-design.md` §13 (one line pointing to the new spec)
- Modify: `agent/src/main/kotlin/dev/jano/wake/agent/Main.kt` (`AGENT_VERSION = "1.2"`), `app/build.gradle.kts` (versionName `1.2`, versionCode +1)

- [ ] **Step 1: Full build.** Run `.\gradlew test :agent:agentZip :app:assembleDebug` and `credential-provider\build.cmd`. Expected: all green, zip and APK built.
- [ ] **Step 2: CHECKPOINT (John, VM + phone).** Check each:
  1. Shut down from the phone, wake from the phone. The caption reads `On, at sign-in`, Unlock shows, and the other commands are disabled.
  2. Unlock + fingerprint → desktop. The message reads `MY-PC signed in`. Then the tray agent appears, the phone shows `On for …`, and port 47615 closes.
  3. Same with a Microsoft account (MSA) on the VM.
  4. Change the Windows password, cold boot, Unlock → `didn't accept the saved password`; the password is gone from the phone; exactly one failed logon in Event Viewer (4625).
  5. A second local account on the VM: the sign-in goes to the installing account.
  6. Win+L with the agent running: unlock still works (through the agent; the service log shows no relay).
  7. Install with `n` to the sign-in question: behavior matches version 1 exactly (no service, port 47615 closed at the sign-in screen).
- [ ] **Step 3: Update the docs and versions** as listed in Files. Use only placeholder names and addresses.
- [ ] **Step 4: Commit** `docs: fingerprint sign-in after shutdown; version 1.2`
