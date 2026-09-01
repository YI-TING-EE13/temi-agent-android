# Academic-Lab Android Development Workflow

This document defines the shared development path for the public TemiAgent
Android repository. It preserves the existing Issue, branch, pull request, CI,
review, and merge sequence while making responsibility and evidence boundaries
explicit.

The workflow uses stable role IDs rather than personal names. Any personnel
mapping for a role belongs outside the public repository.

## Role model

| Role | Responsibility |
| --- | --- |
| `PROJECT-01` | Provides research and project direction, priority, major constraints, acceptance expectations, advice, feedback, and a lightweight final governance review when required. `PROJECT-01` is not the routine Android implementation operator. |
| `ANDROID-01` | Owns Android Issue decomposition, source analysis, technical design, implementation, tests, documentation, pull-request preparation, review fixes, and Android evidence collection. |
| `ANDROID-02` | Provides backup Android maintenance and review coverage. |
| `TEMI-01` | Owns physical Temi, ADB, installation, and physical-acceptance operations. |
| `AI6-01` | Owns the external AI6 repository and runtime boundary. |
| `MQTT-01` | Owns the external MQTT broker and runtime boundary. |
| `SIGNING-01` | Owns primary Android Demo signing custody. |
| `GITHUB-01` | Owns repository, pull-request, tag, release, and related GitHub governance. |

The Android maintainer must not modify an external runtime merely because
Android observes a failure there. Escalate the issue to the boundary owner
first and keep each change in its owned repository.

## Core development flow

Use this sequence for non-trivial Android work:

```text
PROJECT-01 research/project direction
  -> GitHub Issue
  -> change classification
  -> feature, fix, experiment, or documentation branch
  -> ANDROID-01-owned implementation
  -> local tests
  -> Pull Request
  -> Android CI
  -> review
  -> PROJECT-01 lightweight final review when required
  -> merge
  -> signed Demo and physical Temi acceptance when required
  -> current-status and evidence update
  -> Issue closure
```

`PROJECT-01` supplies direction and reviews system implications. `ANDROID-01`
turns that direction into an implementable Issue and owns routine Android
engineering. `PROJECT-01` does not need to approve each implementation detail
or commit.

## Start from the accepted base

Run the following commands from the Android repository root before creating a
branch:

```text
git fetch origin
git checkout main
git pull --ff-only
git rev-parse HEAD
```

Compare `HEAD` with the required base recorded by the Issue or release gate.
Stop if the required base is not present or if `main` moved unexpectedly. Do
not develop directly on protected `main`; create the documentation or code
branch from the verified base.

## GitHub Issue requirements

For non-trivial Android work, the Issue must record:

- Objective.
- Motivation and the user or research need.
- Scope.
- Non-goals.
- Acceptance criteria.
- Required evidence.
- Android modules expected to change.
- External contract impact.
- `Physical Temi acceptance required: YES / NO / UNKNOWN`.
- `AI6/MQTT impact: NONE / describe`.
- Risk or change class.
- Open decisions.

`ANDROID-01` is responsible for converting high-level direction into a
testable Issue. `PROJECT-01` provides or resolves project-level direction and
open decisions; it does not own routine Issue decomposition.

## Change classes

Classify the change before implementation. The class controls the evidence
and authorization gate; it does not add approval layers to simple work.

| Class | Boundary | Examples and required handling |
| --- | --- | --- |
| **A — REPO_LOCAL** | Existing Android architecture and contracts | UI fix, internal refactor, unit test, documentation, or local implementation without a contract change. `ANDROID-01` may proceed within the existing architecture and contracts; normal pull-request review still applies. |
| **B — FEATURE_OR_ARCHITECTURE** | New Android behavior or a substantial design change | New feature, major lifecycle change, new camera/media behavior, or Temi SDK architectural change. Summarize the proposed design before substantial implementation. `PROJECT-01` reviews the goal and system implications, not every class or method. |
| **C — CROSS_REPO_CONTRACT** | Android and an external repository or runtime | MQTT payload, canonical command, WebSocket contract, Android-to-AI6 behavior, or shared identifier. Define the coordinated contract and compatibility plan before merging incompatible behavior. Use one shared Change ID across the affected repositories. |
| **D — HIGH_RISK_DEVICE_OR_RELEASE** | Signing, identity, device state, movement, endpoints, or release governance | APK signing, package identity, install/uninstall or data clearing, downgrade, real robot movement, production endpoint/configuration, or release governance. Use the existing explicit-authorization and runbook gates. |

## Branch workflow

Use a coherent branch named for the Issue:

```text
feat/<issue>-<short-topic>
fix/<issue>-<short-topic>
experiment/<issue>-<short-topic>
docs/<issue>-<short-topic>
```

Do not perform routine development directly on `main`. Keep the pull request
focused on one Issue and do not mix unrelated refactors with feature work.

## Android verification ladder

Record evidence at the strongest level actually exercised. The levels are
separate:

