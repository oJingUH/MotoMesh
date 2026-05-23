# CHANGELOG

All notable changes to MotoMesh will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/)
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Project scaffold: Opus codec layer, BLE RYLR993 driver, mesh forwarder, jitter buffer, node table
- Audio mixer interface with music ducking start signal
- MotoMeshService foreground runner with wake lock + notification channel
- README, CONTRIBUTING, CHANGELOG; MIT license
- AndroidManifest with all permissions (RECORD_AUDIO, BLUETOOTH_CONNECT, CAPTURE_AUDIO_OUTPUT, FOREGROUND_SERVICE)
- build.gradle.kts with Concentus Opus dependency

### In Progress
- Phase 1: Opus audio loopback validation
- Phase 2: Single LoRa BLE link (2-device test)

### Known Limitations
- RYLR993 BLE pairing: passkey must be set to 123456 on the module before pairing in Android
- CAPTURE_AUDIO_OUTPUT requires system permission on some OEM skins — user grants manually
- No_PLC — actual Opus PLC call is as "silence" rather than comfort noise, it still sounds harsh
- Native mixer .so not yet built — audio is a stub; patch pending

---
MIT License — see LICENSE
