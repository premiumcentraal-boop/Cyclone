# Agent 1 — Cyclone Mobile Universal Phone Toolbox Handoff

Branch: `feature/mobile-phone-toolbox-agent1`  
Draft PR: #2, targeting `feature/android-mobile-v0`

## Latest verified source build

The latest source commit tested by CI is `1619ef50c7f4103739ca121f147a2a7225c36e00`.

GitHub Actions workflow run `32155673027` completed successfully with:

- Android SDK 35 setup: PASS
- `testDebugUnitTest`: PASS
- `assembleDebug`: PASS
- APK upload: PASS

Artifact:

- ID: `9331650833`
- Name: `cyclone-mobile-debug-apk`
- GitHub artifact digest: `sha256:a17bd6181c9f3ea36a68559e8cf9e0c2b6e9339ef792477c60cb2c8b62d6c6e3`

A docs-only handoff commit may appear after this source commit; it does not change the compiled Android implementation.

## Agent 1 implementation

Agent 1 converted the mobile layer into a generic Android 14+ device-control/perception toolbox with:

- typed `phone.*` request/result/error protocol
- `PhoneToolRegistry` and `PhoneToolExecutor`
- normalized Accessibility snapshots
- stable node IDs, node paths and parent/child relationships
- window metadata and screen fingerprints
- selectors for resource ID, exact/partial text, content description, class/role, ancestors/descendants, coordinates, relative position and lightweight fuzzy text
- semantic click with Accessibility action + gesture fallback
- long press, tap, type/replace text, scroll, swipe, Back and Home
- screenshot metadata, optional crop and opt-in base64
- app launch
- retained notification metadata and `contentIntent` opening
- clipboard read/write
- share and allowlisted URI intents
- local `phone.wait_for` / `phone.assert`
- bounded retry/postcondition checks
- command-ID idempotency cache and rapid duplicate suppression
- capability registry
- serialized remote phone command execution
- short Accessibility-event debounce for fresh observations
- privacy-conscious command audit records
- hardened HUMAN/AGENT controller ownership
- forced fresh `phone.observe` after human control returns
- preservation of legacy `takeover_start` / `takeover_return` bridge controls
- selector unit tests
- physical-device acceptance checklist

## Integration contract for Agent 2

Automation Studio must consume `PhoneToolRegistry` / `PhoneToolExecutor` semantics. It should not call `CycloneAccessibilityService` directly.

Use `phone.wait_for` and `phone.assert` for deterministic local waits instead of model or server polling. An automation step should reference selectors rather than hard-coded coordinates whenever possible.

## Integration contract for Agent 3

Hermes should receive the typed `phone.*` tool surface rather than raw Accessibility internals. The preferred perception order is:

1. `phone.observe`
2. `phone.find` with deterministic selectors
3. structural/relative/fuzzy selectors
4. `phone.screenshot` only when structured UI is insufficient
5. vision model returns a target/selector, then the normal typed phone tool performs and verifies the action

Human takeover semantics are already enforced at the phone-control layer: when HUMAN owns input, mutating tools are rejected. After control returns to AGENT, a fresh `phone.observe` is mandatory before mutations can resume. Agent 3 should build the durable zero-token suspend/resume protocol above this primitive.

## Not yet physically verified

No real Android 14+ phone was connected during this Agent 1 session. The following remain hardware acceptance gates:

- install APK
- observe a real app hierarchy
- screenshot a real screen
- run selector-based click/type/scroll/swipe
- open an actual retained notification
- verify clipboard behavior on target OEM
- verify capability states against Android settings
- verify HUMAN controller rejects actions on-device
- verify stale UI recovery
- multi-OEM compatibility
- 24-hour reliability/battery soak

See `apps/mobile/PHONE_TOOL_ACCEPTANCE.md` for the detailed hardware checklist.
