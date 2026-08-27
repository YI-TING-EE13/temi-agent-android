# Demo Signing Handover

This document transfers the Demo signing procedure without transferring
private signing material. The project owner controls access to the signing
identity and decides who may create an installable Demo artifact.

## Why signing identity matters

Android package updates require a compatible signing identity. A correct
package name and a higher versionCode do not make an APK update-compatible when
the signer is wrong. Treat a signing mismatch as a release stop, not as a
reason to remove the installed package.

## Public versus private material

The following values are public verification data:

- expected Demo certificate SHA-256:
  `4DA8461B45B02FADCB042F63151FEE05D56EBD5105EB721D7D62E30B88513A7F`;
- configuration template: `signing.local.properties.example`; and
- verification code: `app/build.gradle`, task `verifyDemoSigningConfig`.

The following material remains private and out of band:

- the keystore file;
- the keystore/store password;
- the private key password;
- the key alias when the deployment treats it as confidential; and
- the custody location and responsible contact.

The populated `signing.local.properties` file is ignored and must remain local
to the authorized operator. The keystore path must be an absolute external
`<KEYSTORE_PATH>`; never copy the keystore into the repository or document its
real filesystem path.

## New maintainer setup

Complete these steps only after the project custodian grants signing access:

1. Obtain the authorized signing material and custody instructions from the
   project custodian through the approved private channel.
2. Store the keystore outside the repository and restrict access according to
   the project’s custody policy.
3. From the repository root, create the ignored local configuration from the
   tracked template:

   ```text
   Copy-Item .\signing.local.properties.example .\signing.local.properties
   ```

   On Linux or macOS, use `cp signing.local.properties.example
   signing.local.properties`.

4. Fill the four private values locally, using an absolute `<KEYSTORE_PATH>`.
   Do not include the values in shell history, documentation, issue text, PR
   text, or build logs.
5. Use JDK 21 and run the Demo build:

   ```text
   .\gradlew.bat :app:assembleDemo --no-daemon --console=plain --max-workers=1
   ```

   Linux or macOS:

   ```text
   ./gradlew :app:assembleDemo --no-daemon --console=plain --max-workers=1
   ```

6. Confirm that `verifyDemoSigningConfig` reports `PASS` and that the emitted
   public signer fingerprint matches the expected certificate above.
7. Run the signed-artifact preflight described in
   [BUILD_AND_TEST.md](BUILD_AND_TEST.md), including package, signer,
   non-debuggable, Media v1.1, and attach-deadline checks.
8. Record only the variant, source `HEAD`, public fingerprint, preflight
   result, and artifact provenance in the authorized release record.

## Never

- Never commit a keystore or a populated `signing.local.properties` file.
- Never paste passwords, private aliases, keystore paths, or other signing
  secrets into issues, pull requests, documentation, or chat.
- Never create a new signing key merely because the existing credentials are
  inconvenient to access.
- Never uninstall the deployed package merely to bypass a signer mismatch.
- Never switch the Demo variant to debug signing or call a debug APK the
  canonical deployment artifact.

## Lost key scenario

`REQUIRES_PROJECT_OWNER_DECISION`.

If the existing signing identity cannot be accessed or the certificate does
not match, stop the release. The project owner must decide whether recovery of
the existing custody path or a deliberate new-signing migration is possible.
Do not invent a destructive recovery procedure, delete installed data, or
publish a replacement key from this runbook.
