---
name: zcam-feature
description: Design and implement a new ZCam capability across its Android, server, client, storage, and browser surfaces, then test and deliver it.
---

# ZCam Feature

Use this workflow for a new user-facing capability or a material extension of existing behavior.

## Workflow

1. Read the existing architecture and identify the owning module, public contracts, persisted state, recovery behavior, and security boundary.
2. Define observable acceptance criteria before editing. Include failure, background, restart, and low-connectivity behavior where relevant.
3. Prefer existing domain interfaces and dependency injection patterns. Add a new abstraction only when it represents a real cross-module contract.
4. Keep all product surfaces consistent:
   - Server endpoint and status payload.
   - Android client transport and UI.
   - Embedded browser panel when the capability is remotely controllable.
   - Persistence, watchdog, boot recovery, and recording behavior when runtime state changes.
5. Add focused unit or integration coverage for the contract and important state transitions.
6. Validate embedded JavaScript with `node --check`, then run `./gradlew test :app:assembleDebug`.
7. Update README and operational limitations. Do not claim Android can bypass Force stop, background camera restrictions, or OEM battery controls.

## Delivery

Review `git diff` and `git diff --check`. Stage only intended source and documentation files, excluding local artifacts and secrets. Commit with an imperative feature summary, push the current branch, and verify the remote commit. Report the APK path and SHA-256. If delivery cannot complete, preserve the local commit and identify the exact failed step.

