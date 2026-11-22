# OpenCPN GPS Server Free

Use your Android phone as the GPS for OpenCPN on your laptop, over Wi-Fi. No USB GPS puck needed.

The phone sends its position to the laptop. OpenCPN shows your ship on the chart as if a GPS were plugged in.
The phone shows no chart; it only passes the position to the laptop.

**Backup and practice use only.** This is not a certified navigation input and not a type-approved ECDIS
sensor. Never use it as your only way of fixing the ship's position.

Free version: everything works, but each session lasts 2 hours and you can start another one any time.
[OpenCPN GPS Server Pro](#pro-version) runs without a time limit.

Made by **[Trozovka](https://github.com/Trozovka) (John Winsome Munar)**, Master Mariner.

Keywords: OpenCPN, GPS, NMEA 0183, Android, phone as GPS, Wi-Fi GPS, TCP, at sea, laptop navigation, backup GPS.

## Screenshots

| Idle | Running, connected | Settings / About |
|---|---|---|
| ![Idle](gumroad-assets/01_free_main_stopped.png) | ![Running](gumroad-assets/02_free_main_running.png) | ![Settings](gumroad-assets/03_free_settings_about.png) |

## What it does

- Sends your position, speed and course to OpenCPN once per second.
- Keeps working with the phone screen locked (after you allow the battery exemption).
- Shows GPS status, position, speed, heading and how many messages were sent.
- Works on Wi-Fi, phone hotspot or USB tether, and follows a new IP address by itself.
- Several laptops can use the same phone at once.
- Metric or imperial display.

## Install (no building needed)

1. Download the APK from **[GitHub Releases](https://github.com/Trozovka/opencpn-gps-server-free/releases)**
   or **[Gumroad](https://trozovka.gumroad.com/l/opencpn-gps-server-free)** (free).
2. Open the file on the phone. Android asks you to allow installs from this source the first time. That is
   normal, because the app is not on Google Play.

## How to use it with OpenCPN

1. Open the app. Allow the location permissions, and allow the battery exemption when asked. This keeps it
   running with the screen off.
2. Tap **Start**. Note the phone's IP address shown in the app.
3. On the laptop, in OpenCPN: **Options -> Connections -> Add Connection**.
   Type `Network`, Protocol `TCP`, Address = the phone's IP, DataPort `10110` (or the port set in the app's Settings).
   Tick **Receive Input**. Leave Output unticked.
4. Within a few seconds OpenCPN shows a live GPS fix.

The phone and laptop must be on the same network (ship Wi-Fi, or the laptop on the phone's hotspot).

## Pro version

**OpenCPN GPS Server Pro** has no time limit: run it for the whole passage.
Price and download: **[trozovka.gumroad.com/l/opencpn-gps-server-pro](https://trozovka.gumroad.com/l/opencpn-gps-server-pro)**.
The Pro source is private. This repo has the full source of the free version under the MIT license.

---

## For developers

The phone reads its own location and sends `$GPGGA`/`$GPRMC`/`$GPVTG`/`$GPGSA` sentences (1 Hz, correct
checksums) from a TCP server socket. OpenCPN connects as a Network -> TCP client.

### Technical details

- Positions from the satellite receiver only (`LocationManager` GPS provider), never from Wi-Fi or mobile networks.
- Android foreground service with a partial wake lock; survives Doze once the battery-optimization exemption is granted.
- Server listens on all interfaces; multi-client (each client handled separately); unresponsive clients dropped after about 15 s.
- Telemetry includes fix-to-wire latency (red above 500 ms).
- NMEA output always uses NMEA-native units, whatever the display setting (that is what OpenCPN expects).
- Free sessions stop after 2 hours (counted from the first position) with an upgrade prompt.

### Build from source

Requires JDK 17 and the Android SDK (the Gradle wrapper handles the rest).

```
git clone https://github.com/Trozovka/opencpn-gps-server-free.git
cd opencpn-gps-server-free
echo "sdk.dir=/path/to/your/android-sdk" > local.properties
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Tech stack

- Kotlin + Jetpack Compose, MVVM.
- Gradle multi-module: `:core` (service, NMEA formatting, UI, shared with Pro) + `:app` (thin Free launcher).
  Internal package names still say `lite`; they are kept so existing installs keep updating.
- minSdk 26, target latest stable Android API.
- Not a fork. Existing GPS-relay apps (e.g. [gpsdRelay](https://github.com/project-kaat/gpsdRelay)) were looked
  at, but they connect to a gpsd server instead of hosting a TCP server, so nothing was reused.

### Why background location

The server must keep sending fixes while the phone screen is locked at the helm. That needs
`ACCESS_BACKGROUND_LOCATION`, a foreground service and a partial wake lock while the server runs.

## License

MIT - see [LICENSE](LICENSE) for the full text.

Copyright (c) 2026 Trozovka (John Winsome Munar). Original Author: Trozovka (John Winsome Munar). All derivative works must retain the [NOTICE](NOTICE) file's attribution. Not a fork of, or derived from, any other project's source.
