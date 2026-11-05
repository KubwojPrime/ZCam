---
name: zcam-code-review
description: Review ZCam changes for defects, regressions, security issues, Android lifecycle risks, and missing tests without changing code unless requested.
---

# ZCam Code Review

Use this workflow for review, audit, pull-request review, or pre-release risk assessment.

## Review Priorities

1. Behavioral bugs and data-loss risks in recording, retention, MP4 finalization, playback ranges, and event indexing.
2. Android lifecycle restrictions: foreground service types, boot restore, alarms, camera and microphone permissions, Force stop, and OEM battery management.
3. Security boundaries: pairing, trusted-device authorization, token handling, LAN/VPN exposure, path traversal, and private media access.
4. Cross-surface drift between server endpoints, `LocalClient`, Android UI, and embedded browser JavaScript.
5. Recovery loops, races, stale desired state, watchdog conflicts, resource leaks, and thermal behavior.
6. Missing tests for new contracts and state transitions.

## Output

Lead with findings ordered by severity. Give exact file and line references, impact, and a concrete correction. Then list assumptions and residual test gaps. Keep summaries secondary. If no issues are found, say so explicitly.

Pure review is read-only and must not create an empty commit. If the user asks to apply fixes, follow `zcam-fix`, run validation, commit the fixes, push the current branch, and verify the remote commit.

