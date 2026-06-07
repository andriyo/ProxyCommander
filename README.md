# ProxyCommander

Android Studio plugin for quickly controlling `adb reverse`, device proxy setup, and optional device time resync across emulators/devices used in development.

## What it does

- Connect Proxy to All Devices:
  - enables `adb reverse tcp:<port> tcp:<port>`
  - sets `settings global http_proxy localhost:<port>`
- Connect Proxy to Current and Disconnect Others:
  - detects active emulator from IDE context
  - connects proxy on that emulator
  - clears proxy settings on all other available devices
- Disconnect Proxy from All Devices:
  - removes reverse mapping
  - clears proxy settings (including PAC)
- Devices...:
  - lists available devices alongside remembered auto-connect targets
  - shows connection and proxy status, plus API level and serial
  - per-device actions: proxy, proxy only this device, and a host-proxy connection test
  - bulk Proxy All / Unproxy All

## Auto-reconnect

Devices that you connect (via the actions or the Devices dialog) are remembered. A background
watcher tracks `adb` device snapshots, and when a remembered device reappears — for example
after a reboot or emulator restart — the plugin waits for it to finish booting and re-applies
the reverse mapping and proxy automatically. Disconnecting all devices clears the remembered set.

When a device that is *not* remembered newly appears, the plugin shows a non-intrusive
notification offering to connect it to the proxy. Ignoring the notification leaves the device
untouched; choosing **Connect to Proxy** connects it and remembers it, so it auto-reconnects
from then on. Devices already connected when the project opens form a baseline and are not
offered, to avoid notification noise at startup.

## Connection testing

The Devices dialog can verify a device end-to-end: it confirms the device proxy points at
`localhost:<port>`, the reverse mapping is enabled, and a host proxy is actually listening on
`<port>`. Charles, Proxyman, mitmproxy, Burp, and Fiddler are recognized by name; any other process
listening on the port is reported generically.

## Status bar

A status bar widget shows the configured port and the number of connected devices (`Proxy :8888 (2)`).
Click it to open the Devices dialog. The Devices dialog also refreshes live as devices connect and
disconnect.

## Settings

`Settings/Preferences -> Tools -> Proxy Commander` (also reachable via
`Tools -> Proxy Commander -> Settings...`). Settings are application-wide and shared across all open
projects; a single background watcher serves every project. Settings from older per-project builds are
migrated automatically the first time a project opens.

- `Port` (default: `8888`)
- `ADB Path` (optional). When empty, adb is resolved in order: `$ADB`, the Android SDK
  (`local.properties` `sdk.dir`, `ANDROID_SDK_ROOT`, `ANDROID_HOME`, or standard SDK
  locations), then `adb` on `PATH`.
- `Reset device clock on connect` (enabled by default; aligns the device clock to host time via
  `time_detector`, which works offline without NTP reachability)

## Installation

1. Build plugin ZIP:

```bash
./gradlew buildPlugin
```

2. Install from disk in Android Studio:
   - `Settings/Preferences -> Plugins`
   - gear icon -> `Install Plugin from Disk...`
   - select the ZIP under `build/distributions/` (e.g. `ProxyCommander-1.3.0.zip`)
3. Restart Android Studio.

## Run in sandbox (development)

```bash
# Point -PandroidStudioPath at your local Android Studio install:
./gradlew runIde -PandroidStudioPath="/Applications/Android Studio.app/Contents"
```

## Integration Tests (real adb + devices)

These tests call `ProxyCommanderController` methods directly and verify on connected devices that:

- `Connect All` enables reverse + proxy
- `Connect active emulator and clear others' proxy` keeps only the active emulator proxied
- `Disconnect All` clears proxy/reverse
- with connect enabled, devices can reach `http://0.0.0.0:<port>`
- after disconnect, devices cannot reach `http://0.0.0.0:<port>` but can still reach internet hosts

Run:

```bash
PROXY_COMMANDER_RUN_INTEGRATION_TESTS=1 ./gradlew test --tests io.github.andriyo.proxycommander.ProxyCommanderIntegrationTest
```

Optional:

- `PROXY_COMMANDER_IT_PORT` (default: `8080`)

## Where actions appear

- `Tools -> Proxy Commander`
- Run toolbar (`ToolbarRunGroup`) near Android attach debugger actions
- Running Devices secondary toolbar (`StreamingToolbarSecondary`) for supported quick actions

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
