# Wake runbook

Everything runs from the project folder. Use Git Bash (or PowerShell with `.\gradlew.bat`).

`adb` lives at `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`. Find the phone's serial with `adb devices`.

## Build

```bash
./gradlew test                        # all unit tests: protocol, agent, app
./gradlew :app:assembleRelease        # app/build/outputs/apk/release/app-release.apk
./gradlew :agent:agentJar             # agent/build/libs/wake-agent.jar
```

Release signing reads `keystore.properties` (git-ignored) in the project root:

```
storeFile=C:/Users/<you>/.android/wake-release.jks
storePassword=…
keyAlias=wake
keyPassword=…
```

**Back up `wake-release.jks` and `keystore.properties` together.** Android only installs an update over the existing app if it's signed with the same key. Losing the key means uninstalling, which also loses the pairing.

## Install or update the app

```bash
adb -s <serial> install -r app/build/outputs/apk/release/app-release.apk
```

## Install or update the agent on the PC

Run as administrator. It's safe to repeat, and it only replaces the jar when it changed:

```powershell
powershell -ExecutionPolicy Bypass -File pc-setup\setup.ps1 -AgentJar agent\build\libs\wake-agent.jar
```

After updating the jar, quit the agent from the tray icon and start the "Wake Agent" scheduled task (or log off and on).

## Pair (or re-pair) the phone

1. On the PC, right-click the green Wake icon in the tray and choose **Pair phone…**. A 6-digit code appears.
2. In the app: Settings → Devices → Add a PC (or Pair again), choose MY-PC, and type the code.

Without the tray, `javaw -jar %LOCALAPPDATA%\Wake\wake-agent.jar --pair` starts the agent in pairing mode and writes the code to `%LOCALAPPDATA%\Wake\agent.log`.

Pairing again replaces the old secret, and only one phone can be paired at a time.

## Logs and files

| What | Where |
|---|---|
| Agent log | `%LOCALAPPDATA%\Wake\agent.log` |
| Agent jar | `%LOCALAPPDATA%\Wake\wake-agent.jar` |
| Pairing secret (PC) | `%APPDATA%\Wake\secret.bin` |
| Pairing secret (phone) | Android Keystore, app data |
| App logs | `adb logcat` |

## Debug-build helpers

These work only in debug builds (`./gradlew :app:installDebug`):

```bash
adb shell am start -S -n dev.jano.wake/.MainActivity --es demoState on        # off|waking|on|undo|stopping|didntwake|away|noagent|locked|unlocking
adb shell am start -S -n dev.jano.wake/.MainActivity --es debugAction discover  # logs discovery + probe under tag WakeDebug
```

## Fingerprint unlock: recovery

The unlock module only adds a "Wake" tile to the lock screen. The normal password and PIN tiles always stay, so if the Wake tile misbehaves, pick another sign-in option and sign in as usual, then run `credential-provider\tools\uninstall-dev.ps1` (or `setup.ps1 -Uninstall`) elevated.

If the lock screen itself keeps crashing and you can't sign in:

1. Hold Shift and click Restart (or interrupt boot three times) to reach Windows Recovery → Troubleshoot → Advanced options → Command Prompt.
2. Remove the provider from the offline registry:

   ```cmd
   reg load HKLM\OFF C:\Windows\System32\config\SOFTWARE
   reg delete "HKLM\OFF\Microsoft\Windows\CurrentVersion\Authentication\Credential Providers\{8C5D3B0E-6A41-4C7E-9F2B-5D1A7E3C9B04}" /f
   reg unload HKLM\OFF
   ```

   (In Recovery the Windows drive may not be `C:`; check with `dir D:\Windows` etc.) Renaming `C:\Program Files\Wake\WakeCredentialProvider.dll` also works.
3. Restart. Sign in normally.

## Ports

| Port | Use |
|---|---|
| UDP 9 | Wake-on-LAN magic packet (broadcast `192.168.1.255`) |
| TCP 47613 | Agent HTTP API (signed requests) |
| UDP 47614 | "Find my PC" discovery |
| TCP 135 | Used only as a "PC is up" probe |
