# Cyclone Mobile 4.3.2

Version 4.3.2, Android versionCode 93. Built from the preserved 4.3.1 source with grounded action verification, persistent task handoff, and one shared task status.

- Phone actions retain separate execution and verification outcomes. A dispatched gesture or changed page hash cannot alone complete a semantic operation.
- Back now uses the same canonical action contract end to end. Gateway handling no longer overrides missing or negative Android verification.
- Actions carry current observation identity and exact session/display scope. Resume invalidates pre-handoff observations and reconciles the current page before continuing.
- Ask Cyclone, View Progress, and task notifications share Working, Action Needed, Done, and terminal Failed/Stopped state. Progress labels omit raw tool parameters and provider prose.
- Take Over and I'm Done use explicit backend capabilities and existing command routing. Autofill remains unavailable; no credential manager was added.
- Compiled routines stop when the executor rejects a step and verify their final page before publishing progress.
- The two frontend task-card commits are integrated, with their provisional resume eligibility replaced by backend capabilities.

PhoneToolExecutor, existing GATE approvals, execution planes, and Aurora activation/window lifecycle remain in place. Signing material is unchanged; the existing release workflow verifies update-signature continuity with 4.3.1.

Validation: Python gateway, MCP, source guards, and Android CI results are recorded in [the engineering handoff](HARNESS_4.3.2_HANDOFF.md). Physical Pixel 8 execution and UI acceptance have not been tested for this release. Device-specific accessibility behavior, handoff timing, profile switching, and overlay rendering still require device acceptance testing.
