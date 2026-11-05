---
name: zcam-api-ui-sync
description: Keep ZCam server APIs, Android client models, app controls, and embedded browser panel synchronized when remote behavior changes.
---

# ZCam API And UI Sync

Use this workflow whenever an endpoint, status field, remote command, recording model, or server-side behavior changes.

## Contract Checklist

- Update routing, method validation, authentication behavior, response codes, and JSON payloads in `server`.
- Update `LocalClient`, `OkHttpLocalClient`, status models, and parsing defaults in `client`.
- Update actions, state, error handling, and controls in `app` and `ui`.
- Update the embedded panel HTML, CSS, and JavaScript in `ZCamHttpServer.kt`.
- Keep desired state, service commands, watchdog behavior, boot recovery, and storage lifecycle aligned with the command.
- Add server integration coverage for valid and invalid requests plus a state-transition test when runtime behavior changes.

## Validation

Extract the embedded `<script>` and run `node --check`. Run focused server/client/service tests, then `./gradlew test :app:assembleDebug`. Exercise the endpoint against a reachable test server when available, without using destructive recording operations.

## Delivery

Update README endpoint and behavior documentation. Review `git diff --check`, commit all synchronized surfaces together, push the current branch, and verify the remote commit. Report any surface intentionally left unchanged and why.

