# ProxyCommander

Android Studio plugin for quickly controlling `adb reverse`, device proxy setup, and optional device time resync across emulators/devices used in development.

## What it does

- Connect Proxy to All Devices:
  - enables `adb reverse tcp:<port> tcp:<port>`
  - sets `settings global http_proxy localhost:<port>`
- Connect Proxy to Current and Disconnect Others:
  - detects the active device (emulator or physical) from IDE context; with no context it falls
    back only when exactly one device is connected
  - connects proxy on that device
  - clears proxy settings on all other available devices
- Disconnect Proxy from All Devices:
  - removes reverse mapping
  - clears proxy settings (including PAC)
  - verifies direct internet access by requiring a valid device-side HTTP response; an offline
    device produces a warning without turning successful proxy cleanup into a failure
- Devices...:
  - lists available devices alongside remembered auto-connect targets
  - shows connection and proxy status, plus API level and serial
  - per-device actions: proxy, proxy only this device, unproxy, host-proxy connection test, and
    forget (disable auto-connect)
  - bulk Proxy All / Unproxy All

## Auto-reconnect

Devices that you connect (via the actions or the Devices dialog) are remembered. A background
watcher tracks `adb` device snapshots, and when a remembered device reappears — for example
after a reboot or emulator restart — the plugin waits for it to finish booting and re-applies
the reverse mapping and proxy automatically. Disconnecting all devices clears the remembered set;
**Forget** in the Devices dialog removes a single device, and **Unproxy** disconnects it and
forgets it in one step (otherwise auto-reconnect would immediately re-apply the proxy).

When a device that is *not* remembered newly appears, the plugin shows a non-intrusive
notification offering to connect it to the proxy. Ignoring the notification leaves the device
untouched; choosing **Connect to Proxy** connects it and remembers it, so it auto-reconnects
from then on; choosing **Don't Offer Again** mutes the offer for that device permanently
(connecting it later via the Devices dialog unmutes it). Devices already connected when the
project opens form a baseline and are not offered, to avoid notification noise at startup.

## Connection testing

The Devices dialog can verify a device end-to-end: it confirms the device proxy points at
`localhost:<port>`, the reverse mapping is enabled, and a host proxy is actually listening on
`<port>`. Charles, Proxyman, mitmproxy, Burp, and Fiddler are recognized by name; any other process
listening on the port is reported generically.

## Status bar

A status bar widget shows the configured port plus proxied/connected device counts
(`Proxy :8888 (1/2)` means one of two connected devices is proxied). Clicking it opens a popup
with the plugin's actions (Devices..., Connect/Disconnect All, Connect Current, Settings...).
The Devices dialog also refreshes live as devices connect, disconnect, or change proxy state.

## Settings

`Settings/Preferences -> Tools -> Proxy Commander` (also reachable via
`Tools -> Proxy Commander -> Settings...`). Settings are application-wide and shared across all open
projects; a single background watcher serves every project. Settings from older per-project builds are
migrated automatically the first time a project opens.

- `Port` (default: `8888`). Changing the port keeps a short history of earlier ports so that
  connect/disconnect also removes reverse mappings left over from before the change.
- `ADB Path` (optional). When empty, adb is resolved in order: `$ADB`, the Android SDK
  (`local.properties` `sdk.dir`, `ANDROID_SDK_ROOT`, `ANDROID_HOME`, or standard SDK
  locations), then `adb` on `PATH`.
- `Reset device clock on connect` (enabled by default; aligns the device clock to host time via
  `time_detector`, which works offline without NTP reachability; on Android versions without
  `time_detector` the plugin reports that the clock could not be forced)

## Installation

1. Build plugin ZIP:

```bash
./gradlew buildPlugin
```

2. Install from disk in Android Studio:
   - `Settings/Preferences -> Plugins`
   - gear icon -> `Install Plugin from Disk...`
   - select the ZIP under `build/distributions/` (e.g. `ProxyCommander-1.4.1.zip`)
3. Restart Android Studio.

## Releasing

Pushing a `v*` tag that exactly matches the Gradle project version (e.g. `v1.4.1`) runs the
`Release` workflow: unit tests, plugin compatibility and project-configuration verification,
structure checks, signing, `publishPlugin` to JetBrains Marketplace (using the `PUBLISH_TOKEN`,
`CERTIFICATE_CHAIN`, `PRIVATE_KEY`, and `PRIVATE_KEY_PASSWORD` repository secrets), and a GitHub
release with only the signed plugin ZIP attached.

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
- after disconnect, devices cannot reach `http://0.0.0.0:<port>` but receive a valid HTTP status
  response directly from at least one internet host

> **Warning:** This suite mutates and clears proxy/reverse state on every attached adb device. It
> runs `adb root`/`adb unroot` and deliberately drifts, then resets, emulator clocks. Run it only
> with isolated, disposable emulators; disconnect physical and shared devices first.

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
