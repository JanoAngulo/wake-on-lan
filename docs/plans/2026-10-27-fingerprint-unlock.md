# Fingerprint Unlock Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Unlock the PC's lock screen from Wake with the phone's fingerprint (after Lock or Sleep; not cold boot).

**Architecture:** The phone keeps the Windows password in a fingerprint-gated Android Keystore key and sends it, AES-GCM-encrypted under a key derived from the existing pairing secret, in a signed `POST /unlock`. The Kotlin agent decrypts it and hands it over a local named pipe to a new C++ Credential Provider DLL loaded by LogonUI, after checking the pipe is owned by SYSTEM/Administrators. The DLL auto-submits the password for the locked user and reports Windows' NTSTATUS back.

**Tech Stack:** Existing: Kotlin 2.2.20, JDK 21, AGP 8.13.0, Compose, OkHttp 5.1.0, JNA 5.17.0, kotlin-test. New: `androidx.biometric:biometric:1.1.0`; C++20 with MSVC (Visual Studio 2022 Build Tools, C++ workload + Windows 11 SDK) and CMake (bundled with the Build Tools).

**Spec:** `docs/specs/2026-10-27-fingerprint-unlock-design.md` (builds on `docs/specs/2026-10-26-wake-on-lan-design.md`).

## Global Constraints

- Pipe name `\\.\pipe\wake-unlock`. Pipe frames: request `u32 version = 1`, `u32 length`, `length` bytes UTF-16LE password; reply `u32 status`, `u32 substatus`. All little-endian.
- Encryption key `HKDF-SHA256(secret, salt = empty, info = "wake-unlock-v1", 32)`; AES-256-GCM, 12-byte IV, 128-bit tag, AAD = the request's `X-Wake-Nonce` value as UTF-8 bytes; plaintext = password UTF-8.
- `/unlock` results, exact wire strings: `unlocked`, `wrong_password`, `not_locked`, `no_module`, `timeout`, `failed`. Agent waits at most 15 s for the DLL's reply.
- `/status` field `lockScreen: Boolean?` (`true` pipe present, `false` installed but no pipe, `null` not installed).
- Accepted pipe owner SIDs: `S-1-5-18`, `S-1-5-32-544`. Anything else: refuse to write.
- DLL: `CPUS_UNLOCK_WORKSTATION` only; never an `ICredentialProviderFilter`; no exceptions escape any COM method; no file, registry or network access; password `SecureZeroMemory`'d after `ReportResult`.
- DLL CLSID (fixed): `{8C5D3B0E-6A41-4C7E-9F2B-5D1A7E3C9B04}`. Install path `C:\Program Files\Wake\WakeCredentialProvider.dll`, never under `%LOCALAPPDATA%`.
- Phone key alias `wake-unlock-<deviceId>`; `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG or AUTH_DEVICE_CREDENTIAL)`; `setInvalidatedByBiometricEnrollment(true)`.
- Password bytes are `ByteArray`/`CharArray`, never `String`, in agent and app; zeroed after use; never logged.
- `wrong_password` → phone deletes the saved password; no automatic retry anywhere.
- Never install the DLL, run the elevated setup or lock/unlock this PC; stop at CHECKPOINT steps and wait for John. First DLL run is in a Hyper-V VM.
- Public repo: only generic placeholders (`MY-PC`, `0A:1B:2C:3D:4E:5F`, `192.168.1.x`). No real passwords anywhere, including test fixtures beyond obvious dummies.
- Commit messages: Conventional Commits, no AI attribution of any kind.
- This folder blocks file creation by some tools (Controlled Folder Access). If Write/Edit fail with ENOENT on a new file, create it with `touch` in Bash first, or write in the scratchpad and `cp`.

## Review Focus

1. **Microsoft account on the PC** → unlock must work with the `MicrosoftAccount\…` qualified name, not just local accounts. Pinned by Task 3 checklist item 4 (VM with an MSA) and Task 16 item 2.
2. **Password with non-ASCII characters** (`ñ`, `é`, emoji) → reaches Windows intact. Pinned by Task 5 test `encodesNonAsciiPasswordAsUtf16le` and Task 1 native test `parses_non_ascii_utf16`.
3. **Fingerprints changed on the phone after setup** → the app says to re-enter the password; no crash, nothing sent. Pinned by Task 12 test `invalidatedKeyAsksForPasswordAgain`.
4. **Agent unreachable or PC unlocked by hand while the request is in flight** → one clear message, no retry. Pinned by Task 12 tests `unlockWhenAgentUnreachableShowsMessageAndDoesNotRetry` and `notLockedResultRefreshesQuietly`.
5. **Double tap on Unlock** → one biometric prompt, one request. Pinned by Task 12 test `secondTapWhileUnlockingIsIgnored`.

---

## Phase A: Spike the sign-in module (answers the risky questions first)

### Task 1: Credential Provider DLL with pipe server and auto-submit

