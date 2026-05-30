# Security Policy

ZCam is a local-first camera/audio runtime. Security issues can affect live
video, live audio, recordings, pairing, tokens, and trusted device state.

## Supported Versions

Until `1.0`, only the latest public release is supported for security fixes.
Older `0.x` releases should be treated as test builds.

| Version | Supported |
| --- | --- |
| `0.2.8` | Yes |
| `< 0.2.8` | No |

## Reporting A Vulnerability

Prefer a private report through GitHub Security Advisories if available for the
repository. If that is not available, contact the maintainer through the GitHub
repository before publishing details.

Please include:

- Affected version or commit.
- Device and Android version.
- Network setup.
- Reproduction steps.
- Expected and actual behavior.
- Impact assessment.
- Any logs or proof-of-concept details needed to reproduce the issue.

Do not publish exploit details until the maintainer has had time to triage and
ship a fix.

## Security Boundaries

Expected protections:

- Local LAN/VPN access gating.
- Token authentication for runtime endpoints.
- PIN/challenge pairing flow.
- Trusted device checks.
- Token rotation and revocation.
- Bounded watchdog/recovery behavior.

Non-goals for the current pre-1.0 line:

- Public Internet exposure.
- Cloud relay operation.
- Multi-tenant authorization.
- Enterprise device management.
- End-to-end encrypted remote access.

## Known Pre-1.0 Hardening Items

- Replace debug release signing with a private production signing setup.
- Force first-run credential setup or generate per-install credentials.
- Review `usesCleartextTraffic=true` and document the LAN-only threat model.
- Decide whether release builds should enable shrinking/obfuscation.
- Perform endpoint-level auth regression tests before every release.
- Run a 24-hour stability test with active camera, audio, recording, and
  reconnect scenarios.

