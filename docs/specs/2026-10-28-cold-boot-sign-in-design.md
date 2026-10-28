# Fingerprint sign-in after a cold boot (version 2 of fingerprint unlock)

Builds on `docs/specs/2026-10-27-fingerprint-unlock-design.md` (§13 sketched this) and `docs/specs/2026-10-26-wake-on-lan-design.md`.

## 1. Problem

Wake a PC from full shutdown, tap Unlock, touch the fingerprint sensor: nothing happens. The tray agent starts at logon, so after a cold boot nothing on the PC is listening. The phone shows "On, waiting for agent". Sleep and Lock work because the session, and the agent, survive them.

## 2. Goal

From full shutdown: tap Wake, wait for the sign-in screen, tap Unlock, touch the sensor, land on the desktop. The design should be as secure as it can be while still doing that.

## 3. Non-goals

- Signing in a different Windows account than the one that installed Wake.
- Finding the PC when its IP address changed during boot. The phone uses the last known IP, and the pre-login side answers no discovery. A DHCP reservation on the router avoids this.
- Remote access from outside the home network.
- Shutdown, reboot, sleep or lock before login.

## 4. Anti-goals (a failure even if sign-in works)

- Anything that stores the Windows password on the PC (no auto-logon, no LSA secret).
- A pre-login process running as SYSTEM or with admin rights.
- A pre-login listener that is reachable while the user is signed in and the session is unlocked.
- A pre-login process that can send shutdown, reboot or other commands.
- Any change to how version 1 behaves when it is not opted in.

## 5. Shape

```
Phone ──signed /signin/* on TCP 47615──▶ WakeSignIn.exe ─────pipe──▶ WakeCredentialProvider.dll (in LogonUI)
           (key: signInKey)                (startup task, LOCAL SERVICE
                                            + task SID NT TASK\Wake Sign-in,
                                            listens only while the
                                            lock or sign-in screen is up)
Phone ──signed /status, /command, /unlock on TCP 47613──▶ tray agent (unchanged, user session)
```

