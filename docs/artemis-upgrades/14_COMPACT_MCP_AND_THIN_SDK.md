# A thin SDK and explicit task-oriented MCP surface

**Priority: P2. Scope: developer integration. Implementation size: medium. Status: proposal, not implemented.**

## Finding

Artemis separates its Python client from the host runtime. The client package declares zero runtime dependencies and explicitly avoids importing ADB, agent implementations, or model providers. It exposes health, readiness, capabilities, task submission, polling, and cancellation. Its MCP entry points group task execution, management, device state, trace inspection, and diagnosis.[^1][^2][^3] This reduces installation cost for a consumer that only needs to talk to an existing host.

The package is marked alpha. Its existence is useful architecture evidence, not proof of a stable public compatibility contract or that Cyclone should adopt its server.

## Cyclone comparison

Cyclone already has a granular constrained MCP tool surface and a distinct Live Phone integration.[^4][^5] PC-native tools let an external agent make its own phone decisions; Live Phone delegates a task to a different execution path. Collapsing these modes would change authority, provider use, billing, and behavior. The improvement is lighter packaging and clearer contracts, not silently routing every tool through another autonomous agent.

## Proposed change

Extract or package a proposed `cyclone-client` around existing authenticated gateway/task contracts. Keep transport, typed request/result models, timeout handling, and structured errors in the client. Keep Android control, ADB management, model orchestration, and server setup in the host. Audit the current dependency graph before choosing extraction boundaries; zero dependencies is an option, not a requirement at the expense of reliable transport.

Offer explicit APIs for discovery/readiness and for the supported task mode. A task handle carries a stable ID, mode, scope, state, timestamps, and result/evidence links. Add idempotency semantics before permitting automatic submission retries. Distinguish cancelling local polling from requesting remote task cancellation; a timeout cannot falsely report that device work stopped.

A compact task-oriented MCP entry point can coexist with granular tools for users who deliberately choose Live Phone. It must disclose the selected execution mode and model, validate scope, and return the same task handle as the SDK. Keep native `phone_observe`/`phone_act` workflows intact.

Expose server and schema versions and reject incompatible contracts clearly. Follow existing credential handling, avoiding tokens in URLs, logs, or exception reprs. Do not duplicate the provider catalog or provider API key storage in the thin client.

## Acceptance and rollout

Install the client in a clean environment without Android/ADB/provider packages and exercise it against a fake authenticated server. Verify timeout-versus-cancel semantics, duplicate submission handling, wrong-mode rejection, and old/new schema compatibility. Existing granular MCP integration tests must still pass unchanged in behavior.

Release an explicitly experimental client first, document supported server versions, and promote only after compatibility tests cover upgrades. Rollback is removal of the optional integration package; existing gateway and native tools remain supported. Dependencies: 10 for schemas, 11 for task lifecycle, and 12 for readiness projection.

## Sources

[^1]: Artemis, [`packages/artemis-client/pyproject.toml`](https://github.com/google/artemis/blob/371aa6df56880643da57b30da936e9812fb0ec66/packages/artemis-client/pyproject.toml).

[^2]: Artemis, [`packages/artemis-client/src/artemis_client/client.py`](https://github.com/google/artemis/blob/371aa6df56880643da57b30da936e9812fb0ec66/packages/artemis-client/src/artemis_client/client.py#L55), `class ArtemisClient`.

[^3]: Artemis, [`mcp_server/tools/task_runner.py`](https://github.com/google/artemis/blob/371aa6df56880643da57b30da936e9812fb0ec66/mcp_server/tools/task_runner.py).

[^4]: Cyclone, [`tools/cyclone-agent-mcp/cyclone_agent_mcp/server.py`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/tools/cyclone-agent-mcp/cyclone_agent_mcp/server.py#L33), `def build_server`.

[^5]: Cyclone, [`tools/codex-phone-mcp/cyclone_phone_mcp/live_phone.py`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/tools/codex-phone-mcp/cyclone_phone_mcp/live_phone.py).
