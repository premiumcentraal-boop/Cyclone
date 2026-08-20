# Quality Gates

Cyclone needs different truth levels for static code, CI and physical phone behavior.

## Gate A — repository/static invariants

Use for every mobile-affecting change.

Check:

- one package / one launcher;
- expected product surfaces still exist;
- version identity is centralized;
- no stale user-facing release strings;
- no unapproved generic shell/root agent tools;
- changed files remain inside the task’s ownership scope;
- `git diff --check` / formatting sanity.

## Gate B — unit/contract tests

### Android

Cover:

- phone tool parsing/execution outcomes;
- selector and element-ID resolution;
- observation/page semantics;
- gateway auth/protocol/privacy;
- App Graph/Brain retrieval contracts;
- teaching lifecycle;
- action risk boundaries.

### PC gateway

Cover:

- ADB/device selection logic;
- observation normalization;
- Android error propagation;
- action stabilization/history;
- screenshot/debug bundle paths;
- auth/token separation;
- forbidden action payloads.

### MCP

Cover:

- MCP initialize/list/call protocol;
- exact constrained tool list;
- progressive retrieval behavior;
- `phone.type` authorization/redaction;
- failed action accounting;
- mock two-pass route/learning evidence.

## Gate C — build/package

Build the actual Android APK and PC/MCP packages from the exact candidate SHA. Capture SHA-256 and size.

No release claim before this gate is green.

## Gate D — harmless physical phone acceptance

For device-control releases, perform a real route on target hardware that does not create consequential effects.

Example structure:

1. Home/Launcher.
2. Open Settings.
3. Navigate to a harmless destination such as Apps.
4. Verify semantic page identity.
5. Return Home.
6. Repeat the route.
7. Confirm the second run uses/retrieves learned route evidence where expected.

Collect failures as perception, semanticization, retrieval, action, verification or reasoning categories.

## Gate E — learning acceptance

Teach or explore a safe route, end the learning session, then start a separate run and verify reuse.

The important test is not “could Cyclone reach it once?” It is “did Cyclone turn the first success into useful durable knowledge?”

## Gate F — recovery acceptance

Deliberately invalidate a selector/route in a controlled fixture or test app and verify:

- failure is recorded;
- confidence/staleness changes;
- Cyclone re-observes/searches;
- a replacement can be promoted only after evidence;
- old alternatives/provenance remain available.

## Gate G — consequence/privacy review

Before exposing new actions or autonomous triggers, verify:

- confirmation/risk classification;
- authentication handling;
- sensitive typing redaction;
- background scope/permission;
- no policy bypass through PC/MCP;
- audit/report data does not contain secrets.

## Verification labels

Use explicit language:

- **Implemented** — code exists.
- **Unit verified** — tests passed.
- **CI built** — artifact assembled in CI.
- **Physically verified** — successfully run on target Android hardware.
- **Production verified** — signed/distributed/observed under the real deployment path.

Do not collapse these into a single “done.”