A new program, `WakeSignIn.exe` (called "the sign-in service" below, though it's a scheduled task, not a Windows service), ships from the same Kotlin `agent` module with its own entry point. A scheduled task named `Wake Sign-in` starts it at boot. It does one job: relay a sealed password from the phone to the credential provider's pipe, using the existing `PipeUnlockRelay`.

## 6. Security decisions

1. **Low-privilege account.** The task runs as `NT AUTHORITY\LOCAL SERVICE`, never SYSTEM or a user. Its principal sets `ProcessTokenSidType = Unrestricted`, which adds a SID unique to this task, `NT TASK\Wake Sign-in`. Every grant below names that task SID, never `LOCAL SERVICE`, which other Windows services share. `RequiredPrivilege = SeChangeNotifyPrivilege` strips every other privilege. Triggers: at startup, plus a repeat every 5 minutes with `MultipleInstances = IgnoreNew`, so a crashed process is back within 5 minutes. The program also holds a single-instance lock.
2. **Separate, derived key.** The service never holds the pairing secret. It holds `signInKey = HKDF-SHA256(pairingSecret, salt = empty, info = "wake-signin-v1", 32)`. The phone derives the same key from the secret it already has, so nobody re-pairs. If the service's key leaks, an attacker can't send shutdown or other commands; those need the pairing secret. Every `/signin/*` request is signed with `signInKey` and the password is sealed with it (same HMAC and AES-GCM scheme as version 1, `signInKey` in place of the secret).
3. **No cross-replay.** Sign-in paths are `/signin/status` and `/signin/unlock`, and the signature covers the path. A request captured on the agent's port fails on the service, and the other way round, because both the key and the path differ.
4. **Listens only when needed.** The service checks for the pipe `\\.\pipe\wake-unlock` every second. It binds TCP 47615 only while the pipe exists (the lock or sign-in screen is up). It closes the port 5 s after the pipe disappears, once no request is in flight. While you're signed in and unlocked, nothing listens on 47615.
5. **Tiny surface.** Two routes. Request bodies over 4096 bytes are refused with 413 before they are read. A 2-thread pool. No pairing endpoint, no commands, no discovery reply.
6. **Admin-only binaries.** The service's program lives in `C:\Program Files\Wake\SignIn\`, where only administrators can write. It is never run from `%LOCALAPPDATA%`.
7. **Key at rest.** The tray agent writes `signInKey` to `C:\ProgramData\Wake\signin\signin.key`, encrypted with DPAPI machine scope (`CRYPTPROTECT_LOCAL_MACHINE | CRYPTPROTECT_UI_FORBIDDEN`). Folder ACL, inheritance off: SYSTEM full, Administrators full, the installing user modify, `NT TASK\Wake Sign-in` read. The log folder `C:\ProgramData\Wake\logs\` grants the task SID modify and the user read.
8. **Pipe access.** The DLL's pipe DACL adds `NT TASK\Wake Sign-in` (read+write) next to SYSTEM and the target user. The owner check stays: the service writes only to a pipe owned by `S-1-5-18`.
9. **Right account.** The installer stores the installing user's SID in `HKLM\SOFTWARE\Wake` value `SignInUserSid` (REG_SZ; admin-only write). The DLL picks that user from LogonUI's user array, and falls back to the first user when the value is absent or the user isn't listed, which is version 1's behavior.
10. **Firewall.** One new inbound rule, `Wake Sign-in`: TCP 47615, `RemoteAddress LocalSubnet`, bound to the program `C:\Program Files\Wake\SignIn\WakeSignIn.exe`.
11. **Opt-in.** A separate installer question, asked only when the unlock module is installed: `Allow fingerprint sign-in after shutdown? (y/N)`, with `-SignIn` / `-NoSignIn` switches. Off by default.
12. **BitLocker is reported.** `-Check` reports whether BitLocker protects C:. Wake doesn't change it. A stolen drive is the one thing these decisions don't cover, and BitLocker does.

Kept from version 1: one attempt per tap, never retried; `wrong_password` deletes the phone's saved password; the password is never logged or written to disk; it is zeroed after use; the phone verifies the PC's MAC in every status reply.

## 7. Wire protocol additions (`protocol` module)

- `Ports.SIGNIN = 47615`.
- `fun signInKey(secret: ByteArray): ByteArray` as in §6.2.
- `GET /signin/status` → `SignInStatus(mac: String, serverTimeMs: Long, signInScreen: Boolean)`. `signInScreen` is true while the pipe exists.
- `POST /signin/unlock`: body `UnlockRequest` sealed with `signInKey`, reply `UnlockResponse`, same results as `/unlock`.
- Errors and the stale-clock proof work exactly as on the agent, using `signInKey`.

## 8. Phone

- While the PC answers probes but the agent doesn't (`PcState.On(agent = false)`), the phone also asks `/signin/status` on port 47615 with `signInKey`. Refused or unreachable means no sign-in screen. The reply's MAC must match the device.
- When `signInScreen` is true, the Unlock button shows in Lock's place, and Unlock is the only enabled command. The caption reads `On, at sign-in`.
- Unlock goes to the agent when the agent answered the last poll, otherwise to the service. Same fingerprint prompt, same result handling. Success reads `<name> signed in`.

## 9. Fallback if the startup task doesn't work

If the task SID `NT TASK\Wake Sign-in` doesn't resolve for `icacls` and `LookupAccountNameW`, or the task doesn't start before sign-in (Task 1's spike), the fallback is a real Windows service. It runs as the virtual account `NT SERVICE\WakeSignIn`, with `sc sidtype restricted` and `sc privs SeChangeNotifyPrivilege`, through a JNA service-control dispatcher. The grants then name `NT SERVICE\WakeSignIn`. Granting plain `LOCAL SERVICE` (`S-1-5-19`) is not an option. John decides at the checkpoint.

## 10. Left to the builder

- How the installer sets the task's repeat interval (PowerShell trigger objects or task XML).
- Log line wording.
- How `-Check` lays out the new rows.

## 11. Calls made without explicit user input

- A separate pre-login program instead of moving the whole agent into one: lock, sleep and the tray must run in the user session anyway, and keeping them apart is what lets the pre-login side be this small.
- Startup task before Windows service (John chose this on 2026-10-28): less code. It gives up only the write-restricted token.
- A derived key instead of sharing the pairing secret.
- A port that is closed except while the lock or sign-in screen is up.
- A separate opt-in question, off by default.
- No discovery reply before login.