**Files:**
- Create: `credential-provider/CMakeLists.txt`, `credential-provider/build.cmd`
- Create: `credential-provider/src/Dll.cpp`, `src/ClassFactory.{h,cpp}`, `src/Provider.{h,cpp}`, `src/Credential.{h,cpp}`, `src/PipeServer.{h,cpp}`, `src/PipeProtocol.{h,cpp}`, `src/Guids.h`, `src/WakeCredentialProvider.def`, `src/resources.rc` (tile bitmap)
- Create: `credential-provider/tests/pipe_protocol_test.cpp`
- Create: `credential-provider/NOTICE.md` (MIT notice for any code taken from Microsoft `Windows-classic-samples/Samples/CredentialProvider`)
- Modify: `.gitignore` (add `credential-provider/build/`, `*.apk`)

**Interfaces:**
- Produces (C++, `PipeProtocol.h`, no Windows dependencies so it unit-tests):
  - `std::optional<std::wstring> ParseRequest(const uint8_t* data, size_t size)`: nullopt on version ≠ 1, length > size − 8, odd length, or length > 1024.
  - `std::array<uint8_t, 8> FormatReply(uint32_t status, uint32_t substatus)`.
- Produces (DLL exports): `DllGetClassObject`, `DllCanUnloadNow` for CLSID `{8C5D3B0E-6A41-4C7E-9F2B-5D1A7E3C9B04}`.
- Produces (`build.cmd`): finds the Build Tools with `vswhere.exe`, runs CMake configure (`-G "Visual Studio 17 2022" -A x64`), build (`--config Release`) and `ctest -C Release`. Output: `credential-provider\build\Release\WakeCredentialProvider.dll`.

- [ ] **Step 1: Write the failing native tests** in `tests/pipe_protocol_test.cpp` (plain `main` with asserts, registered with `add_test`):
  - `parses_ascii`: frame `01 00 00 00 | 06 00 00 00 | 'a' 00 'b' 00 'c' 00` → `L"abc"`.
  - `parses_non_ascii_utf16`: frame carrying UTF-16LE of `L"ñé😀"` (6 bytes + 4-byte surrogate pair = 8 bytes) → same string.
  - `rejects_wrong_version`: version 2 → nullopt.
  - `rejects_length_past_end`: length 10, 6 bytes present → nullopt.
  - `rejects_odd_length` and `rejects_over_1024`.
  - `formats_reply`: `FormatReply(0xC000006D, 0xC000006A)` → `6D 00 00 C0 6A 00 00 C0`.

- [ ] **Step 2: Run `credential-provider\build.cmd`**. Expected: tests fail to link (no `PipeProtocol.cpp`).

