---
name: zcam-device-test
description: Build and exercise ZCam on an Android device or reachable test server, collecting reproducible evidence and delivering any fixes found.
---

# ZCam Device Test

Use this workflow for APK testing, physical-device validation, LAN/VPN API checks, or server-panel smoke tests.

## Workflow

1. Record the branch, version, expected device role, server address, and available connection method. Never expose pairing tokens in the final response.
2. Run focused unit tests and build `:app:assembleDebug` before installation.
3. Prefer ADB only when the device is already authorized. Do not repeatedly retry OEM-blocked installation flows; fall back to a local APK or GitHub release when requested.
4. Validate the highest-risk paths relevant to the change: process restart, screen-off operation, camera toggle, recording finalization, HTTP ranges, timeline seeking, segment transition, orientation, and client notifications.
5. For a reachable server, capture status and response semantics without modifying or deleting user recordings. Use small byte ranges for media checks.
6. If a defect is found, reproduce it deterministically, apply the focused fix, rerun the affected scenario, then run `./gradlew test :app:assembleDebug`.

## Delivery

Report tested device conditions, passed scenarios, untested physical conditions, APK path, and SHA-256. If code or documentation changed, create a focused commit, push the current branch, and verify the remote commit. A no-change test run does not require an empty commit.

