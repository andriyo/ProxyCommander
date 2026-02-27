# ProxyCommander

Android Studio plugin for quickly controlling `adb reverse` and device proxy setup across emulators/devices used in development.

## What it does

- Connect all devices:
  - enables `adb reverse tcp:<port> tcp:<port>`
  - sets `settings global http_proxy localhost:<port>`
- Connect active emulator and clear others proxy:
  - detects active emulator from IDE context
  - connects proxy on that emulator
  - clears proxy on all other connected devices
- Disconnect all devices:
  - removes reverse mapping
  - clears proxy settings (including PAC)
- Select emulator and disconnect others:
  - shows connected emulator list
  - keeps one connected and disconnects all others
- Fill password on active device:
  - detects active device
  - finds likely password field in foreground app UI hierarchy
  - enters configured development password

## Settings

`Tools -> Proxy Commander -> Settings...`

- `Port` (default: `8888`)
- `ADB Path` (optional, falls back to `$ADB` or `adb` in `PATH`)
- `Dev Password (plain text)` for quick autofill action

Note: dev password is intentionally stored as plain text in project settings for local development convenience.

## Installation

1. Build plugin ZIP:

```bash
cd /Users/andrii/IdeaProjects/ProxyCommander
./gradlew buildPlugin
```

2. Install from disk in Android Studio:
   - `Settings/Preferences -> Plugins`
   - gear icon -> `Install Plugin from Disk...`
   - select:
     `build/distributions/ProxyCommander-1.0-SNAPSHOT.zip`
3. Restart Android Studio.

## Run in sandbox (development)

```bash
./gradlew runIde -PandroidStudioPath="/Users/andrii/Applications/Android Studio.app/Contents"
```

## Where actions appear

- `Tools -> Proxy Commander`
- Run toolbar (`ToolbarRunGroup`) near Android attach debugger actions
- Running Devices secondary toolbar (`StreamingToolbarSecondary`) for supported actions

## Troubleshooting

If app still behaves as if proxy is set after disconnect:

```bash
adb -s <serial> shell settings get global http_proxy
adb -s <serial> shell settings get global global_http_proxy_host
adb -s <serial> shell settings get global global_http_proxy_port
adb -s <serial> shell settings get global global_http_proxy_pac
```

Expected values are blank/`null` (or `:0` for `http_proxy` depending on device).

If toolbar buttons do not show up immediately, restart the IDE instance.
