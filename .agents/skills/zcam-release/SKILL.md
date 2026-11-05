---
name: zcam-release
description: Prepare, sign, publish, and verify a ZCam GitHub release with version metadata, APK, checksum, commit, tag, and release notes.
---

# ZCam Release

Use this workflow only when the user asks to publish a version or GitHub release.

## Preconditions

- Inspect `git status`, recent tags, and GitHub releases before choosing the next version.
- Preserve unrelated work. Do not release a dirty tree unless every included change is intentional and explained.
- Require the ignored `keystore.properties` and configured private signing key. Never print or commit signing secrets.

## Release Workflow

1. Select the next semantic version consistent with existing tags. Update `versionCode`, `versionName`, README release version, and changelog when applicable.
2. Run `./gradlew test :app:assembleRelease` using an available JDK 17 or newer compatible with the build.
3. Verify the APK signature with Android `apksigner`; confirm the expected ZCam/KubwojPrime certificate.
4. Copy the signed APK to `release/zcam_<version>.apk` and generate `release/zcam_<version>.apk.sha256` without staging the release directory.
5. Review and commit all intended source and version changes. Push the branch before creating the release.
6. Create GitHub tag `v<version>` and a non-draft, non-prerelease release unless the user requests otherwise. Upload only the versioned APK and checksum.
7. Verify the tag and remote branch point to the release commit. Verify the GitHub asset digest equals the local APK SHA-256.

## Output

Report the commit, tag, release URL, direct APK URL, SHA-256, signature verification, and test result. A release is incomplete until remote assets and digests are verified.

