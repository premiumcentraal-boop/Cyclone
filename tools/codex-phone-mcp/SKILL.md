# Cyclone Phone MCP: Locate, then act

Use `phone_locate` before every phone mutation. It returns the device state, a bounded Page Card,
and goal-ranked semantic candidates without dumping a raw accessibility tree.

1. Pick a current `elementId` from the Page Card or semantic search result.
2. Pass that ID to `phone_act`; do not create text, fuzzy, bounds, or coordinate selectors.
3. Treat every `elementId` as observation-scoped. It expires after a mutation—never reuse it.
4. Prefer the verified route returned by Cyclone. If Page Card context is truncated, search and
   inspect before using screenshots.
5. Read `phone_act`'s before/after Page Cards, `pageChanged`, delta, and `errorClass`. A transport
   receipt is not proof that Android performed or verified the action.

The server intentionally exposes no shell, ADB, root, PowerShell, or generic command execution.