- [ ] **Step 3: Implement `PipeProtocol.cpp`**, then the COM pieces:
  - `Provider`: `ICredentialProvider` + `ICredentialProviderSetUserArray`. `SetUsageScenario` returns `E_NOTIMPL` for anything but `CPUS_UNLOCK_WORKSTATION`. `SetUserArray` keeps the first user's `PKEY_Identity_QualifiedUserName` and SID. `Advise` stores `ICredentialProviderEvents*` + context and starts `PipeServer`; `UnAdvise` stops it and joins its thread. `GetCredentialCount`: count 1, default 0, `pbAutoLogonWithDefault = TRUE` only while a password is pending.
  - `PipeServer`: one thread; `CreateNamedPipeW(L"\\\\.\\pipe\\wake-unlock", PIPE_ACCESS_DUPLEX | FILE_FLAG_OVERLAPPED, PIPE_TYPE_MESSAGE | PIPE_READMODE_MESSAGE | PIPE_REJECT_REMOTE_CLIENTS, 1, 64, 4096, 0, &sa)`; security descriptor from SDDL `O:SYD:(A;;GA;;;SY)(A;;GRGW;;;<lockedUserSid>)`. On a message: `ParseRequest`; on success hand the password to `Credential`, call `CredentialsChanged(context)`, then wait (event, 20 s cap) for `ReportResult` and write `FormatReply`. Stop event cancels the wait for `UnAdvise`.
  - `Credential`: `ICredentialProviderCredential2`. Fields: tile image, label "Wake", text "Waiting for your phone". `GetSerialization`: `KERB_INTERACTIVE_UNLOCK_LOGON` with `MessageType = KerbWorkstationUnlockLogon`, domain/user split from the qualified name at the first `\` (pass `MicrosoftAccount` as the domain for MSA), packed with `KerbInteractiveUnlockLogonPack` from the sample's helpers; auth package from `LsaLookupAuthenticationPackage("Negotiate")`. Returns `CPGSR_RETURN_CREDENTIAL_FINISHED`. `ReportResult(ntsStatus, ntsSubstatus, …)` signals `PipeServer` with both codes, then `SecureZeroMemory` the password.
  - Every exported/COM method body is inside `try { … } catch (...) { return E_UNEXPECTED; }`.

- [ ] **Step 4: Run `credential-provider\build.cmd`**. Expected: build succeeds, `ctest` reports 7/7 passed, `WakeCredentialProvider.dll` exists. Also `dumpbin /exports` shows `DllGetClassObject` and `DllCanUnloadNow`.

- [ ] **Step 5: Commit**

```bash
git add .gitignore credential-provider
git commit -m "feat(unlock): credential provider that accepts a password over a local pipe"
```

### Task 2: Spike tools (install, uninstall, send a password)

**Files:**
- Create: `credential-provider/tools/install-dev.ps1`, `credential-provider/tools/uninstall-dev.ps1`, `credential-provider/tools/send-password.ps1`
- Create: `docs/RUNBOOK.md` section "Fingerprint unlock: recovery"

**Interfaces:**
- `install-dev.ps1 -Dll <path>`: copies to `C:\Program Files\Wake\`, writes `HKCR\CLSID\{8C5D3B0E-6A41-4C7E-9F2B-5D1A7E3C9B04}` (default value `Wake unlock`) with `InprocServer32` (default = DLL path, `ThreadingModel = Apartment`) and `HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\Credential Providers\{…}` (default `Wake unlock`). Requires admin.
- `uninstall-dev.ps1`: removes both keys; deletes the DLL, or schedules delete-on-reboot via `MoveFileEx(..., MOVEFILE_DELAY_UNTIL_REBOOT)` if in use.
- `send-password.ps1 -DelaySeconds 20 -Log <path>`: reads the password with `Read-Host -AsSecureString`, sleeps, connects `NamedPipeClientStream('.', 'wake-unlock', InOut)` in message mode, writes the frame, reads 8 bytes, writes `status=0x… substatus=0x… owner=<SID>` to the log. Owner via `GetAccessControl().GetOwner([SecurityIdentifier])`.

- [ ] **Step 1: Write the three scripts and the runbook recovery section** (normal tiles stay; WinRE → Command Prompt → `reg load HKLM\OFF C:\Windows\System32\config\SOFTWARE` → delete `HKLM\OFF\Microsoft\Windows\CurrentVersion\Authentication\Credential Providers\{8C5D3B0E-…}` → `reg unload HKLM\OFF`).
- [ ] **Step 2: Parse-check each script:** `Get-ChildItem credential-provider\tools\*.ps1 | % { $e = $null; [void][System.Management.Automation.Language.Parser]::ParseFile($_.FullName, [ref]$null, [ref]$e); "$($_.Name): $($e.Count) errors" }`. Expected: `0 errors` for all three.
- [ ] **Step 3: Commit** `feat(unlock): dev install, uninstall and pipe test scripts`.

### Task 3: CHECKPOINT (John, in a Hyper-V VM)

Not run by the builder. Hand John this checklist and wait for the results:

1. Create a Windows 11 VM in Hyper-V with a **local** account that has a password. Copy in the DLL and `credential-provider\tools\`.
2. Elevated: `install-dev.ps1 -Dll .\WakeCredentialProvider.dll`. Lock (Win+L). A "Wake" tile appears among the sign-in options; password/PIN tiles are still there. Unlock normally.
3. Run `send-password.ps1 -DelaySeconds 20 -Log C:\Users\Public\wake.txt`, type the password, press Win+L within 20 s, **leave the lock screen curtain down**. Expected: the VM unlocks by itself; log says `status=0x0`, `owner=` `S-1-5-18` or `S-1-5-32-544`.
4. Repeat 3 on a VM account signed in with a **Microsoft account**. Expected: unlocks.
5. Repeat 3 with a wrong password. Expected: lock screen shows the normal wrong-password message; log `status=0xC000006D`. Lock screen still usable.
6. Turn on Settings → Accounts → Sign-in options → "For improved security, only allow Windows Hello sign-in for Microsoft accounts". Lock. Record: does the Wake tile still appear, does step 3 still work?
7. Sleep the VM (if supported), wake it, run step 3 again. Record whether it unlocks without touching the keyboard first.
8. `uninstall-dev.ps1`, reboot, confirm the Wake tile is gone.

Record answers in the spec §12 "Spike first" paragraph. If 3 or 4 fails, stop the plan and revisit the design with John. If 7 needs a keypress first, Task 7's display wake is required, not optional.

---

## Phase B: Protocol

### Task 4: Unlock messages and password sealing

**Files:**
- Create: `protocol/src/main/kotlin/dev/jano/wake/protocol/Unlock.kt`
- Modify: `protocol/src/main/kotlin/dev/jano/wake/protocol/Messages.kt` (`StatusResponse` gains `val lockScreen: Boolean? = null`)
- Test: `protocol/src/test/kotlin/dev/jano/wake/protocol/UnlockTest.kt`

**Interfaces:**
- Produces:
  - `@Serializable enum class UnlockResult { @SerialName("unlocked") UNLOCKED, @SerialName("wrong_password") WRONG_PASSWORD, @SerialName("not_locked") NOT_LOCKED, @SerialName("no_module") NO_MODULE, @SerialName("timeout") TIMEOUT, @SerialName("failed") FAILED }`
  - `@Serializable data class UnlockRequest(val iv: String, val password: String)`
  - `@Serializable data class UnlockResponse(val result: UnlockResult, val ntstatus: Long? = null)`
  - `fun sealPassword(secret: ByteArray, nonce: String, password: ByteArray): UnlockRequest`
  - `fun openPassword(secret: ByteArray, nonce: String, request: UnlockRequest): ByteArray?` (null on bad base64, wrong IV length or tag failure)

- [ ] **Step 1: Write failing tests**
  - `sealThenOpenRoundTrips`: `openPassword(secret, "n1", sealPassword(secret, "n1", "pässwörd".toByteArray()))` contentEquals input.
  - `openWithOtherNonceFails`: sealed with `"n1"`, opened with `"n2"` → null.
  - `openWithOtherSecretFails` → null.
  - `tamperedCiphertextFails`: flip one byte of the decoded `password` field → null.
  - `ivIsFreshEachSeal`: two seals of the same input have different `iv`.
  - `unlockKeyIsNotTheSecret`: ciphertext doesn't decrypt with `AES-GCM` keyed by `secret` directly.
  - `statusWithoutLockScreenDecodesAsNull`: decode old-agent JSON without the field → `lockScreen == null`.
  - `resultWireNames`: `WakeJson.encodeToString(UnlockResponse(UnlockResult.WRONG_PASSWORD, 3221225581))` contains `"wrong_password"`.
- [ ] **Step 2: Run** `./gradlew :protocol:test --tests '*UnlockTest*'`. Expected: FAIL, unresolved references.
- [ ] **Step 3: Implement `Unlock.kt`** with `hkdfSha256(secret, ByteArray(0), "wake-unlock-v1".toByteArray(), 32)`, `AES/GCM/NoPadding`, `GCMParameterSpec(128, iv)`, `updateAAD(nonce.toByteArray())`, `b64url` for both fields; IV from `SecureRandom`.
- [ ] **Step 4: Run** `./gradlew :protocol:test`. Expected: all pass.
- [ ] **Step 5: Commit** `feat(protocol): encrypted unlock request and lockScreen status`.

---

## Phase C: Agent

### Task 5: Pipe frames and result mapping

**Files:**
- Create: `agent/src/main/kotlin/dev/jano/wake/agent/unlock/PipeFrames.kt`
- Test: `agent/src/test/kotlin/dev/jano/wake/agent/unlock/PipeFramesTest.kt`

**Interfaces:**
- Produces:
  - `fun encodeRequest(passwordUtf8: ByteArray): ByteArray` (decodes UTF-8 into an array-backed `CharBuffer`, encodes UTF-16LE, zeroes the intermediate buffers; never builds a `String`)
  - `data class PipeReply(val status: Long, val substatus: Long)`; `fun decodeReply(bytes: ByteArray): PipeReply?` (null unless exactly 8 bytes)
  - `fun toResponse(reply: PipeReply): UnlockResponse`: `0` → `UNLOCKED`; status `0xC000006D` (STATUS_LOGON_FAILURE) or substatus `0xC000006A` (STATUS_WRONG_PASSWORD) → `WRONG_PASSWORD` with `ntstatus = status`; anything else → `FAILED` with `ntstatus = status`.

- [ ] **Step 1: Write failing tests**: `encodesAsciiFrame` (`"abc"` → `01000000 06000000 610062006300`), `encodesNonAsciiPasswordAsUtf16le` (`"ñé😀"` → length 8, bytes `F1 00 E9 00 3D D8 00 DE`), `decodesReply`, `rejectsShortReply`, `mapsSuccess`, `mapsLogonFailureToWrongPassword`, `mapsOtherStatusToFailed` (`0xC0000234` account locked → `FAILED`, ntstatus kept).
- [ ] **Step 2: Run** `./gradlew :agent:test --tests '*PipeFramesTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement `PipeFrames.kt`.**
- [ ] **Step 4: Run** the same. Expected: PASS.
- [ ] **Step 5: Commit** `feat(agent): unlock pipe frames`.

