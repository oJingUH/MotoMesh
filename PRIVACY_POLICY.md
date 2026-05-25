# Privacy Policy

**Last updated: May 24, 2026**

## MotoMesh — Open-Source Group Voice Chat for Motorcycles

This Privacy Policy explains how MotoMesh handles your data. MotoMesh is designed to operate without any server infrastructure — voice data is transmitted directly between devices via Bluetooth Low Energy (BLE) or, optionally, through a user-provided TCP relay server.

### Data Collection

MotoMesh **does not collect, store, or transmit any personal data** to any server operated by the app developer.

### Permissions

The app requires the following Android permissions, each used exclusively for the stated purpose:

| Permission | Purpose |
|------------|---------|
| **RECORD_AUDIO** | Capturing your voice via the device microphone for real-time transmission to other riders |
| **INTERNET** | Optional cellular TCP relay transport (user-configurable; no data sent to developer servers) |
| **BLUETOOTH_CONNECT** | Connecting to RYLR993 LoRa radio modules via BLE |
| **BLUETOOTH_SCAN** | Discovering nearby RYLR993 modules |
| **POST_NOTIFICATIONS** | Showing persistent "MotoMesh active" notification (required by Android 13+ for foreground services) |
| **FOREGROUND_SERVICE_MICROPHONE** | Running microphone capture as a foreground service (Android 14+) |
| **MODIFY_AUDIO_SETTINGS** | Ducking music volume when voice is detected |

### How Voice Data Is Handled

- **Voice data exists only on your device and other riders' devices.**
- In **LoRa mode**: voice frames are transmitted over BLE to a RYLR993 radio module, then broadcast via radio frequency to other riders' modules. No internet connection is used.
- In **Cellular TCP mode**: voice frames are sent through a TCP relay server that **you** provide and control. The relay server forwards frames between connected riders. The developer does not operate any relay server.
- In **Loopback mode**: voice is encoded and decoded locally for testing. No data leaves your device.

### Third-Party Services

MotoMesh uses no third-party analytics, crash reporting, advertising, or tracking SDKs. The sole third-party library is [Concentus](https://github.com/lostromb/concentus), a pure-Java Opus codec implementation (BSD license).

### Data Retention

No voice data is stored on the device beyond the duration of the current session. When you close the app, all audio buffers are released. No recordings are made.

### Children's Privacy

MotoMesh is not directed at children under 13. The app does not collect any personal information from any user, regardless of age.

### Changes to This Policy

If this policy changes, the updated version will be posted here. Since MotoMesh is open-source (MIT license), you can also track code changes in the public repository.

### Contact

For questions about this privacy policy, open an issue at:
https://github.com/oJingUH/MotoMesh

---

*This is a static document stored in the app repository.*