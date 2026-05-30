# Privacy

ZCam is designed as a local-first Android application. It does not require a
cloud account, external backend, analytics service, or remote telemetry service
to operate.

## Data Processed By The App

Depending on enabled features, ZCam may process:

- Live camera frames.
- Recorded video segments.
- Microphone audio for push-to-talk and live audio.
- Playback/cue audio commands.
- Runtime settings.
- Trusted device records.
- Pairing challenges and API tokens.
- Runtime health and diagnostic logs.

## Where Data Is Stored

Data is stored locally on the Android device unless the user explicitly accesses
it from another local client:

- Runtime settings are stored in Android DataStore.
- Trusted devices and token metadata are stored locally.
- Recordings are stored under the app's external files area.
- Recording indexes and event indexes are stored locally next to recordings.
- Runtime logs are local diagnostic artifacts.

## Network Behavior

ZCam exposes a local HTTP/WebSocket server for LAN/VPN clients. The app is not
intended to be exposed directly to the public Internet.

Network clients may receive:

- Live preview frames.
- Live audio frames.
- Runtime status.
- Pairing payloads.
- Recording metadata.
- Downloaded recording files.

## Third-Party Services

The app does not intentionally send video, audio, settings, tokens, or logs to a
third-party service. Any external transfer depends on user-controlled network
access, user-installed VPNs, Android system behavior, or manual sharing outside
the app.

## User Responsibilities

Users are responsible for:

- Running ZCam only on trusted local networks or private VPNs.
- Changing default credentials before real use.
- Controlling who can access the local network.
- Complying with local privacy, recording, and surveillance laws.
- Protecting exported APKs, logs, and downloaded recordings.

## Pre-1.0 Privacy Gate

Before a formal `1.0` release, the project should complete a user-facing privacy
review for app store metadata, Android permission explanations, default
credential setup, and recording retention behavior.

