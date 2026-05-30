# Contributing

ZCam is being prepared for a formal `1.0` release. Contributions should preserve
the local-first, long-running, and security-conscious design of the project.

## License Of Contributions

By contributing to ZCam, you agree that your contribution is licensed under the
same license as the project: GNU Affero General Public License version 3 or
later (`AGPL-3.0-or-later`).

Contributions must preserve the project attribution notice:

> Based on ZCam, original project by KubwojPrime.

The `ZCam` name, logos, icons, and visual brand identifiers remain reserved by
KubwojPrime. See `NOTICE` and `TRADEMARKS.md`.

## Development Setup

Required:

- JDK 17.
- Android SDK with API 35.
- Android Studio or Gradle command-line tooling.

Useful commands:

```powershell
.\gradlew.bat :ui:compileDebugKotlin
.\gradlew.bat test
.\gradlew.bat :app:assembleRelease
```

## Engineering Rules

- Keep runtime work off the UI thread.
- Prefer existing module boundaries over new cross-module shortcuts.
- Keep domain contracts in `core`.
- Keep Android persistence in `data` or focused runtime modules.
- Keep network API handling in `server` and client calls in `client`.
- Keep security-sensitive behavior explicit and covered by tests.
- Avoid silent recovery loops; use bounded retry/backoff/cooldown.
- Do not introduce cloud dependencies for core runtime behavior.
- Do not commit generated APKs, local SDK files, keystores, or Gradle caches.

## Code Style

- Kotlin official style.
- Small, focused changes.
- Clear names over broad abstractions.
- Comments only where they clarify non-obvious behavior.
- No heavy work on Compose recomposition paths.

## Testing Expectations

For narrow UI changes:

- Compile the affected module.

For runtime, storage, security, or API changes:

- Add or update focused unit/integration tests.
- Run the affected module tests.
- Run `.\gradlew.bat test` when the blast radius crosses modules.

For release candidates:

- Run release build.
- Verify APK metadata.
- Verify checksum.
- Verify GitHub Release asset upload.

## Pull Request Checklist

- Change is scoped and described.
- Relevant tests pass.
- Security/privacy impact is considered.
- README or docs are updated when behavior changes.
- No generated binaries or local environment files are committed.
