# Contributing to MotoMesh

Thanks for your interest! MotoMesh is an open-source project, and contributions of all kinds are welcome — code, docs, hardware testing, bug reports.

## Quick Start

```bash
git clone https://github.com/oJingUH/MotoMesh.git
cd MotoMesh
./gradlew assembleDebug
```

Install on device:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Run tests:
```bash
./gradlew testDebugUnitTest
```

## Development Environment

- Android Studio Hedgehog or later
- JDK 17+ (Android Studio JBR works)
- Android SDK API 35
- Physical Android device with USB debugging (emulator won't do BLE)

## Project Structure

| Path | Description |
|------|-------------|
| `app/src/main/java/com/motomesh/audio/` | Opus codec, audio pipeline, ducking controller, jitter buffer |
| `app/src/main/java/com/motomesh/mesh/` | Mesh engine, forwarder, node table |
| `app/src/main/java/com/motomesh/lora/` | LoRaDriver, RYLR993 BLE GATT driver |
| `app/src/main/java/com/motomesh/cellular/` | CellularBridge TCP relay transport |
| `app/src/main/java/com/motomesh/service/` | MotoMeshService foreground service |
| `app/src/main/java/com/motomesh/ui/` | MainActivity, SettingsActivity, NodeAdapter |
| `app/src/test/` | Unit tests (MeshForwarder, NodeTable) |

## How to Contribute

### Reporting Bugs

Open a [GitHub Issue](https://github.com/oJingUH/MotoMesh/issues/new?template=bug_report.md) with:

- Android version and device model
- What you were doing when it broke
- Full logcat output: `adb logcat -d -s MotoMesh:* > crash.log`

### Feature Requests

Open a [GitHub Issue](https://github.com/oJingUH/MotoMesh/issues/new?template=feature_request.md) describing the use case and why it matters for group rides.

### Code Changes

1. Fork the repo
2. Create a feature branch (`git checkout -b feat/your-thing`)
3. Make changes
4. Run tests (`./gradlew testDebugUnitTest`)
5. Build the APK (`./gradlew assembleDebug`)
6. Push and open a Pull Request

### Hardware Testing

The biggest bottleneck is LoRa hardware. If you have a RYLR993 module (or any compatible LoRa BLE module) and want to help:

1. Build the app
2. Pair the module in Android Bluetooth settings (passkey: `123456`)
3. Connect in the app
4. Report: range, latency, packet loss, battery life

See the [README](./README.md) for the full hardware BOM and wiring diagram.

## Code Style

- Kotlin, no semicolons
- 4-space indentation
- `object` for singletons, constructor injection for testable classes
- Coroutines + StateFlow for async, no LiveData
- Document public API with KDoc comments

## License

By contributing, you agree that your contributions will be licensed under the [MIT License](./LICENSE).