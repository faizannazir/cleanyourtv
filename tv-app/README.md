# CleanYourTV — Standalone Android TV App

`tv-app` is a standalone, native Android TV application for CleanYourTV. It operates directly on compatible Android TV and Google TV devices (such as TCL Smart TV Pro) without requiring an external computer or server.

## Features
- **Remote D-Pad Navigation**: TV-first dark UI designed for 10ft TV viewing distance.
- **Local ADB Protocol Integration**: Pure Kotlin ADB protocol implementation connecting to local device ADB daemon over loopback/network.
- **Safety Engine**: Hard-locks Google Assistant, YouTube, Play Services, Settings, active launcher, and network/system UI components.
- **Explicit, Non-Destructive Cleanup**: Disables only the optional, unprotected packages the person ticks. It never deletes app data, cache, preferences, databases or other application data.
- **Baseline Backup & State Restore**: Captures the original enabled/disabled state and every AppOps mode before cleanup and stores an atomic, fsynced baseline in private app storage. Restore puts only those package and AppOps states back; it cannot and does not restore deleted app data.
- **Local Diagnostic JSON Export**: Exports package state and scan summaries without transmitting data off-device.

## Developer Instructions
- Build debug APK: `cd tv-app && ./gradlew assembleDebug`
- Run unit tests: `cd tv-app && ./gradlew test`