### Task 6: Unlock relay with pipe owner check

**Files:**
- Create: `agent/src/main/kotlin/dev/jano/wake/agent/unlock/UnlockRelay.kt`
- Test: `agent/src/test/kotlin/dev/jano/wake/agent/unlock/UnlockRelayTest.kt`

**Interfaces:**
- Consumes: Task 5.
- Produces:
  - `interface UnlockRelay { fun lockScreen(): Boolean?; fun relay(passwordUtf8: ByteArray): UnlockResponse }`
  - `interface PipeChannel : AutoCloseable { val ownerSid: String?; fun write(bytes: ByteArray); fun read(timeoutMs: Long): ByteArray? }` (`read` returns null on timeout)
  - `interface PipeHost { fun installed(): Boolean; fun pipeExists(): Boolean; fun connect(): PipeChannel?; fun wakeDisplay() }`
  - `class PipeUnlockRelay(private val host: PipeHost, private val log: (String) -> Unit, private val replyTimeoutMs: Long = 15_000) : UnlockRelay`

Behaviour of `relay`: not installed → `NO_MODULE`; `connect()` null → `NOT_LOCKED`; owner not in `{"S-1-5-18", "S-1-5-32-544"}` → `FAILED`, logs `refused pipe owned by <sid>`, writes nothing; otherwise `wakeDisplay()`, write `encodeRequest`, `read(replyTimeoutMs)` → null = `TIMEOUT`, else `toResponse(decodeReply(…) ?: return FAILED)`. Always closes the channel and zeroes the input array and the encoded frame (in `finally`). `lockScreen()`: `null` if not installed, else `pipeExists()`.

