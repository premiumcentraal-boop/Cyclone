# Human Gesture Continuation — Agent 3 Handoff (V0.2)

## Assigned mission

Move the Round 1 semantic PC↔Mobile Human Gesture contract into the real Device Gateway / Cyclone Agent MCP typed transport while preserving Android as the only gesture synthesis and mutation authority.

## Source identity

- Repository: `premiumcentraal-boop/Cyclone`
- Exact base SHA: `1f62785772583523ee61730fe03b5f5f7c637b16`
- Branch: `agent/human-gesture-continuation-gateway-protocol`
- Final implementation SHA before this handoff: `e7b769bbd47595dc6e1c364e86d862361a55cb61`
- Final branch SHA: the branch HEAD containing this handoff commit
- No Agent 1 or Agent 2 merge was performed.

## Commits

1. `8408a791ec5aa8b02561d9718d480047a2c5301c` — `feat(gateway): validate human gesture preference contract`
2. `df3ae9e4810640eed98983539e815b947002aaf2` — `feat(mcp): enforce semantic humanize boundary`
3. `08e945e9a558859b0c1fddb00d83951df2f856a1` — `fix(mcp): reject raw trajectories in existing validation path`
4. `725af2c6fef589e21ea315914fd6641b26a883e3` — `test(gateway): cover human gesture transport safety`
5. `026a7a1d29d6062cb921a3a3db25a62b1ed7b507` — `test(mcp): cover humanize compatibility and raw path rejection`
6. `e7b769bbd47595dc6e1c364e86d862361a55cb61` — `docs(gateway): record V0.2 human gesture transport audit`
7. handoff commit — this file

## Files changed

- `apps/device-gateway/cyclone_device_gateway/capabilities/models.py`
- `apps/device-gateway/tests/test_human_gesture_transport_v1.py`
- `tools/cyclone-agent-mcp/cyclone_agent_mcp/safe.py`
- `tools/cyclone-agent-mcp/tests/test_human_gesture_transport.py`
- `docs/HUMAN_GESTURE_GATEWAY_CONTINUATION_V0_2.md`
- `docs/handoffs/HUMAN_GESTURE_CONTINUATION_AGENT3.md`

Round 1 lab, trace schema, calibration tooling, and template design were not modified.

## Real Gateway/MCP audit

The existing typed path is already sufficient:

`phone_act -> PhoneTools.phone_act -> GatewayClient.action -> Device Gateway typed action -> ActionRouter -> Android action.execute`.

`params` are preserved as a typed object through this path. Therefore V0.2 does not add an endpoint, raw path tool, mutation queue, or execution plane.

The existing MCP/Gateway code still owns:

- advertised-capability checking;
- fresh observation witness enforcement;
- typed action allowlisting;
- exact `sessionId` / `displayId` forwarding;
- re-observation requirement after mutation;
- Android execution/verification authority.

## Typed schema changes

`CapabilityActionRequest.params.humanize` is accepted only for:

- `phone.click`
- `phone.swipe`
- `phone.scroll`

Allowed values are exactly:

- `auto`
- `off`
- `light`
- `normal`

Omission remains valid for backward compatibility.

The Gateway contract rejects PC-authored trajectory fields including control points, Bezier/path data, sampled points, trajectories, and stroke arrays. MCP generic typed-param validation independently rejects invalid humanize values and raw trajectory-shaped keys before transport.

No `phone.drag`, double-tap, generic coordinate tool, shell, ADB, or script surface was introduced.

## Capability contract

`CapabilityDiscoveryResponse` now includes `human_gesture` with:

- control version `cyclone.human_gesture.control.v1`
- trace version `cyclone.human_gesture.trace.v1`
- `transport_schema_ready = true`
- `runtime_available = false`
- profiles `auto/off/light/normal`
- click/scroll/swipe = `schema_ready_runtime_unverified`
- drag = `unsupported`
- foreground / named VD / Layer2 = `runtime_unverified`

This is intentionally truthful. Agent 2 Mobile runtime has not landed on this branch, so Agent 3 does not advertise Android curved-path support merely because the PC schema is ready.

## Mobile assumptions / dependency on Agent 2

The stable integration seam is `params.humanize`. Agent 2 should resolve that preference at the existing Mobile typed action / `PhoneToolExecutor` boundary and keep semantic Android actions preferred.

At handoff time the requested Agent 2 continuation branch `agent/human-gesture-continuation-runtime` was not present in GitHub branch search. Therefore no Mobile runtime implementation was merged or claimed.

When Agent 2 lands, integration should add a real Mobile capability/status signal and derive `runtime_available` plus per-plane support from that signal. Do not hard-code support true on the PC side.

## Backward compatibility

- Old callers that omit `humanize` remain valid.
- Existing `cyclone.gateway.capability.v1` remains the transport protocol.
- Existing action names and `params` shape remain intact.
- Execution identity is unchanged.
- No existing semantic click/long-click/type behavior is replaced.
- No fourth execution plane exists.

## Security checks

Covered by the new contract tests:

- valid `auto/off/light/normal` accepted on existing touch actions;
- invalid profile rejected;
- omitted field accepted;
- humanize rejected on non-touch Gateway actions;
- raw trajectory fields rejected;
- drag remains unsupported;
- capability advertisement does not overclaim Mobile runtime availability.

Existing fresh-observation, capability allowlist, GATE/policy, and Session Contract code paths were not weakened or bypassed.

## Tests / CI

New tests were added in both Device Gateway and Cyclone Agent MCP suites.

The GitHub connector did not create a workflow run for the implementation head when checked, so repository CI execution is **UNVERIFIED**. No claim is made that GitHub Actions passed.

A local compatibility check confirmed the Pydantic v2 `model_post_init` validation pattern used by the Gateway converts a rejected semantic profile into a `ValidationError`, matching the new tests' expected fail-closed behavior.

No physical Android testing was performed or claimed.

## Unsupported / intentionally absent

- Mobile runtime gesture synthesis on this branch
- full per-plane runtime support claims
- `phone.drag`
- double-tap
- PC-authored Bezier controls or point arrays
- PC-side RNG or path synthesis
- arbitrary coordinate MCP expansion
- routine/skill-wide humanize policy
- full trajectory diagnostics in ordinary responses

## Deviations

The Round 1 contract suggested capability metadata might advertise a detailed runtime matrix immediately. V0.2 deliberately advertises transport readiness separately from runtime availability because Agent 2 has not landed. This prevents a PC client from mistaking schema acceptance for actual Android Human Gesture execution.

No bounded `resolvedProfile`/`traceHash` response projection was added yet because those values must originate from the real Mobile runtime; fabricating them in Gateway would create a second authority.

## Integration readiness

Agent 3 lane is ready to combine with Agent 2 once Mobile accepts the same `params.humanize` semantic preference and exposes a truthful runtime capability signal.

Recommended integration order:

1. combine Agent 1 hardened core lane;
2. combine Agent 2 Mobile runtime lane;
3. combine this Agent 3 transport lane;
4. wire capability advertisement to Agent 2's real runtime signal;
5. run Gateway + MCP + Mobile cross-lane CI;
6. test foreground, named VD, and Layer2 on real supported backends;
7. only then mark runtime availability/per-plane support true.

## Next round

- Consume Agent 2's runtime capability signal.
- Add bounded `resolvedProfile`, `gestureMode`, `traceHash`, and synthesis version only when Android supplies them authoritatively.
- Run old-client compatibility fixtures against the combined integration branch.
- Run physical-device semantic equivalence tests from Mobile-local AI and Cyclone One / PC AI.

No release, version bump, signing change, force push, or unrelated merge was performed.
