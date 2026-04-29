# SpeakEasy — Privacy Policy

_Last updated: 2026-04-28_

SpeakEasy is a peer-to-peer Bluetooth intercom for motorcycle riders. It is
designed to work without any server, account, or internet connection.

## Data we collect

**None.** SpeakEasy does not run any backend, has no analytics SDK, and does
not transmit any data to the developer or to third parties.

## What stays on your device

- **Voice audio.** Captured from the microphone, encoded with Opus (or μ-law /
  PCM if you choose), and sent **directly over Bluetooth Classic (RFCOMM)** to
  the paired peer phone. Audio is never recorded, never stored, and never
  uploaded.
- **App settings.** Codec choice, microphone mode, language, theme, heartbeat
  interval, etc. are stored locally in Android `SharedPreferences`.
- **Connection profiles.** Up to eight Bluetooth peer profiles (MAC + label) so
  you can quickly reconnect to a known partner. Stored locally; you can delete
  individual entries via long-press in the app.
- **Crash logs.** When the app crashes, a plain-text stack trace is written to
  the app-private folder `Android/data/com.speakeasy.intercom/files/crashes/`
  on your device. The newest 30 logs are kept; older ones are deleted
  automatically. **Logs are never uploaded.** They only leave your device if
  *you* explicitly tap "Send feedback → Send with logs" (see below) or "Share
  crash logs" in the diagnostics screen.

## Permissions and why we need them

| Permission | Purpose |
| --- | --- |
| `RECORD_AUDIO` | Capture your voice from the microphone (or Bluetooth headset). |
| `MODIFY_AUDIO_SETTINGS` | Switch audio routing to the Bluetooth headset (SCO). |
| `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` / `BLUETOOTH_ADVERTISE` (Android 12+) | Discover the peer phone, accept incoming RFCOMM connections, exchange audio. |
| `BLUETOOTH` / `BLUETOOTH_ADMIN` / `ACCESS_FINE_LOCATION` (Android 8–11) | Same as above on older devices. The location permission is required by the OS for Bluetooth scanning; SpeakEasy does **not** read or store your location. |
| `FOREGROUND_SERVICE` (+ `_MICROPHONE` / `_CONNECTED_DEVICE` on Android 14+) | Keep the audio pipeline running while the screen is off. |
| `POST_NOTIFICATIONS` | Show the persistent "Connected" notification with a one-tap Disconnect action. |
| `WAKE_LOCK` | Keep the CPU running so audio is not interrupted. |
| `VIBRATE` | Short haptic cues on connection state changes. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Ask you to exclude SpeakEasy from battery optimization so the connection survives long rides. |

The location permission on Android 8–11 is declared with
`android:usesPermissionFlags="neverForLocation"` (Android 12+ Bluetooth
permissions). SpeakEasy never queries location APIs and never derives or
stores location data.

## Sending feedback

The "Send feedback" entry in the menu prepares a plain email addressed to the
developer (`speakeasy@skymail.eu`). The email contains:

- the text you write,
- the app version,
- your device manufacturer / model and Android version (helps reproduce bugs).

If you tap **"Send with logs"**, the locally stored crash-log text files are
attached. They contain stack traces and the device fingerprint above — **no
audio, no contacts, no location**.

You see the email in your normal email app and can edit or delete anything
before sending. Nothing is transmitted automatically.

## Data sharing

The developer receives email only when *you* hit "Send" in your email app.
That email is read by a single person (the developer) and is not shared with
third parties or used for advertising.

## Children

SpeakEasy does not knowingly target children under 13. The app has no
account system, no chat function, and no online component.

## Changes to this policy

If the policy ever changes, the new version will be published at the same URL
with an updated date at the top.

## Contact

Developer e-mail: speakeasy@skymail.eu
