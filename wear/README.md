# LocalSend for Wear OS (unofficial, experimental)

A native Kotlin / Compose for Wear OS app that speaks the LocalSend **v2.2** protocol
(see `packages/core/src/http/` and `packages/core/src/multicast/` for the reference).
It is independent of the Flutter app: open the `wear/` folder in Android Studio.

## Status: UNTESTED
This was written without an Android SDK, so it has **never been compiled or run**.
Expect compile errors to fix on first build (dependency versions, a few API names).

## What works (by design, not verified)
- Multicast discovery + subnet-scan fallback, answering announcements via HTTP register
- Receive text messages and files up to 20 MB (saved to the app's external files dir), with an Accept/Decline prompt
- Send text (typed/voice via RemoteInput, or presets) to HTTP and HTTPS peers (uses a keystore-generated client certificate, as HTTPS peers require mTLS)

## Known limitations
- Receiving only works while the app is open (no foreground service yet)
- Receiver is plain HTTP (announces `protocol: http`), not HTTPS
- No fingerprint pinning when sending; no PIN support; no file sending; no web-send
- Multicast on watches over Bluetooth-tethered Wi-Fi may not work; the scan fallback needs real Wi-Fi

## Build
Open `wear/` in Android Studio (it will create the Gradle wrapper), pick a Wear OS emulator or watch, run `app`.
