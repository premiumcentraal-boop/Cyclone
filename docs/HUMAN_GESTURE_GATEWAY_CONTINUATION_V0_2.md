# Human Gesture Gateway Continuation V0.2

## Result

Agent 3 moved `cyclone.human_gesture.control.v1` from documentation into the existing typed Gateway/MCP transport without creating a PC-side gesture engine.

## Real transport audit

The existing path is already the correct semantic carrier:

`phone_act -> PhoneTools.phone_act -> GatewayClient.action -> Device Gateway typed action -> ActionRouter -> Android action.execute`.

`params` are forwarded as an object, so `humanize` does not require a new endpoint or execution plane. Existing observation witnesses, capability allowlisting, and `{sessionId, displayId}` forwarding remain unchanged.

## V0.2 schema behavior

For `phone.click`, `phone.swipe`, and `phone.scroll`, `params.humanize` may be omitted or set to exactly:

- `auto`
- `off`
- `light`
- `normal`

Omission preserves old-client behavior. Invalid values fail closed. Humanize on unrelated actions fails at the typed Gateway contract.

The PC transport rejects trajectory-authoring fields such as Bezier controls, point arrays, paths, samples, trajectories, and stroke arrays. Android remains the trajectory synthesizer.

## Capability advertisement

`CapabilityDiscoveryResponse` now includes a versioned `human_gesture` block. Until Agent 2 runtime integration is actually present on the integration branch it deliberately reports:

- `transport_schema_ready = true`
- `runtime_available = false`
- all three existing touch actions as `schema_ready_runtime_unverified`
- `phone.drag = unsupported`
- foreground, named VD, and Layer2 as `runtime_unverified`

This is intentionally conservative. An integration agent should flip runtime availability only from a real Mobile runtime capability signal, never from the existence of this PC schema.

## Safety and identity

No changes were made to GATE, policy, observation freshness, mutation ownership, confirmation, duplicate suppression, or Session Contract logic. Humanization remains a preference inside an already typed action. The existing `sessionId`/`displayId` transport is untouched. No fourth execution plane exists.

## Agent 2 integration seam

Agent 2 should consume `params.humanize` at the Mobile typed action boundary and resolve it phone-side. If Agent 2 chooses a different exact field name, the integration resolution should be a narrow rename/adapter; no PC trajectory representation is required.

A future Mobile status/capability bit should drive `runtime_available` and per-plane support. Until that signal exists, this branch refuses to advertise runtime support.
