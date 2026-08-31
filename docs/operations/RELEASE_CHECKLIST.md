# Release Checklist

Use this checklist for a candidate that may be accepted for a Temi device.
Record evidence in the authorized release record. A checked box means the
operator observed the stated result for the candidate; it does not promote a
desktop or unit-test result to physical or AI6 end-to-end acceptance.

## Source

- [ ] The checkout is on the reviewed branch and based on the intended `main`.
- [ ] `git status --short --untracked-files=all` is clean after local-only
      files are excluded by the repository policy.
- [ ] The exact source `HEAD` and any required ancestor are recorded.
- [ ] `git diff --check`, `git diff --stat`, and the full diff review pass.
- [ ] The candidate changes only the intended files and contains no Android
      source, test, generated output, or private configuration changes.
- [ ] `versionCode` and `versionName` are deliberate and recorded. The accepted
      Android candidate is `com.robotemi.agent`, versionCode 6, versionName
      1.0.5; the V4E versionCode 5 / versionName 1.0.4 physical baseline is a
      historical pre-acceptance record, and V4H closed physical acceptance.

## Tests

- [ ] Run `:app:testDebugUnitTest` with the supported JDK 21 toolchain and
      record the actual test count and result. The accepted baseline was
      288/288; the V4E candidate record is 294/294.
- [ ] Run `:app:assembleDebug` and record the result.
- [ ] Run any release-specific tests required by the candidate, or record
      `NOT_APPLICABLE` with the reason.
- [ ] Confirm that unit-test and desktop build evidence is labeled as such and
      is not presented as physical Temi or AI6 acceptance.

## Demo

- [ ] The operator has authorization to use the project Demo signing identity.
- [ ] The ignored `signing.local.properties` file points to an authorized
      external keystore and contains no tracked or published private values.
- [ ] Run `:app:assembleDemo` with JDK 21 and record the result.
- [ ] `verifyDemoSigningConfig` reports `PASS` during the Demo build.
- [ ] Run `tools/verify_demo_artifact.ps1` against the intended signed APK from
      a clean checkout and record its pass output.
- [ ] Confirm package `com.robotemi.agent`.
- [ ] Confirm the candidate version code/name.
- [ ] Confirm the signer matches the public expected SHA-256:
      `4DA8461B45B02FADCB042F63151FEE05D56EBD5105EB721D7D62E30B88513A7F`.
- [ ] Confirm `debuggable=false` for the signed Demo artifact.
- [ ] Confirm the selected Demo BuildConfig has Media v1.1 enabled and an
      attach deadline of 10,000 ms.
- [ ] Record that the accepted Demo defaults keep resident identity, care
      reporting, and legacy global MQTT actions disabled unless a separately
      reviewed build deliberately changes them.

## Repository safety

- [ ] `local.properties` is not tracked or included in the candidate.
- [ ] `signing.local.properties` is not tracked or included in the candidate.
- [ ] The two owner-authorized exercise videos are the only binary media added;
      no unapproved media was added to the repository.
- [ ] No APK or AAB is committed unless a separate publication policy
      explicitly requires it.
- [ ] No password, credential, private endpoint, real Temi address, private
      WebSocket URL, AI6 host, or personal data is present.
- [ ] No unexpected large object or generated build output entered the commit.
- [ ] A final `git status --short --untracked-files=all` and `git diff --cached`
      review covers the exact staged scope.

## Physical upgrade, when required

- [ ] The operator has confirmed ownership of one exact Temi serial
      `<TEMI_IP>:<ADB_PORT>` and uses `adb -s <SERIAL>` for every target
      operation.
- [ ] The pre-install package, versionCode/versionName, signer, `userId`,
      `dataDir`, and `firstInstallTime` are recorded.
- [ ] The artifact signer matches the installed package signer.
- [ ] The built versionCode is greater than the installed versionCode.
- [ ] The operator uses exactly one normal `adb -s <SERIAL> install -r
      <SIGNED_DEMO_APK>` operation.
- [ ] The operator does not use `-d`, `--downgrade`, `uninstall`, or `pm clear`
      as a routine upgrade step.
- [ ] Post-install package identity and version are recorded.
- [ ] `userId`, `dataDir`, and `firstInstallTime` preservation is checked when
      the release gate requires data retention.
- [ ] The post-install signer and non-debuggable Demo provenance are checked.
- [ ] `MainActivity` starts and a bounded log capture shows no immediate fatal
      exception.
- [ ] Any broader Temi, MQTT, camera, voice, navigation, media, or external
      service acceptance is recorded separately with its actual evidence.

## Exercise media and top safe-area acceptance, when required

- [ ] Before coordinate testing, confirm that `MainActivity` is resumed and its
      window is focused. `StandbyActivity` can be the foreground owner, so
      check the current resumed/top/focused window during operator readiness;
      do not assume that it always automatically takes foreground.
- [ ] Confirm the centralized top-safe-area policy is
      `max(0.09 * windowHeight, 72dp)`, combined with system-bar and cutout
      insets, and record the resulting exercise-button bounds.
- [ ] Record one hand and one leg coordinate tap, with each local video
      visible, advancing, and completing naturally.
- [ ] Record a bounded foreground monitor and state whether any autonomous
      `StandbyActivity` takeover was observed.
- [ ] Keep local coordinate playback separate from full canonical broker/AI6
      or `VERIFIED_E2E` acceptance, and record the defect classification when
      the incident is closed.

## Publication

- [ ] The reviewed GitHub pull request is merged to `main` by the authorized
      owner before publication is called complete.
- [ ] Required GitHub CI is `PASS` for the merged commit.
- [ ] The release/tag decision is recorded as `POLICY_PENDING` until the
      project owner finalizes tag policy. A tag is not required by this
      checklist.
- [ ] The signed artifact checksum, source `HEAD`, signer fingerprint, build
      variant, and preflight output are recorded for provenance.
- [ ] The checksum record is not described as proof of bit-for-bit APK
      reproducibility across independent builds.

## Stop conditions

Stop and ask the project owner when the signer is wrong or unavailable, the
version is not forward, the target identity is uncertain, an owner-authorized
media resource is missing, a required CI result is not terminal, or a requested recovery would
require uninstalling or clearing data. The project has no finalized automatic
rollback or tag policy; do not invent one in the release record.
