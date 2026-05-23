# Contributing to MotoMesh

Thank you for your interest in contributing. This project is open-source
because the group-ride communication problem is better solved together.

## How to Help

- Report bugs via GitHub Issues (include device model, Android version, RYLR993 firmware version)
- Submit PRs — see the branch layout below
- Improve hardware documentation (more module types, power wiring)
- Add frequency-plan support for non-EU/US regions

## Branch Layout

| Branch | Purpose |
|--------|---------|
| `main` | Stable, passing CI, ready to build |
| `dev` | Active development: merge here, PRs target here |
| `v0.x` | Release tags when stable |

## Build & Test

```bash
# Unit tests (JUnit 4, runs on host JVM)
./gradlew test

# Instrumented tests (require connected device with BLE enabled)
./gradlew connectedAndroidTest

# Build debug APK
./gradlew assembleDebug

# Build signed release APK
./gradlew assembleRelease
```

## Code Style

- Kotlin (target 1.9, JVM 17)
- No trailing whitespace
- Functions over comments: write self-documenting code
- If a doc-block is longer than 4 lines, the function needs refactoring
- Architecture: audio/, lora/, mesh/, service/, ui/ — keep concerns separated

## Code of Conduct

Be respectful. This is about safer group rides, not internet argument sport.

---

Built with 🏍️ and bad Wi-Fi coverage. MIT licensed.