- [ ] **Step 1: Write failing tests** with a fake `PipeHost`/`PipeChannel` recording writes: `notInstalledIsNoModule`, `noPipeIsNotLocked`, `rejectsPipeOwnedByUser` (owner `S-1-5-21-1-2-3-1001` → `FAILED`, zero writes), `acceptsSystemOwner`, `acceptsAdministratorsOwner`, `timeoutWhenNoReply`, `wrongPasswordPassesThrough`, `zeroesPasswordAfterRelay` (input array all zero afterwards), `closesChannelOnEveryPath`, `lockScreenNullWhenNotInstalled`, `lockScreenTracksPipe`.
- [ ] **Step 2: Run** `./gradlew :agent:test --tests '*UnlockRelayTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement `PipeUnlockRelay`.**
- [ ] **Step 4: Run** the same. Expected: PASS.
- [ ] **Step 5: Commit** `feat(agent): unlock relay that only talks to a SYSTEM-owned pipe`.

### Task 7: Windows pipe host (JNA)

**Files:**
- Create: `agent/src/main/kotlin/dev/jano/wake/agent/unlock/WindowsPipeHost.kt`

**Interfaces:**
- Produces: `class WindowsPipeHost : PipeHost`.
  - `installed()`: `Advapi32Util.registryKeyExists(HKEY_LOCAL_MACHINE, "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Authentication\\Credential Providers\\{8C5D3B0E-6A41-4C7E-9F2B-5D1A7E3C9B04}")`.
  - `pipeExists()`: `Kernel32.WaitNamedPipe("\\\\.\\pipe\\wake-unlock", 1)` → true, or false with last error `ERROR_SEM_TIMEOUT` (121) → true (exists, busy); otherwise false. Never opens the pipe.
  - `connect()`: `CreateFile(name, GENERIC_READ or GENERIC_WRITE or READ_CONTROL, 0, null, OPEN_EXISTING, FILE_FLAG_OVERLAPPED, null)`; invalid handle → null. `SetNamedPipeHandleState` to `PIPE_READMODE_MESSAGE`. `ownerSid` via `Advapi32.GetSecurityInfo(handle, SE_KERNEL_OBJECT, OWNER_SECURITY_INFORMATION, …)` + `Advapi32Util.convertSidToStringSid`. `read(timeoutMs)`: overlapped `ReadFile` + `WaitForSingleObject(event, timeoutMs)`; on timeout `CancelIoEx` and return null.
  - `wakeDisplay()`: `SetThreadExecutionState(ES_DISPLAY_REQUIRED)` (if Task 3 item 7 showed a keypress is needed, also send a harmless input such as a zero-distance `SendInput` mouse move, and note it).

- [ ] **Step 1: Implement.** No unit test: this is a thin JNA shell around Task 6's tested logic.
- [ ] **Step 2: Run** `./gradlew :agent:test :agent:agentJar`. Expected: build passes, existing tests pass.
- [ ] **Step 3: Commit** `feat(agent): Windows named pipe host for unlock`.

### Task 8: `/unlock` endpoint and `lockScreen` in status

**Files:**
- Modify: `agent/src/main/kotlin/dev/jano/wake/agent/AgentServer.kt` (constructor param, route, `status()`)
- Modify: `agent/src/main/kotlin/dev/jano/wake/agent/Main.kt` (`Listeners` passes one shared `PipeUnlockRelay(WindowsPipeHost(), ::log)`)
- Test: `agent/src/test/kotlin/dev/jano/wake/agent/AgentServerTest.kt`

**Interfaces:**
- Consumes: Task 4 (`openPassword`, `UnlockRequest`, `UnlockResponse`), Task 6 (`UnlockRelay`).
- Produces: `AgentServer(..., unlock: UnlockRelay)`; route `"POST" to "/unlock"` through `signed(...)`. Handler reads `H_NONCE` from the request headers (signature already verified), `openPassword(store.load()!!, nonce, request)`; null → 400 `bad_request`; else `unlock.relay(password)` → 200 `UnlockResponse`. `status()` sets `lockScreen = unlock.lockScreen()`.

- [ ] **Step 1: Write failing tests** (fake `UnlockRelay` recording the password it receives): `unlockRelaysDecryptedPassword`, `unlockRequiresSignature` (unsigned → 401, relay never called), `unlockReplayRejected` (same nonce twice → 401 `replay` on second, relay called once), `unlockWithCiphertextForOtherNonceIs400`, `statusReportsLockScreen` (fake returns `true` → JSON `"lockScreen":true`). Update the existing `server` fixture to pass a fake relay.
- [ ] **Step 2: Run** `./gradlew :agent:test --tests '*AgentServerTest*'`. Expected: new tests FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew :agent:test`. Expected: all PASS.
- [ ] **Step 5: Commit** `feat(agent): /unlock endpoint and lock screen status`.

