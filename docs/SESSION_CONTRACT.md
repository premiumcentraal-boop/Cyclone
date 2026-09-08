# Session Contract (draft for One 1.1 + Mobile 4.1)

Frozen intent — implement in Mobile B2 + One A2/A3. Do not invent a fourth plane without a plan revision. Stage A2 absorbs Layer 2 on PC gateway, MCP `phone_workspace`, and One glass; named VD tiles remain A3.

## Planes

| Plane | Identity | Display | Mutation model | Typical MCP |
|---|---|---|---|---|
| Foreground human | `session_id=default-foreground` | `displayId=0` | Direct; human may hold input | `phone_observe/act` + session_id |
| Session Kernel VD | named `session_id` | `displayId>0` required | Isolated VD inject; never display 0 | same + `display_id` |
| Layer 2 workspace | `workspaceId` + `workspaceGeneration` | stays display 0 | Time-sliced global mutate lock; switch verifies package/user | `phone_workspace` then `phone_act.params` carry ids |

## Hard rules

1. Missing `session_id` on UI observe/act → `SESSION_REQUIRED` (no silent default).
2. Named VD with missing/`0` display → `SESSION_DISPLAY_MISMATCH`.
3. Layer 2 mutate without matching generation → fail closed.
4. Pending GATE blocks switch and queue; never synthesize approval.
5. PC is glass only; `PhoneToolExecutor` on Android is the only mutator.
6. One UI must never draw a Layer 2 workspace as a VD tile or vice versa.
