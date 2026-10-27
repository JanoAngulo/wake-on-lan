# Wake: unlock the PC with the phone's fingerprint

Date: 2026-10-27
Builds on: [`2026-10-26-wake-on-lan-design.md`](2026-10-26-wake-on-lan-design.md)

## 1. Intent

Wake already turns the PC on, puts it to sleep and locks it from bed. The last trip down the ladder is typing the Windows password at the lock screen. This feature removes it: when the PC shows the lock screen, the user taps **Unlock** in Wake, touches the phone's fingerprint sensor, and the PC unlocks.

It follows the structure of Remote Fingerprint Unlock (an Android app plus a Windows sign-in module installed on the PC) but is our own implementation inside Wake. Only that app's public behaviour is used as a reference. Its code is closed source and is not decompiled, copied or talked to.

## 2. Goals, non-goals, anti-goals

**Goals (version 1)**

1. Unlock a **locked** session (after Lock, or after waking from Sleep) from the phone, gated by the phone's fingerprint.
2. One app, one pairing: reuses the existing pairing secret; no second pairing flow.
3. The phone shows the truth: Unlock only appears when the PC really is showing the lock screen, and the result (unlocked, wrong password, timed out) comes from Windows, not a guess.
4. The Windows password is entered once on the phone and is unusable without the fingerprint (or the phone's PIN as fallback).

**Non-goals (version 1)**

- Signing in after a cold boot or reboot (no user session exists yet, so the agent isn't running). Planned as version 2, §13.
- Unlocking from outside the home network (same reason as the base design: CGNAT, no relay).
- UAC prompts, Remote Desktop, other users on the PC, fast user switching.
- Face unlock as a requirement. It works only on phones whose face unlock is Android Class 3 (strong); §5.3.

**Anti-goals** (failure even if it technically works)

- Anyone on the home Wi-Fi, or any program running as the user on the PC, being able to unlock the PC or read the password.
- The password being stored on the PC's disk.
- A bug in the sign-in module locking the user out of their own PC.
- Repeated wrong-password attempts that trip Windows account lockout.
- The phone saying "Unlocked" when it isn't.

## 3. Reference: how Remote Fingerprint Unlock is structured

From its public behaviour: a phone app, a separately installed Windows module that adds a sign-in option to the lock screen, a pairing step, and the user's Windows password stored once and released with the fingerprint. Wake keeps that split and maps it onto pieces we already have:

| Their piece | Ours |
|---|---|
| Android app | Wake app (existing), new Unlock action and setup screen |
| Windows module | `WakeCredentialProvider.dll` (new, C++) + the existing agent |
| Pairing | Existing X25519 pairing and HMAC-signed requests |
| Ads, Pro tier, cloud | None |

## 4. Architecture

```
Phone (Wake app)
  │  fingerprint → Android Keystore releases the password
  │  POST /unlock  (HMAC-signed, password encrypted with a key derived from the pairing secret)
  ▼
Agent (existing Kotlin tray app, user session, LAN only)
  │  verifies signature, decrypts
  │  connects to \\.\pipe\wake-unlock and checks the pipe is owned by SYSTEM
  ▼
WakeCredentialProvider.dll (new, C++, loaded by LogonUI.exe as SYSTEM)
  │  receives the password, auto-submits it for the locked user
  │  reports Windows' answer back over the pipe
  ▼
Windows unlocks (or says the password is wrong)
```

Key split: **the DLL does no networking and holds no state on disk.** It's the smallest possible C++ piece; everything that can be written in Kotlin and unit-tested stays in the agent and the app. The agent keeps running while the session is locked or after waking from sleep, so version 1 needs no Windows service.

## 5. Phone

### 5.1 Setup (once per PC)

Settings → the PC → **Fingerprint unlock**:

1. Explains what happens in one short paragraph and that the PC needs the unlock module (§9).
2. Password field for the Windows password of the account on that PC. If the account is a Microsoft account, it's that account's password. A Windows Hello PIN won't work and the screen says so.
3. **Save with fingerprint**: BiometricPrompt confirms, the password is encrypted with a Keystore key that needs the fingerprint for every use, and stored per device id.
4. **Test now** (optional): asks the user to lock the PC (the existing Lock button), then runs one unlock.

Remove: deletes the stored password and the Keystore key for that device. Unpairing the PC also removes it.

### 5.2 Unlock

- Main screen, PC on: when status says the lock screen is up (§6.2), the **Lock** button becomes **Unlock** with a fingerprint icon. Tap → BiometricPrompt → the app decrypts the password inside the Keystore-authorised cipher, sends `/unlock`, then zeroes its copy.
- Unlock is a tap, not a hold: it's not destructive and the fingerprint is already the deliberate step.
- States after sending: **Unlocking…** (spinner on the button) → **Unlocked** (status shows not locked) or one of the failures in §6.3.

### 5.3 Keystore and biometrics

- One AES-256-GCM key per device (alias `wake-unlock-<deviceId>`), `setUserAuthenticationRequired(true)`, `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG or AUTH_DEVICE_CREDENTIAL)`, `setInvalidatedByBiometricEnrollment(true)`. Timeout 0 means every unlock needs a fresh fingerprint; the key is bound to the `CryptoObject` passed to BiometricPrompt.
- Adding a new fingerprint on the phone invalidates the key; the app catches `KeyPermanentlyInvalidatedException` and asks for the password again.
- Fingerprint is the primary path. The phone PIN works as fallback, so a dirty sensor can't lock the user out of unlocking.
- Face: works automatically on phones whose face unlock is Class 3. Samsung's face recognition is Class 2 on most models, so expect fingerprint only on a Galaxy. *(Builder verifies on the S26 Ultra and words the setup screen accordingly.)*
- New dependency: `androidx.biometric`.

## 6. Protocol

### 6.1 `POST /unlock`

Signed exactly like `/command` (HMAC over method, path, body, timestamp, nonce; 30 s skew; nonce replay check).

```json
{ "iv": "<b64url 12 bytes>", "password": "<b64url AES-GCM ciphertext+tag>" }
```

- Encryption key: `HKDF-SHA256(secret, salt = empty, info = "wake-unlock-v1", 32)`. Never the HMAC key itself.
- AAD: the request's nonce header bytes, so a ciphertext can't be lifted into another request.
- Plaintext: the password as UTF-8.

Response `200`:

```json
{ "result": "unlocked" | "wrong_password" | "not_locked" | "no_module" | "timeout" | "failed", "ntstatus": 0 }
```

`ntstatus` is set for `wrong_password` and `failed` so the log can say exactly what Windows answered.

### 6.2 `/status` gains one field

```json
{ ..., "lockScreen": true | false | null }
```

- `true`: the unlock module's pipe exists, which means LogonUI has loaded our tile, so the lock screen really is up.
- `false`: module installed, pipe absent: session is unlocked.
- `null`: module not installed. The app hides Unlock and Settings shows how to install it.

The agent probes the pipe in a way that doesn't consume a connection (builder's call on the exact call), plus a registry check for "installed".

### 6.3 Failure handling on the phone

| Result | What the app does |
|---|---|
| `wrong_password` | Says the PC rejected the saved password, **clears it**, and opens setup. Never retries on its own (account lockout). |
| `not_locked` | Refreshes status; nothing to do. |
| `no_module` | Points to Settings → How to connect. |
| `timeout` | "The PC didn't answer in time." Status polling shows the truth a moment later. |
| `failed` | Generic error with the NTSTATUS in the log. |
| Biometric cancelled | Nothing sent. |

## 7. Agent changes

- New `UnlockRelay`: takes the decrypted password bytes, connects to `\\.\pipe\wake-unlock`, verifies the pipe, writes one request, waits up to 15 s for the answer, zeroes the buffer.
- **Pipe identity check (required):** before writing, read the pipe handle's owner SID (`GetSecurityInfo`, owner) and accept only `S-1-5-18` (SYSTEM) or `S-1-5-32-544` (Administrators, the default owner for SYSTEM-created objects). A normal-user program can't create a pipe with that owner, so a program squatting the name can't collect the password. The DLL also sets the owner explicitly. *(Builder verifies the actual owner SID LogonUI's pipe gets and tightens the accepted list if possible.)*
- Pipe access via JNA (`jna-platform` is already a dependency): `CreateFile`, `SetNamedPipeHandleState` (message mode), `WriteFile`, `ReadFile`, `GetSecurityInfo`.
- Password bytes are a `ByteArray`, never a `String`, and are zeroed after use. The agent never logs them.
- Wake the display before relaying (`SetThreadExecutionState(ES_DISPLAY_REQUIRED)` or equivalent) so LogonUI is actually rendering after sleep. *(Builder verifies whether it's needed.)*

### 7.1 Pipe protocol (agent ↔ DLL)

Message mode, one request, one reply, then the agent closes.

- Request: `u32 version = 1`, `u32 length`, `length` bytes of the password as UTF-16LE.
- Reply: `u32 status` (0 = logged on; otherwise the NTSTATUS from `ReportResult`), `u32 substatus`.

## 8. Credential Provider DLL

- C++ COM in-process server implementing `ICredentialProvider`, `ICredentialProviderSetUserArray` and one `ICredentialProviderCredential2`. Starts from Microsoft's `Windows-classic-samples` CredentialProvider sample (MIT licence; keep its notice).
- Supports `CPUS_UNLOCK_WORKSTATION` only. Returns `E_NOTIMPL` for every other usage scenario, so it never shows up in UAC prompts or at cold-boot sign-in.
- **Does not filter other providers.** The password and PIN tiles stay exactly as they are; ours is extra. If ours misbehaves the user signs in the normal way.
- On `SetUsageScenario`/`Advise`: start one background thread that creates `\\.\pipe\wake-unlock` with `PIPE_REJECT_REMOTE_CLIENTS`, message mode, one instance, and a security descriptor: owner SYSTEM, DACL SYSTEM full + the locked user's SID read/write. On `UnAdvise`: close the pipe and join the thread.
- On a request: store the password in the credential, set auto-submit, call `ICredentialProviderEvents::CredentialsChanged`. LogonUI re-enumerates; `GetCredentialCount` returns our tile as default with `pbAutoLogonWithDefault = TRUE`; `GetSerialization` packs a `KERB_INTERACTIVE_UNLOCK_LOGON` (`KerbWorkstationUnlockLogon`) for the locked user's qualified name from `ICredentialProviderUser` (handles `MicrosoftAccount\…` and local accounts). `ReportResult` writes the reply to the pipe and `SecureZeroMemory`s the password.
- Every COM entry point is wrapped so no C++ exception escapes into LogonUI.
- No files, no registry writes, no network, no logging of the password. Diagnostic logging goes to the Windows event log at most, and only status codes.
- Tile: label "Wake", small fingerprint image, text "Waiting for your phone". It's visible when selected, which is how the user knows the module is loaded.

## 9. Installer

`setup.ps1` gains a 6th item, **opt-in**: on install it asks "Turn on fingerprint unlock? (y/N)", and `-Unlock` / `-NoUnlock` skip the question.

- Copies the DLL to `C:\Program Files\Wake\WakeCredentialProvider.dll`. **Never under `%LOCALAPPDATA%`**: the DLL runs as SYSTEM, so it must sit where only administrators can write, or any user program could replace it and gain SYSTEM.
- Registers `HKCR\CLSID\{clsid}\InprocServer32` (`ThreadingModel = Apartment`) and `HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\Credential Providers\{clsid}`.
- `-Check` reports it like the other items. `-Uninstall` removes both keys and the file (the file may be in use by LogonUI; schedule delete-on-reboot if so).
- Checks the Windows setting *"For improved security, only allow Windows Hello sign-in for Microsoft accounts"* and reports it, since it can hide password-based tiles. *(Builder verifies whether it blocks a third-party provider and, if so, the installer explains how to turn it off.)*
- Release zip adds `WakeCredentialProvider.dll`.

## 10. Security

| Threat | Defence |
|---|---|
| Someone on the Wi-Fi sniffs `/unlock` | Password is AES-GCM encrypted with a key derived from the pairing secret, which never crossed the network. |
| Replays a captured `/unlock` | Existing nonce and timestamp checks; ciphertext bound to the nonce. |
| Sends their own `/unlock` | No pairing secret → bad signature. |
| Steals the phone | Password key needs the owner's fingerprint or PIN for every use. |
| Copies the PC's disk | Password isn't stored on the PC. |
| User-level program impersonates the DLL's pipe | Agent checks the pipe owner is SYSTEM/Administrators before writing. |
| User-level program sends a password to the DLL | Gains nothing: it would need the correct password. DACL allows only the locked user and SYSTEM. |
| User-level program replaces the DLL | DLL lives in `Program Files`, admin-only. |
| Wrong saved password trips account lockout | One attempt, then the phone clears the password and stops. |
| DLL crash takes down the lock screen | Tiny DLL, no exceptions escape, other tiles untouched, tested in a VM first, documented recovery (§12). |

Out of scope: malware already running as the user (it already owns the session) or as administrator.

## 11. UI

Reuses the existing visual system (dark, Manrope, current pills and buttons).

- Main screen, PC on and lock screen up: status pill shows **Locked**; Lock button becomes **Unlock** with a fingerprint icon.
- Settings → the PC: new **Fingerprint unlock** row: Off / On / "Install the unlock module on the PC first".
- Mockups at real device scale (412 × 891 dp) are produced and checked before the UI is built.

## 12. Build, test and safety

**Toolchain:** the DLL needs MSVC and the Windows SDK, which aren't on the dev PC (no compiler, no SDK at the time of writing). Options: install Visual Studio 2022 Build Tools (C++ workload, Windows 11 SDK), or build it on a GitHub Actions Windows runner. *Open decision for the user.* The DLL builds with CMake from `credential-provider/`; the Gradle `agentZip` task picks up the built DLL.

**Testing:**

- Kotlin (protocol, agent, app view models): unit tests like the rest of the project. Unlock encryption round trip, `/unlock` endpoint with a fake relay, relay behaviour with a fake pipe, result mapping, "clear password on wrong_password".
- DLL: a small native test harness for the pipe protocol and the packing of the logon structure. Real sign-in behaviour is tested by hand.
- **First run of the DLL is in a VM** (Hyper-V is available on Windows 11 Pro): install, lock, unlock from a PowerShell pipe client, unlock from the phone, uninstall. Only after that passes does it go on the real PC.
- As in the base design, the user runs every elevated install and every lock/unlock test on the real PC; the builder stops at those checkpoints.

**Recovery** (in the runbook): the normal password/PIN tiles always remain. If LogonUI crashes repeatedly: boot to Windows Recovery → Command Prompt → load the offline SOFTWARE hive and delete our provider key, or rename the DLL.

**Spike first:** before any app or agent work, build the minimal DLL and test in the VM, driven by a PowerShell pipe client, to answer the early risks: auto-submit works behind the lock-screen curtain, the Microsoft-account qualified name works, the pipe owner SID is what §7 expects, and the "Windows Hello only" setting's effect.

## 13. Version 2 (not in this build): sign in after cold boot

Needs a Windows service running as SYSTEM, started before login, serving `/unlock` (and `/status`) so the phone can reach the PC before anyone signs in, and the DLL supporting `CPUS_LOGON`. The service owns the pipe client role instead of the tray agent. Pairing secret storage moves to a location the service can read (DPAPI machine scope, admin-only ACL). Designed separately once version 1 is proven.

## 14. Left to the builder

- Exact JNA calls for pipe probing and owner check; exact wording of setup copy.
- Whether the display needs waking explicitly after sleep.
- CLSID value, DLL resource details, tile bitmap.
- Event-log logging in the DLL or none.
- Native test harness shape.

## 15. Decisions made without explicit user input

- Password stored **on the phone**, released per fingerprint, not on the PC. Stronger against PC disk theft; the cost is re-entering it on the phone after a password change.
- Phone PIN allowed as fallback to the fingerprint.
- Version 1 is unlock-only (Lock and Sleep); cold-boot sign-in is version 2.
- Unlock is a tap (fingerprint is the deliberate step), not a hold.
- No Windows service in version 1; the tray agent relays to the DLL.
- Installer item is opt-in.
- One unlock attempt per tap; a rejected password is cleared, never retried automatically.
- Spec lives in `docs/specs/` to match the existing design doc.
