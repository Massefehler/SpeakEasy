# SpeakEasy — Datenschutzerklärung

_Stand: 28.04.2026_

SpeakEasy ist ein Peer-to-Peer-Bluetooth-Intercom für Motorradfahrer. Die App
funktioniert ohne Server, ohne Account und ohne Internetverbindung.

## Welche Daten wir erheben

**Keine.** SpeakEasy betreibt kein Backend, nutzt keine Analytics-Bibliothek
und überträgt keine Daten an den Entwickler oder an Dritte.

## Was lokal auf deinem Gerät bleibt

- **Sprach-Audio.** Wird vom Mikrofon aufgenommen, mit Opus codiert (alternativ
  μ-law / PCM) und **direkt über Bluetooth Classic (RFCOMM)** zum gepairten
  Partner-Telefon geschickt. Audio wird nie gespeichert, nie aufgezeichnet,
  nie hochgeladen.
- **App-Einstellungen.** Codec-Auswahl, Mikro-Modus, Sprache, Design,
  Heartbeat-Intervall etc. liegen lokal in Android-`SharedPreferences`.
- **Verbindungsprofile.** Bis zu acht Bluetooth-Peer-Profile (MAC + Label) für
  die Schnellverbindung. Lokal gespeichert; einzelne Einträge können in der
  App per Long-Press gelöscht werden.
- **Crash-Logs.** Wenn die App abstürzt, wird ein Klartext-Stacktrace in den
  app-privaten Ordner `Android/data/com.speakeasy.intercom/files/crashes/`
  geschrieben. Die neuesten 30 Logs bleiben erhalten, ältere werden
  automatisch gelöscht. **Logs werden nicht hochgeladen.** Sie verlassen das
  Gerät nur, wenn *du* in der App auf „Feedback senden → Mit Logs senden"
  oder im Diagnose-Screen auf „Crash-Logs teilen" tippst.

## Berechtigungen und ihr Zweck

| Berechtigung | Zweck |
| --- | --- |
| `RECORD_AUDIO` | Sprache vom Mikrofon (oder Bluetooth-Headset) aufnehmen. |
| `MODIFY_AUDIO_SETTINGS` | Audio-Routing aufs Bluetooth-Headset umschalten (SCO). |
| `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` / `BLUETOOTH_ADVERTISE` (Android 12+) | Partner-Telefon finden, eingehende RFCOMM-Verbindung annehmen, Audio austauschen. |
| `BLUETOOTH` / `BLUETOOTH_ADMIN` / `ACCESS_FINE_LOCATION` (Android 8–11) | Wie oben auf älteren Geräten. Die Standortberechtigung wird vom Betriebssystem für den Bluetooth-Scan verlangt; SpeakEasy liest oder speichert **keine** Standortdaten. |
| `FOREGROUND_SERVICE` (+ `_MICROPHONE` / `_CONNECTED_DEVICE` ab Android 14) | Audio-Pipeline läuft auch bei abgeschaltetem Display weiter. |
| `POST_NOTIFICATIONS` | Persistente „Verbunden"-Benachrichtigung mit einem Tippen-Trennen-Knopf. |
| `WAKE_LOCK` | Hält die CPU wach, damit der Audio-Strom nicht unterbrochen wird. |
| `VIBRATE` | Kurze Vibration bei Statuswechsel. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Fragt nach, ob SpeakEasy von der Akku-Optimierung ausgenommen werden darf, damit lange Touren stabil laufen. |

Die Standortberechtigung auf Android 8–11 wird auf Android 12+ durch die
Bluetooth-Permissions mit `android:usesPermissionFlags="neverForLocation"`
ersetzt. SpeakEasy ruft keine Location-APIs auf und leitet keine Standortdaten
ab.

## Feedback senden

Der Menüeintrag „Feedback senden" bereitet eine Klartext-E-Mail an den
Entwickler (`speakeasy@skymail.eu`) vor. Die Mail enthält:

- den Text, den du schreibst,
- die App-Version,
- Hersteller, Modell und Android-Version deines Geräts (hilft beim
  Reproduzieren von Fehlern).

Tippst du **„Mit Logs senden"**, werden die lokalen Crash-Log-Textdateien
angehängt. Diese enthalten Stacktraces und die oben genannten Geräte-Infos —
**kein Audio, keine Kontakte, keinen Standort**.

Die Mail erscheint in deiner gewohnten Mail-App; du kannst sie vor dem
Absenden noch ändern oder verwerfen. Nichts wird automatisch übertragen.

## Datenweitergabe

Der Entwickler erhält eine Mail nur dann, wenn *du* in deiner Mail-App auf
„Senden" tippst. Diese Mail liest eine einzige Person (der Entwickler) und
gibt sie nicht an Dritte weiter, weder zu Werbe- noch zu sonstigen Zwecken.

## Kinder

SpeakEasy richtet sich nicht gezielt an Kinder unter 13 Jahren. Die App hat
weder ein Nutzerkonto noch eine Chat-Funktion noch einen Online-Bestandteil.

## Änderungen dieser Erklärung

Falls sich die Erklärung ändert, wird die neue Version unter derselben URL
mit aktualisiertem Datum oben veröffentlicht.

## Kontakt

Entwickler-E-Mail: speakeasy@skymail.eu
