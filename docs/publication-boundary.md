# Android publication boundary

## Intended publication set

The standalone Android publication consists of:

- app/ source, tests, and source resources. Optional private exercise videos
  are excluded.
- gradle/ and the Gradle Wrapper.
- Root Android build configuration and gradle.properties.
- README.md, AGENTS.md, docs/, and tools/.
- Non-secret configuration templates such as local.properties.example and
  signing.local.properties.example.

The Android Gradle build consumes the Temi SDK as the external Maven
dependency com.robotemi:sdk:1.134.1. It does not require a parent SDK checkout
or a parent settings/build file.

## Explicit exclusions

The current working tree also contains hermes_temi_bridge/. It is a separate
historical reference service, not Android source, not a Gradle dependency, and
not part of the intended standalone Android repository. It remains untouched
because backend and Bridge work are outside this cleanup. Exclude it before
exporting or creating the standalone repository.

The following are always local-only or generated:

- local.properties and signing.local.properties.
- app/src/main/res/raw/elderly_hand_exercise.mp4 and
  app/src/main/res/raw/elderly_leg_exercise.mp4.
- Keystores, certificates, passwords, aliases, and SDK paths.
- .gradle/, build/, APK/AAB files, captures, recordings, logs, and reports.

## Documentation classification

- README.md: PUBLIC_DOCUMENTATION.
- AGENTS.md: DEVELOPER_DOCUMENTATION, generalized for publication.
- docs/performance/yuv-copy-optimization-2026-08-09.md:
  PUBLIC_DOCUMENTATION with historical machine-specific details removed.
- hermes_temi_bridge/: PRIVATE_OPERATIONAL_NOTE and OUT_OF_PUBLICATION_SCOPE.

This file is a boundary record, not permission to publish the excluded
reference service or any machine-local configuration.
