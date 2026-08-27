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

During the original export/publication cleanup, `hermes_temi_bridge/` was
treated as an excluded historical/reference service and was not included in the
standalone public Android repository. It is not part of the current standalone
tree. Backend and Bridge services remain external dependencies and are outside
the Android publication boundary.

The following are always local-only or generated:

- local.properties and signing.local.properties.
- app/src/main/res/raw/elderly_hand_exercise.mp4 and
  app/src/main/res/raw/elderly_leg_exercise.mp4.
- Keystores, certificates, passwords, aliases, and SDK paths.
- .gradle/, build/, APK/AAB files, captures, recordings, logs, and reports.

## Documentation classification

- README.md: PUBLIC_DOCUMENTATION.
- AGENTS.md: DEVELOPER_DOCUMENTATION, generalized for publication.
- docs/CURRENT_STATUS.md, docs/REPOSITORY_MAP.md, and
  docs/VERIFIED_FEATURES.md: CURRENT_DOCUMENTATION and evidence map.
- docs/architecture/, docs/contracts/, docs/operations/, and docs/handover/:
  CURRENT_DOCUMENTATION for architecture, contracts, operations, and handover.
- docs/performance/yuv-copy-optimization-2026-08-09.md:
  HISTORICAL_DOCUMENTATION with historical machine-specific details removed.

This file is a boundary record, not permission to publish the excluded
reference service or any machine-local configuration.