### Task 9: Installer, release zip, docs

**Files:**
- Modify: `pc-setup/setup.ps1` (item 6, uninstall, params `-Unlock`, `-NoUnlock`, `-UnlockDll`)
- Modify: `Install Wake Agent.cmd` (pass `-UnlockDll "unlock\WakeCredentialProvider.dll"` for the release layout, `credential-provider\build\Release\WakeCredentialProvider.dll` for a source checkout when present)
- Modify: `agent/build.gradle.kts` (`agentZip` adds `into("unlock") { from(rootProject.file("credential-provider/build/Release/WakeCredentialProvider.dll")) }`; fails with "Build the unlock module first: credential-provider\build.cmd" if the file is missing)
- Modify: `README.md` (short "Fingerprint unlock" section: what it does, opt-in, needs the Windows password not the PIN), `pc-setup/README.md`, `docs/RUNBOOK.md`

**Interfaces:**
- Item 6 reuses the registry layout and delete-on-reboot logic from Task 2's dev scripts (move the shared functions into `setup.ps1`; dev scripts may call it with `-Unlock`).
- Prompt text exactly: `Turn on fingerprint unlock? (y/N)`. Default No. `-Check` adds rows `Fingerprint unlock module` (`installed`/`not installed`) and `Windows Hello-only sign-in` (`on`/`off`, from `HKLM\SOFTWARE\Microsoft\Windows NT\CurrentVersion\PasswordLess\Device` `DevicePasswordLessBuildVersion`: `2` = on), the latter with note per Task 3 item 6.
- `-Uninstall` removes the module too.

- [ ] **Step 1: Implement.**
- [ ] **Step 2: Run** `powershell -NoProfile -ExecutionPolicy Bypass -File pc-setup\setup.ps1 -Check` (read-only, no admin). Expected: table includes the two new rows; nothing changed.
- [ ] **Step 3: Run** `credential-provider\build.cmd` then `./gradlew :agent:agentZip`. Expected: `build/release/WakeAgent-Windows.zip` contains `WakeAgent-Windows/unlock/WakeCredentialProvider.dll`.
- [ ] **Step 4: Commit** `feat(setup): opt-in fingerprint unlock module`.

---

## Phase D: Phone

### Task 10: Fingerprint-gated password store

**Files:**
- Modify: `gradle/libs.versions.toml` (`biometric = "1.1.0"`, library `androidx-biometric = { module = "androidx.biometric:biometric", version.ref = "biometric" }`), `app/build.gradle.kts`
- Create: `app/src/main/kotlin/dev/jano/wake/data/UnlockPasswords.kt`

**Interfaces:**
- Produces:
  - `sealed interface OpenResult { data class Ready(val cipher: Cipher) : OpenResult; data object NotSaved : OpenResult; data object Invalidated : OpenResult }`
  - `interface UnlockPasswords { fun saved(deviceId: String): Boolean; fun sealCipher(deviceId: String): Cipher; fun save(deviceId: String, cipher: Cipher, password: ByteArray); fun openCipher(deviceId: String): OpenResult; fun open(cipher: Cipher, deviceId: String): ByteArray; fun remove(deviceId: String) }`
  - `class KeystoreUnlockPasswords(context: Context) : UnlockPasswords`: per-device key, alias and parameters from Global Constraints; `sealCipher` replaces any old key and returns an ENCRYPT cipher; `save` stores `iv + ciphertext` in prefs `wake-unlock` (Base64, key = deviceId); `openCipher` returns `Invalidated` (and calls `remove`) on `KeyPermanentlyInvalidatedException`; `remove` deletes prefs entry and Keystore alias.
  - `fun interface Authenticator { suspend fun authenticate(cipher: Cipher): Cipher? }` (null = cancelled or failed), in the same file.

- [ ] **Step 1: Implement.** Keystore code can't run in JVM unit tests; Task 12's tests use a fake `UnlockPasswords` backed by a software AES key.
- [ ] **Step 2: Run** `./gradlew :app:assembleDebug`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 3: Commit** `feat(app): fingerprint-gated storage for the PC password`.

### Task 11: Client `unlock` call

**Files:**
- Modify: `app/src/main/kotlin/dev/jano/wake/net/AgentClient.kt`
- Modify: `app/src/main/kotlin/dev/jano/wake/main/AppGraph.kt` (`AgentApi.unlock`, `AppGraph.unlockPasswords`)
- Test: `app/src/test/kotlin/dev/jano/wake/net/AgentClientTest.kt`

