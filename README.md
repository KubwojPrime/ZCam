# ZCam

ZCam is a local-first Android camera and audio monitoring app designed for
long-running LAN/VPN operation without a cloud backend.

Current public release: `0.2.11`.

Project status: pre-1.0 hardening. The app already contains the core runtime
architecture, local HTTP API, pairing, recording, watchdog/recovery, and client
mode. Before `1.0`, security defaults, final QA, and store/user documentation
must be completed.

## What ZCam Does

- Runs an Android device as a local camera/audio server.
- Streams live preview over LAN/VPN.
- Supports H.264 preview with MJPEG fallback.
- Provides push-to-talk and live audio transport.
- Records video in looped MP4 segments with retention limits.
- Exposes a local HTTP/WebSocket API and fallback web panel.
- Supports QR pairing, PIN/token authentication, and trusted devices.
- Monitors runtime health and performs bounded recovery.
- Can restore desired runtime state after process restart or device reboot.

ZCam is intended for networks controlled by the user. It does not depend on an
external backend and should not be exposed directly to the public Internet.

## Stack

- Kotlin
- Jetpack Compose
- CameraX
- Foreground Service
- NanoHTTPD / NanoWSD
- OkHttp
- DataStore
- Hilt
- Timber
- JUnit

## Repository Layout

- `app` - Android application entry point, manifest, `MainActivity`, boot
  receiver, runtime restore policy, app-level ViewModel.
- `core` - domain contracts, runtime settings models, feature flags,
  dispatchers, logging, power status, and common DI.
- `camera` - CameraX runtime, H.264 preview encoder, MJPEG snapshot source,
  camera controls, lens detection, event detection, and segment recording
  pipeline.
- `server` - local HTTP/WebSocket server, status endpoints, video endpoints,
  pairing/token endpoints, audio sockets, recordings API, and web panel.
- `audio` - push-to-talk runtime, live audio capture/playback, cue playback,
  volume control, and audio command models.
- `storage` - loop recording manager, recording catalog/index, event store,
  retention policy, and recording lookup/download support.
- `watchdog` - heartbeat tracking, stale component detection, and recovery
  request emission.
- `security` - LAN/VPN access policy, PIN/token validation, trusted devices,
  pairing sessions, token rotation/revocation, and token persistence.
- `client` - local HTTP client, WebSocket audio transport, preview URLs,
  recording download client, and pairing client models.
- `service` - foreground runtime service, runtime coordinator, health model,
  recovery policy, environment monitor, and component orchestration.
- `data` - DataStore repositories for runtime settings, desired runtime state,
  crash state, trusted device serialization, and client session state.
- `ui` - Compose UI, UI state/action models, preview surfaces, recording
  playback/timeline panels, pairing screens, and settings screens.
- `ZCam_Project_Workflow` - product and engineering planning documents.
- `release` - local APK output directory. Release APKs are uploaded to GitHub
  Releases and are not source-controlled.

## Runtime Flow

1. The UI starts `ZCamForegroundService`.
2. The service starts `ZCamRuntimeCoordinator`.
3. The coordinator loads `RuntimeSettings` from DataStore.
4. Runtime components start in order: watchdog, camera, server, audio, storage.
5. The watchdog receives heartbeats and emits recovery requests for stale
   components.
6. The coordinator applies bounded recovery with mutexes, exponential backoff,
   and cooldown.
7. The local server exposes status, preview, audio, security, and recording
   endpoints.

## Local API

The server is LAN/VPN-gated and token-protected except for explicit public
pairing/health flows.

Main endpoints:

- `GET /`
- `GET /health`
- `GET /video`
- `GET /mjpeg`
- `GET /snapshot.jpg`
- `GET /api/status`
- `POST /api/camera/torch`
- `POST /api/camera/night-mode`
- `POST /api/camera/zoom`
- `POST /api/audio/live`
- `POST /api/audio/play`
- `POST /api/audio/volume`
- `GET /api/recordings`
- `GET /api/recordings/events`
- `GET /api/recordings/events/{epochMs}.jpg`
- `POST /api/runtime/restart`
- `GET /api/recordings/{fileName}`
- `GET /api/security/pair/qr`
- `POST /api/security/pair/request`
- `POST /api/security/pair/complete`
- `POST /api/security/pair`
- `POST /api/security/token/rotate`
- `POST /api/security/token/revoke`
- `WS /ws/audio`
- `WS /ws/preview`

## Client Event Alerts And Restart

In Client mode, enable Motion alerts after pairing to run a foreground LAN/VPN
monitor. The monitor checks for new events every five seconds and shows an
Android notification with the JPEG frame captured at detection time. Event
frames stay in private app storage on the server, are served only through the
authenticated API, and are capped at 200 retained frames. Notifications depend
on network reachability, notification permission, and Android allowing the
client monitor service to run.

