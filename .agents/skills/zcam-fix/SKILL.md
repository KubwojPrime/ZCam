---
name: zcam-fix
description: Diagnose and fix a ZCam defect or regression, including reproduction, focused tests, validation, and delivery to the current Git branch.
---

# ZCam Fix

Use this workflow for a reported bug, regression, crash, incorrect UI behavior, or reliability problem.

## Workflow

1. Inspect the current branch, working tree, relevant modules, and recent commits. Preserve unrelated user changes.
2. Reproduce the failure with the narrowest useful test, API call, log evidence, or deterministic code path. State when physical-device reproduction is unavailable.
3. Trace the behavior across module boundaries before editing. ZCam commonly spans `app`, `service`, `server`, `client`, `camera`, `storage`, and `ui`.
4. Implement the smallest complete correction. Update every affected surface, including the Android client and embedded browser panel when their contract changes.
5. Add a regression test at the lowest stable layer. For embedded panel JavaScript, also extract the script and run `node --check`.
6. Run focused tests first, then `./gradlew test :app:assembleDebug`. Verify the APK exists and report its SHA-256.
7. Update README or operational documentation only when behavior, setup, permissions, or limitations changed.

## Delivery

Review `git diff` and `git diff --check`. Stage only intended files; never stage the local `release/` directory wholesale. Create a concise bug-fix commit and push the current branch to its configured remote. Verify the remote branch points at the new commit. If push is impossible, stop with the local commit intact and report the exact blocker.