**Interfaces:**
- Consumes: Task 4 `sealPassword`.
- Produces: `suspend fun AgentClient.unlock(password: ByteArray): UnlockResponse`; `AgentApi.unlock(password: ByteArray): UnlockResponse`; `AppGraph(..., val unlockPasswords: UnlockPasswords)` (real: `KeystoreUnlockPasswords(context)`).
- Change: `execute` takes the body as `((nonce: String) -> String)?`; it creates the nonce with `newNonce()`, builds the body, and signs with that nonce. Existing callers pass `{ _ -> json }`. A clock-corrected retry therefore re-seals with a fresh nonce.

- [ ] **Step 1: Write failing tests** (mockwebserver): `unlockSendsCiphertextBoundToNonce` (server side: `openPassword(secret, request.headers[H_NONCE], body)` equals the password), `unlockRetryAfterStaleUsesFreshNonceAndCiphertext` (first reply stale with valid proof; second request has a different nonce and iv, and still opens), `unlockParsesResult`.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*AgentClientTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement.** Update test fakes of `AgentApi`/`AppGraph` in `MainViewModelTest` etc. so the suite compiles.
- [ ] **Step 4: Run** `./gradlew :app:testDebugUnitTest`. Expected: all PASS.
- [ ] **Step 5: Commit** `feat(app): signed, encrypted unlock call`.

### Task 12: Unlock in the main view model

**Files:**
- Modify: `app/src/main/kotlin/dev/jano/wake/main/MainViewModel.kt`, `MainUiState.kt`
- Test: `app/src/test/kotlin/dev/jano/wake/main/MainViewModelTest.kt`

**Interfaces:**
- Consumes: Tasks 10, 11.
- Produces:
  - `MainUiState` gains `val lockScreen: Boolean` (agent says `lockScreen == true` and PC is `On`), `val unlockSaved: Boolean`, `val unlocking: Boolean`. (`combine` already has five flows; use the vararg overload or fold into a small `MutableStateFlow` of unlock state — implementer's call.)
  - `fun unlock(auth: Authenticator)`; `UiMessage.OpenUnlockSetup(deviceId: String)` emitted when Unlock is tapped with nothing saved or after `WRONG_PASSWORD`/`Invalidated`.
- Flow: ignore if `unlocking`; `openCipher` → `NotSaved`/`Invalidated` → message; `Ready` → `auth.authenticate(cipher)` → null = silently stop; else `open` → `api.unlock(password)` → zero password in `finally` → map result. Exactly one call, no retry.
- Copy (exact; `{name}` = device name):
  - `UNLOCKED`: `{name} unlocked`
  - `WRONG_PASSWORD`: `{name} didn't accept the saved password. Enter it again.` + `remove(deviceId)` + `OpenUnlockSetup`
  - `NOT_LOCKED`: no message; refresh status
  - `NO_MODULE`: `Fingerprint unlock isn't installed on {name}. Run Install Wake Agent again on the PC.`
  - `TIMEOUT`: `{name} didn't answer in time.`
  - `FAILED`: `Couldn't unlock {name}.`
  - `Invalidated`: `Fingerprints on this phone changed. Enter the PC password again.` + `OpenUnlockSetup`
  - Network failure: existing `unreachable()` copy.

- [ ] **Step 1: Write failing tests** (fake `UnlockPasswords` with a software AES-GCM key; fake `Authenticator` returning the cipher or null): `unlockSendsSavedPassword`, `cancelledFingerprintSendsNothing`, `wrongPasswordClearsSavedPasswordAndOpensSetup`, `invalidatedKeyAsksForPasswordAgain`, `unlockWhenAgentUnreachableShowsMessageAndDoesNotRetry` (api throws once; exactly 1 call), `notLockedResultRefreshesQuietly` (no message), `secondTapWhileUnlockingIsIgnored` (suspend the fake api; second `unlock` → 1 authenticate, 1 api call), `passwordZeroedAfterSend`, `lockScreenOnlyWhenAgentSaysTrue`.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*MainViewModelTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew :app:testDebugUnitTest`. Expected: all PASS.
- [ ] **Step 5: Commit** `feat(app): unlock action with fingerprint and truthful results`.

### Task 13: CHECKPOINT: mockups

- [ ] **Step 1:** Add two screens to `docs/design/prototype.html` (or a sibling `docs/design/unlock.html` using the same tokens): main screen with PC on + lock screen up (pill **Locked**, Lock button replaced by **Unlock** with fingerprint icon, Unlocking spinner state), and Settings → device → **Fingerprint unlock** (explanation, password field with show/hide, "Save with fingerprint", saved state with "Remove"). Draw at 412 × 891 dp.
- [ ] **Step 2:** Screenshot both at true scale and check them yourself (spacing, contrast, truncation) before showing.
- [ ] **Step 3:** Show John; wait for approval or changes.

### Task 14: Unlock button and biometric prompt

**Files:**
- Create: `app/src/main/kotlin/dev/jano/wake/ui/main/BiometricAuthenticator.kt`
- Modify: `app/src/main/kotlin/dev/jano/wake/MainActivity.kt` (extend `FragmentActivity`), `ui/main/MainScreen.kt` (Lock ↔ Unlock), `ui/main/Presentation.kt` / `Pill.kt` (Locked pill), `ui/icons/WakeIcons.kt` (Fingerprint), `ui/main/DemoStates.kt` (`locked`, `unlocking` demo states), `ui/Nav.kt` (`MainActions.unlock`, `OpenUnlockSetup` navigation)

**Interfaces:**
- Produces: `class BiometricAuthenticator(private val activity: FragmentActivity) : Authenticator` using `BiometricPrompt` with `setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)`, title `Unlock {name}`, `CryptoObject(cipher)`; resumes with `result.cryptoObject?.cipher`, or null on error/cancel.
- Unlock is a `TapButton` (not hold), shown in the Lock slot when `state.lockScreen`; spinner and disabled while `unlocking`.

- [ ] **Step 1: Implement**, matching the approved mockup.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest :app:assembleDebug`. Expected: PASS, BUILD SUCCESSFUL.
- [ ] **Step 3:** Install and screenshot the demo states: `adb install -r app/build/outputs/apk/debug/app-debug.apk`, `adb shell am start -n dev.jano.wake/.MainActivity --es demoState locked`, `adb exec-out screencap -p > %TEMP%\locked.png`. Compare with the mockup.
- [ ] **Step 4: Commit** `feat(app): Unlock button with fingerprint prompt`.