Event detection uses an adaptive low-resolution background model and spatial
motion regions instead of whole-frame brightness sampling. Localized movement
is reported as `object_motion`, while global scene displacement, lens covering,
or a sudden loss of scene detail is reported as `camera_tamper`. Existing
event index entries remain readable and are treated as object motion.
Repeated detections of the same type separated by no more than 30 seconds are
stored and returned as one timed event with start, end, duration, peak
confidence, and one representative frame. Existing point events are coalesced
when read, so older indexes do not need a migration step.

The client can request a restart of a reachable server through the authenticated
API. The server acknowledges the request, then restarts its runtime. If Android
has killed the entire server process, no LAN listener remains to receive a
request; the client reports that the server is unreachable. This local-only
mode cannot guarantee remote wake after a full process kill or force-stop.

## Recording Playback

Client playback uses Media3 and streams finalized MP4 segments directly from
the authenticated server endpoint with HTTP byte ranges. Loaded segments form
one playlist, so the global time slider can seek between recordings without
downloading complete files first. The separate Download command still saves a
full copy to the client device. Seeking over a slow link can briefly buffer,
but it requests only the required media range and keeps the timeline available.
The browser panel uses the same range-enabled endpoints, typed event colors,
timed event bars, global seek control, and automatic transition to the next
recording segment.

CameraX resolution selection follows the configured aspect ratio and requests
the closest supported camera mode for preview, analysis, and recording. The
client renders previews with preserved aspect ratio instead of cropping or
stretching them. Recording rotation is derived from Android's inverse sensor-to-
display mapping at every segment start, preventing opposite landscape mounting
from producing upside-down files.

## Security Model

ZCam uses a local-only security model:

- LAN/VPN access policy rejects non-local clients before command handling.
- Runtime endpoints require API token authentication.
- Pairing requires a PIN and short-lived challenge/request flow.
- Trusted devices are stored locally and validated by device ID.
- Tokens can be rotated and revoked.
- The security manager runs a sanity check after restart to repair inconsistent
  token state.

Important pre-1.0 security work is tracked in
[`docs/1.0_READINESS.md`](docs/1.0_READINESS.md).

## Privacy Model

ZCam is designed to operate without cloud services. Video, audio, runtime
settings, trusted devices, and logs are stored locally on the Android device
unless the user explicitly connects a client or downloads recordings over the
local network.

See [`PRIVACY.md`](PRIVACY.md) for the user-facing privacy statement.

## Android Permissions

ZCam requests permissions needed for its runtime role:

- Camera preview and recording.
- Microphone capture and audio transport.
- Local network communication.
- Foreground service operation.
- Wake lock for long-running runtime.
- Notification permission for the foreground service.
- Media/storage permissions for recordings on Android versions that require
  them.
- Boot completed permission for optional runtime restore after reboot.

## Default Runtime Settings

The defaults are centralized in `RuntimeSettingsDefaults`:

- Server port: `8080`
- Preview/recording baseline: `1280x720`
- FPS: `15`
- Codec: `H.264`
- Preview profile: balanced H.264
- Recording segment length: `5` minutes
- Max recording storage: `32 GB`
- Minimum free storage floor: `5 GB`

Settings are validated by `RuntimeSettingsValidator` before persistence.

## Build

Prerequisites:

- Android Studio or Android SDK command-line tools.
- JDK 17.
- Android SDK for `compileSdk = 35`.

Useful commands:

```powershell
.\gradlew.bat :ui:compileDebugKotlin
.\gradlew.bat test
.\gradlew.bat :app:assembleRelease
```

Release builds use a dedicated private signing key. Copy
`keystore.properties.example` to the ignored `keystore.properties` file and
provide the local key path, alias, and passwords before running
`:app:assembleRelease`. Never commit either file containing secrets or the
keystore itself.

## Tests

Focused test areas:

- Domain/use-case contracts.
- Runtime settings validation.
- Feature flag guard.
- Trusted device codec.
- Recovery policy and coordinator recovery behavior.
- Watchdog stale component recovery.
- Security policy and negative auth paths.
- Server security/audio integration endpoints.
- Storage retention and index recovery.

Run all available unit tests with:

```powershell
.\gradlew.bat test
```

## Release Process

1. Confirm `main` is clean except intended changes.
2. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
3. Run tests and release build.
4. Generate APK checksum.
5. Commit source changes.
6. Push `main`.
7. Create a GitHub Release with the APK asset and checksum.
8. Verify the uploaded asset digest.

The detailed 1.0 gate is in [`docs/1.0_READINESS.md`](docs/1.0_READINESS.md).

## License

ZCam source code is licensed under the GNU Affero General Public License
version 3 or later (`AGPL-3.0-or-later`). See [`LICENSE`](LICENSE).

Forks and modified versions must preserve the attribution notice in
[`NOTICE`](NOTICE):

> Based on ZCam, original project by KubwojPrime.

The ZCam name, logos, icons, and visual brand identifiers are not licensed
under the AGPL. See [`TRADEMARKS.md`](TRADEMARKS.md).

This model is intended to keep the code auditable and user-protective while
requiring public forks and network-accessible modified versions to provide
corresponding source code under the same license. Final legal approval remains
the maintainer's responsibility.

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md).
