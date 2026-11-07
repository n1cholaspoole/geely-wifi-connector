[English](README.md) | [Русский](README.ru.md)

# Wi-Fi Connector

A small Android app (API 21+) that connects the phone to one saved Wi-Fi network when a
chosen app is opened. If foreground detection is not available on the phone, an optional
always-on mode connects whenever the configured network is in range.

This is a personal/sideload app. It is not published to Google Play and is not affiliated
with Spotify. Spotify (`com.spotify.music`) is only the default trigger app; you can pick
any installed app instead.

## What it does

- **App trigger (primary mode):** an Accessibility Service watches foreground app changes.
  When the selected trigger app comes to the foreground, the app saves/activates the
  configured Wi-Fi network and connects to it. Choose the trigger app from a list of
  installed apps in the app screen (default: Spotify).
- **Always-connect fallback:** a foreground service watches Wi-Fi scan results and asks
  Android to connect whenever the configured SSID is visible. It also restarts after a
  reboot.
- **Manual test:** the main screen can connect immediately and shows a live status/log.

The Accessibility Service only checks the foreground package name. It does **not** read
screen content, text, passwords, or notifications.

## Requirements

- Android 5.0 (API 21) or newer.
- The app you want to use as the trigger installed on the phone.
- Location permission (needed by Android for Wi-Fi scanning).
- Location services turned on for the always-connect fallback.
- On Android 13+, notification permission for the foreground-service notification.

## Build

The project uses Gradle 8.7 and Android Gradle Plugin 8.5.2. You need JDK 17 and Android
SDK Platform 34 / Build Tools 34.

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
```

The APK is written to:

```text
app/build/outputs/apk/debug/app-universal-debug.apk
```

Install it with ADB or by copying the APK to the phone:

```bash
adb install -r app/build/outputs/apk/debug/app-universal-debug.apk
```

If `local.properties` is missing, create it with your SDK path:

```properties
sdk.dir=/path/to/android-sdk
```

## First-time setup

1. Open the app.
2. Enter the Wi-Fi **SSID** and password.
3. Select the security type:
   - `WPA/WPA2` for normal home/phone hotspot networks.
   - `Open` for a network without a password.
   - `WPA3` only on Android 10+.
4. Under **Trigger app**, tap **Choose app** and select the app that should trigger the
   connection. The default is Spotify; choose something else if you prefer.
5. Tap **Grant location / notification permissions** and allow the prompts.
6. Tap **Enable app detection**, then enable **Wi-Fi Connector** under
   Android Settings > Accessibility.
7. Optionally enable **Always connect when the network is in range**.
8. Tap **Save & connect** once to verify the connection.
9. Turn on Wi-Fi and location services. Open the trigger app; the log should show the
   trigger.

On Android 11+ there is also **Add network via Android dialog**, which lets you approve
the network through Android's own system UI. This is useful if the phone's OEM blocks the
legacy Wi-Fi APIs.

## How connection works

Android changed Wi-Fi APIs over time, so the app uses two paths:

| Android version | Path |
| --- | --- |
| 5.0-9 (API 21-28) | `WifiManager.addNetwork()` + `enableNetwork()` |
| 10+ (API 29+) | Legacy path is still attempted because the app targets SDK 28. If the OEM blocks it, the app adds a `WifiNetworkSuggestion` and Android performs the connection. |

The app deliberately sets `targetSdk 28`. Apps targeting Android 10 (API 29) or newer are
not allowed to use the legacy `addNetwork()` / `setWifiEnabled()` APIs. Keeping the target
at 28 makes the immediate connection path work on many Android 10-14 devices. The
trade-off is that the app is not Play-Store compliant for new submissions; sideload it for
personal use.

Foreground detection uses an Accessibility Service with no `packageNames` filter, because
the trigger app is configurable. The service receives window-change events for all apps
and only acts when the event's package matches the saved trigger package.

## Password storage

The Wi-Fi password is stored in a dedicated `secrets` preferences file, separate from the
rest of the settings, and is encrypted with a key that never leaves the Android Keystore:

- **Android 6.0+ (API 23+):** AES/GCM.
- **Android 5.0-5.1 (API 21-22):** RSA (the Keystore does not support symmetric keys on
  these versions).

If encryption is unavailable, the app writes a warning to its log and keeps the value in
the dedicated secrets file so the app continues to work. A password saved in plain text by
an older version is migrated into the encrypted store automatically on first read.

## Localization

The interface is available in English and Russian. Russian strings live in
`app/src/main/res/values-ru/strings.xml`.

## Limitations

- **Android does not guarantee silent Wi-Fi switching.** On Android 10+ some devices
  require the user to approve a suggested network once. The app will log this and can open
  Android's approval dialog.
- **The Accessibility Service must be enabled manually.** Android does not allow apps to
  grant it themselves. If it is disabled, use the always-connect fallback or re-enable it.
- **The trigger app must be the foreground app.** The package name is configurable and
  defaults to `com.spotify.music`.
- **The always-connect fallback is battery-sensitive.** Android may throttle background
  scans or kill the service. Disable battery optimization for the app if the service is
  being stopped. The service uses a low-importance notification and a 60-second poll.
- **Wi-Fi scanning requires location services to be on** on most Android versions, even
  though the app does not use your location.
- **Some OEM skins** block `addNetwork`, `setWifiEnabled`, or accessibility events. The app
  logs the failure and falls back to suggestions where possible.

## Troubleshooting

- **"App detection: OFF"**: enable the accessibility service and keep the checkbox on. On
  Android 13+ a sideloaded app may be blocked by "restricted settings"; open Android
  Settings > Apps > Wi-Fi Connector > menu > **Allow restricted settings**, then enable the
  accessibility service again.
- **"Location permission is missing"**: tap the permissions button and allow location.
- **"Wi-Fi is off and this app is not allowed to turn it on"**: turn Wi-Fi on manually,
  then tap Save & connect.
- **Network is visible but no connection**: check the password/security type, or use
  **Add network via Android dialog** on Android 11+. If you previously declined a suggested
  network, Android may have removed this app's Wi-Fi control permission; re-grant it under
  Settings > Apps > Special app access > Wi-Fi control.
- **Service stops after a while**: disable battery optimization for the app and make sure
  notifications are allowed.
- **Nothing happens when the trigger app opens**: open the app again after enabling the
  service; accessibility events are delivered on window changes. Check the app log for a
  "Selected app opened" entry, and make sure the trigger app matches the one you selected.

## Project structure

```text
app/src/main/java/dev/n1cholaspoole/wificonnector/
  MainActivity.java                   configuration UI, app picker, permissions, status/log
  SpotifyAccessibilityService.java    selected-app foreground detection
  WifiAutoConnectService.java         optional always-on scan watcher
  WifiConnector.java                  legacy + suggestion Wi-Fi connection logic
  BootReceiver.java                   restarts the fallback after reboot
  Prefs.java                          settings (password routed to SecretStore)
  SecretStore.java                    encrypted Wi-Fi password store (Android Keystore)
  AppLog.java                         persistent log + UI broadcast

app/src/main/res/xml/accessibility_service_config.xml
app/src/main/res/layout/activity_main.xml
app/src/main/res/values/strings.xml
app/src/main/res/values-ru/strings.xml
```
