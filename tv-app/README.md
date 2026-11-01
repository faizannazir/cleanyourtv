# CleanYourTV — Standalone Android TV App

`tv-app` is a standalone, native Android TV application for CleanYourTV. It operates directly on compatible Android TV and Google TV devices (such as TCL Smart TV Pro) without requiring an external computer or server.

## Features
- **Remote D-Pad Navigation**: TV-first dark UI designed for 10ft TV viewing distance.
- **Local ADB Protocol Integration**: Pure Kotlin ADB protocol implementation connecting to local device ADB daemon over loopback/network.
- **Safety Engine**: Hard-locks Google Assistant, YouTube, Play Services, Settings, active launcher, and network/system UI components.
- **Explicit, Non-Destructive Cleanup**: Disables only the optional, unprotected packages the person ticks. It never deletes app data, cache, preferences, databases or other application data.
- **Target-Bound Backup & State Restore**: Captures the original enabled/disabled state and every AppOps mode before cleanup and stores an atomic, fsynced baseline in private app storage. Each backup is a versioned envelope that records the target host and port. Cleanup and restore refuse a backup captured from another host or port, a corrupt or unreadable file, or a legacy file that has not been explicitly adopted; an unreadable file is preserved rather than treated as missing. The first baseline stays immutable across retries. Restore puts only those package and AppOps states back; it cannot and does not restore deleted app data. Host/port binding is **not** cryptographic physical-device identity: it cannot tell two TVs apart if a DHCP server later gives the same address and port to another device, so confirm the target and, where possible, its model before restoring.
- **Local Diagnostic JSON Export**: Exports package state and scan summaries without transmitting data off-device.
- **Guarded Launcher Handoff**: Installs a packaged launcher through the package-service session protocol, verifies that the package is listed, starts it, and confirms Home resolves to it before disabling only the stock launcher that owned Home. When no launcher APK is packaged, the step reports that unsupported state instead of claiming an install. Its ADB responses must end with the remote `CLSE`; interrupted commands are reported as uncertain and are never replayed automatically.

The ADB transport validates every frame (magic, command, payload length and unsigned checksum), never runs a TV command locally, and closes a connection whose stream state is unknown.

## Developer Instructions
- Build debug APK: `cd tv-app && ./gradlew assembleDebug`
- Run unit tests: `cd tv-app && ./gradlew test`
