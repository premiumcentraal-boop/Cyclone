# One capability contract for tools and prompts

**Priority: P1. Scope: Android/gateway/MCP contracts. Implementation size: medium to large. Status: proposal, not implemented.**

## Finding

Artemis has a manifest between actuator backends and agent-facing tools. Optional actions disappear from both declarations and prompt instruction blocks when the backend cannot perform them. Contract tests scan prompt dependencies to catch a tool mentioned without a corresponding manifest classification.[^1][^2] This prevents the model from being taught an action that its current backend cannot execute.

Artemis requires `click_sequence` because its Flash prompt depends on bursts. Cyclone should borrow the manifest mechanism without inheriting that particular requirement or arbitrary ADB tools.

## Cyclone comparison

Cyclone already records tool name, mutability, required capability, and description in `PhoneToolRegistry`. It also has Python MCP declarations and a separate agent protocol prompt.[^3][^4][^5] These are useful contracts, but their independent maintenance creates a drift risk. A tool may exist in code while being unavailable in the active session or selected model mode.

## Proposed change

Define a versioned canonical tool manifest with stable name, JSON input/output schema, required runtime capabilities, allowed execution modes, observation requirements, mutation classification, human-gate classification, and documentation. Generate language-specific declarations and prompt enumerations from this source, or validate hand-written adapters against it when generation is impractical.

Compute an effective manifest for the active device/session/display and chosen mode. Distinguish unsupported hardware, temporarily unavailable permission/backend, and action requiring human approval. A tool's presence never grants authority: `PhoneToolExecutor` must recheck current ownership, scope, generation, and approvals at dispatch.

Expose manifest version/hash through existing capability responses. On a stale client version, return a typed compatibility error with supported versions rather than accepting an approximately matching argument shape. Rebuild model tool context after a relevant capability change; do not leave stale instructions in the conversation indefinitely.

Keep model capabilities distinct from phone capabilities. An image-capable phone backend does not make a text-only model accept screenshots. Keep PC-native control and Live Phone task submission separate surfaces, even if both derive schemas from the same source.

## Acceptance and rollout

Contract fixtures should remove a capability and prove that its executable declaration and related prompt block disappear together. Negative tests should reject a mutation whose capability was revoked after planning, wrong-generation arguments, and unsupported schema versions. Cross-language snapshots should agree on required fields and error enums without mirroring implementation internals.

Adopt manifest validation first for existing tools, then generate one adapter at a time. Roll back generation while retaining compatibility checks. Track unknown-tool calls, invalid-argument failures, and prompt size. This is a maintenance and reliability improvement; it does not itself increase task-solving intelligence. Coordinate with 04 and 14 when adding new surfaces.

## Sources

[^1]: Artemis, [`artemis/mcp/action_manifest.py`](https://github.com/google/artemis/blob/371aa6df56880643da57b30da936e9812fb0ec66/artemis/mcp/action_manifest.py#L263), `def filter_declarations`.

[^2]: Artemis, [`tests/unit/mcp/test_action_manifest.py`](https://github.com/google/artemis/blob/371aa6df56880643da57b30da936e9812fb0ec66/tests/unit/mcp/test_action_manifest.py).

[^3]: Cyclone, [`apps/mobile/app/src/main/java/com/cyclone/mobile/PhoneToolRegistry.kt`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/apps/mobile/app/src/main/java/com/cyclone/mobile/PhoneToolRegistry.kt).

[^4]: Cyclone, [`tools/cyclone-agent-mcp/cyclone_agent_mcp/server.py`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/tools/cyclone-agent-mcp/cyclone_agent_mcp/server.py#L33), `def build_server`.

[^5]: Cyclone, [`apps/mobile/app/src/main/java/com/cyclone/mobile/ai/PageAgentProtocol.kt`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/apps/mobile/app/src/main/java/com/cyclone/mobile/ai/PageAgentProtocol.kt).
