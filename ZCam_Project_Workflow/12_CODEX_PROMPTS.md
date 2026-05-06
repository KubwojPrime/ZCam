# 12_CODEX_PROMPTS.md

## START PROMPT

Build an Android app named ZCam.

Requirements:
- Kotlin
- Jetpack Compose
- CameraX
- Foreground Service
- Embedded local HTTP server
- MJPEG streaming
- Loop recording
- Push-to-talk audio
- LAN only
- No cloud
- No external backend

Architecture requirements:
- modular design
- separation of concerns
- watchdog/recovery architecture
- production-ready logging
- no UI-thread blocking
- no temporary fixes

The application must support:
- Server mode
- Client mode
- QR pairing
- Local web panel
- Loop recording with auto cleanup
- Thermal protection
- Automatic recovery after failures

Prioritize:
1. Stability
2. Simplicity
3. Long-running reliability
4. Maintainability
5. Performance