### Task 15: Fingerprint unlock setup screen

**Files:**
- Create: `app/src/main/kotlin/dev/jano/wake/ui/settings/UnlockSetupScreen.kt`
- Modify: `ui/settings/SettingsViewModel.kt`, `ui/settings/SettingsScreens.kt` (row in `DeviceExtras`), `ui/Nav.kt` (route `settings/device/{id}/unlock`)
- Test: `app/src/test/kotlin/dev/jano/wake/ui/settings/SettingsViewModelTest.kt` (create)

**Interfaces:**
- Produces: `SettingsViewModel.saveUnlockPassword(deviceId: String, password: CharArray, auth: Authenticator)`, `removeUnlockPassword(deviceId: String)`; `unpair` and `deleteDevice` also call `unlockPasswords.remove`.
- Screen copy: title `Fingerprint unlock`; body `Unlock {name}'s lock screen with your fingerprint. Wake keeps the Windows password on this phone, locked by your fingerprint. It never stays on the PC.`; field label `Windows password`; hint `Your Microsoft account or local account password. A PIN won't work.`; button `Save with fingerprint`; saved state `On` + `Remove`; after save the info line `Saved. Lock your PC and tap Unlock to try it.`; if `lockScreen == null` show `Install the unlock module on the PC first: run Install Wake Agent again and answer y.`
- Password field holds a `CharArray`-backed state cleared on leave; UTF-8 encode into a `ByteArray` for `save`, zero both.

- [ ] **Step 1: Write failing tests**: `savePasswordStoresAfterFingerprint`, `cancelledFingerprintSavesNothing`, `unpairAlsoRemovesUnlockPassword`, `deleteDeviceAlsoRemovesUnlockPassword`.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*SettingsViewModelTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew :app:testDebugUnitTest :app:assembleDebug`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(app): fingerprint unlock setup screen`.

---

## Phase E: Real PC

### Task 16: CHECKPOINT: end-to-end on MY-PC (John)

Builder prepares: `credential-provider\build.cmd`, `./gradlew :agent:agentZip :app:assembleRelease`, installs the APK over ADB. Then hands John this list and waits:

1. Unzip `build\release\WakeAgent-Windows.zip`, run `Install Wake Agent.cmd`, answer `y`. `setup.ps1 -Check` shows the module installed.
2. Phone: Settings → MY-PC → Fingerprint unlock → enter the password → fingerprint. (MY-PC uses a Microsoft account if that's what John signs in with; this is the MSA test on real hardware.)
3. Phone: Lock. Within ~4 s the pill says Locked and the button says Unlock. Tap Unlock → fingerprint → PC unlocks; phone says `MY-PC unlocked`.
4. Sleep from the phone, wake from the phone, Unlock. PC unlocks without touching it.
5. Unlock with the phone's PIN instead of the fingerprint. Works.
6. On the phone, add a new fingerprint in Android settings, tap Unlock. App says fingerprints changed and opens setup; nothing sent.
7. Change nothing on the PC but save a wrong password on the phone; tap Unlock once. PC shows one failed attempt; phone clears the password and opens setup; no second attempt.
8. Lock the PC with Win+L by hand; the phone shows Locked within a poll or two.
9. `Uninstall Wake Agent.cmd`; reboot; Wake tile gone; normal sign-in works.

Fix anything that fails through a new task with its own test, then repeat the affected items.
