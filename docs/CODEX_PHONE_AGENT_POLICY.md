# Cyclone Phone Gateway — Codex Agent Policy

This policy is intended to accompany the `cyclone-phone` MCP server.

## Operating loop

1. Call `phone_status` before a new device-control session.
2. Call `phone_observe` and identify the current PageKey.
3. Prefer a known verified route/routine or an obvious semantic control.
4. If the target is missing from compact context, use `phone_ui_search` rather than asking for a full tree.
5. If several candidates are plausible, use `phone_inspect_element`.
6. Use `phone_screenshot` only when structured UI is insufficient, unlabeled, custom-rendered, spatially ambiguous, or contradictory.
7. Execute exactly one safe typed action through `phone_act`.
8. Observe again and verify the expected PageKey/state transition.
9. Never repeat the identical failed action blindly. Inspect history/search/debug evidence first.
10. Use `phone_debug_bundle` when perception, context selection, execution, and verification disagree.

## Evidence hierarchy

Prefer real current phone evidence over remembered assumptions:

`current semantic UI → verified route evidence → App Graph/Brain hints → deeper UI retrieval → screenshot → diagnostic bundle`

App Graph and Adaptive Brain are useful hints, not unquestionable truth. Actual successful/failed phone outcomes own executable confidence.

## Cyclone 2.9.3 navigation diagnoses

- `ACCESSIBILITY_PERCEPTION`: expected target is absent from raw visible Accessibility evidence. Investigate visual/custom UI or alternative perception.
- `SEMANTICIZATION_LOSS`: raw Android evidence contains the target but PageContext lost it. Investigate semantic hierarchy/labeling/ranking.
- `AGENT_CONTEXT_TRUNCATION`: PageContext has the target but production model context does not. Search/retrieve the missing control rather than globally dumping the tree.
- `AGENT_REASONING_OR_MEMORY`: target reaches the agent input. Investigate model choice, stale Brain/App Graph bias, execution, or verification.

If the proposed action is correct but live execution fails, inspect selector resolution, Android action rejection, gesture execution, stabilization, PageKey transition, and verification before blaming reasoning.

## Safety and privacy

- Never request or expose generic ADB, shell, PowerShell, `su`, or root-command tools.
- Never expose passwords, OTPs, API keys, auth tokens, or entered secret values.
- `phone.type` requires explicit task authorization and `user_authorized=true`.
- Do not perform purchases, deletes, sends, account-security changes, or permission changes unless the user's task explicitly authorizes the consequential operation and the existing Cyclone policy layer permits it.
- Do not claim hidden chain-of-thought. Give concise evidence summaries such as page recognized, route recalled, action chosen, verification passed, or recovery required.
