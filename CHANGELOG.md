# Changelog

All notable project changes should be recorded here before a formal `1.0`
release.

## Unreleased

- Changed the project license model from the proposed MIT license to
  `AGPL-3.0-or-later`.
- Added required attribution notice: "Based on ZCam, original project by
  KubwojPrime."
- Added trademark and brand policy reserving the ZCam name, logos, icons, and
  visual brand identifiers.

## 0.2.13 - 2026-11-05

- Replaced the clip-local browser timeline with coarse and detailed controls
  directly below the recording player.
- Added automatic loading of finalized recordings and a five-row independently
  scrollable segment list with the active segment pinned first.
- Separated the persistent HTTP server from camera capture and recording, with
  remote camera controls in the Android client and browser panel.
- Added five-minute server recovery that restores the runtime with camera and
  recording disabled after an unexpected process loss.
- Added portable project skills for fixes, features, releases, reviews, device
  tests, and API/UI synchronization.

## 0.2.8 - 2026-05-30

- Fixed fullscreen preview scaling so H.264 and MJPEG previews preserve stream
  aspect ratio instead of stretching to fill the screen.
- Bumped Android version to `0.2.8` / `versionCode 10`.
- Published release APK as `zcam_0.2.8.apk`.

## 0.2.7 - 2026-05-15

- Improved recording history timeline and playback-oriented recording browsing.

## 0.2.6 - 2026-05-15

- Added event timeline and H.264 stability work.

## 0.2.5 - 2026-05-14

- Continued runtime, recording, and UI hardening.

## 0.2.4 - 2026-05-14

- Continued runtime and local preview iteration.

## 0.2.3 - 2026-05-13

- Added ultra-wide camera support and H.264 preview work.

## 0.2.2 - 2026-05-09

- Continued prototype runtime updates.

## 0.2.1 - 2026-05-09

- Continued prototype runtime updates.

## 0.2.0 - 2026-05-09

- First versioned MVP/prototype release line.