| Level | What it establishes | What it does not establish |
| --- | --- | --- |
| `SOURCE` | The reviewed source, configuration, and documentation express the intended change. | Passing source review does not prove execution. |
| `UNIT` | Focused JVM or other local tests pass. | Unit tests do not prove Temi behavior or external-service behavior. |
| `BUILD` | The selected Gradle task produces the intended development or release artifact. | A debug build does not prove signed Demo deployment. |
| `DEVICE` | An authorized signed Demo artifact passes a bounded physical Temi check. | Device evidence does not prove Android/AI6 or broker end-to-end compatibility. |
| `E2E` | The complete accepted producer, consumer, external-runtime, and device path is exercised. | A local media or device test alone is not `E2E`. |

Use this sequence when the change requires each gate:

```text
source and unit checks
  -> assembleDebug
  -> pull-request CI
  -> merge
  -> authorized signed Demo
  -> artifact preflight
  -> bounded Temi/device acceptance
```

Do not require a physical test for a change that cannot affect physical
behavior. A claim must name its evidence level and its known limitations.

## Pull-request review packet

Every non-trivial pull request must include:

- Linked Issue.
- Goal.
- What changed.
- Tests and checks run.
- Evidence level and evidence location.
- Documentation impact.
- Known limitations.
- `Physical Temi acceptance required: YES / NO`.
- `Cross-Repo Impact: NONE` or the affected contract and repository.
- `Decision Needed: NONE` or the exact `PROJECT-01` decision.
- Risk and rollback note when relevant.

The packet lets `PROJECT-01` review direction, evidence, scope, and open
decisions without replacing the normal Android implementation review.

## Lightweight `PROJECT-01` review

When a final project review is required, `PROJECT-01` checks:

- Does the change satisfy the intended direction?
- Does the evidence justify the claim?
- Did the change stay within the Issue scope?
- Are limitations explicit?
- Is a cross-repository or high-risk decision unresolved?
- Is the proposed next step reasonable?

Routine Android code details remain the responsibility of `ANDROID-01`,
`ANDROID-02`, and the normal pull-request review process.

## Cross-repository work

For coordinated work involving
`YI-TING-EE13/temi-agent-android` and `YI-TING-EE13/TemiAgent`, use one shared
Change ID:

```text
CR-YYYYMMDD-short-topic
```

Reference corresponding Issue and pull-request records in both repositories.
Each maintainer modifies only the repository they own. The recommended order
is:

```text
contract definition
  -> backwards-compatible transition plan where feasible
  -> producer and consumer tests
  -> Android implementation
  -> AI6 implementation
  -> CI in each repository
  -> documented safe merge order
  -> bounded integration/device acceptance
```

The Android repository does not define an AI6 implementation procedure. Ask
`AI6-01` for the external runtime procedure and evidence when the Issue needs
it.

## Boundary escalation

Escalate by technical boundary:

| Issue boundary | First role |
| --- | --- |
| Android source, UI, or Activity lifecycle | `ANDROID-01` |
| Temi, ADB, installation, or physical acceptance | `TEMI-01` |
| AI6 runtime | `AI6-01` |
| MQTT broker or runtime | `MQTT-01` |
| Demo signing | `SIGNING-01` |
| GitHub, release, or tag governance | `GITHUB-01` |
| Cross-boundary or final governance decision | `PROJECT-01` |

The first role coordinates the diagnosis and brings in the backup or related
role when the Issue crosses a boundary. Do not publish private endpoints,
device identifiers, credentials, signing values, private captures, or resident
data in the public repository.

## Post-merge and Issue closure

When accepted behavior or evidence changes, update the appropriate
current-status and verified-feature documentation. Close the Issue with:

- The merged pull request.
- Final evidence level.
- Device acceptance status.
- Known limitations.
- Follow-up work.

New acceptance does not erase earlier failures or change historical records.
Keep historical evidence labeled as historical.

## Research and experiment work

Use these conceptual stages for experimental Android work:

```text
EXPERIMENTAL -> REPRODUCED -> IMPLEMENTED -> VERIFIED -> ACCEPTED
```

Do not promote an experimental success directly to accepted deployment
behavior. Record the source commit, configuration, test or device conditions,
metric or result, baseline, and known limitation when relevant. Do not commit
private captures, endpoints, device identifiers, resident data, or other
prohibited artifacts.

## Protected governance and lab style

The normal merge model is:

```text
Issue -> branch -> Pull Request -> required CI -> review -> merge
```

Do not weaken protected-main rules, required CI, release/tag policy, or signing
governance as part of an Android change. High-risk device and release work
still requires its existing authorization and runbook gates.

This workflow is intentionally lightweight. It does not require Scrum
ceremonies, daily standups, story points, long design documents, or multiple
approval layers for simple fixes. The workflow supports student ownership,
technical independence, research reproducibility, traceable decisions, and
safe integration.
