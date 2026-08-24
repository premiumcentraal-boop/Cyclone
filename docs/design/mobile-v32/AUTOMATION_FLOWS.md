# Cyclone routine creation flows

These flows are the copy and interaction contract for the mobile builder.

## Canonical language

- Product object: **Routine** in the interface; `AutomationDefinition` remains the compatibility
  model in code.
- Trigger: **When**.
- Ordered work: **Then**.
- Verification: **Check**.
- Manual trigger: **One tap**.
- Enabled state: **On / Off**, never “armed” or “scene active”.

## Flow 1 — one-tap routine

```text
Routines → + → One tap → Add action → Open an app → package/app
         → Add another or continue → Name → Review → Save
```

Default behavior: save enabled because execution still requires an explicit user tap and all steps
continue through policy and the canonical executor.

## Flow 2 — notification routine

```text
Routines → + → Notification received
         → choose/type source app → optional “contains” text
         → Add action(s) → Name → Review → Save
```

If notification access is missing, the routine may be saved but remains off. The review screen links
to Android notification access. Notification text used for matching must not be copied into reports
as unrestricted persisted content.

## Flow 3 — scheduled routine

```text
Routines → + → At a time → native time picker → repeat choice
         → Add action(s) → Name → Review → Save
```

The runtime stores an absolute next time plus an optional interval compatible with the current
AlarmManager implementation. More expressive day-of-week recurrence is a Phase B addition.

## Flow 4 — app-opened routine

```text
Routines → + → App opened → app/package
         → Add action(s) → Name → Review → Save
```

The trigger comes from Accessibility observation. It never grants action authority by itself.

## Starter action catalog

The Phase A visual builder intentionally begins with a small safe catalog:

| Label | Typed step |
|---|---|
| Open an app | `PHONE_TOOL / phone.open_app` |
| Go Home | `PHONE_TOOL / phone.home` |
| Go Back | `PHONE_TOOL / phone.back` |
| Wait a moment | `DELAY` |
| Ask me to continue | `REQUEST_HUMAN_TAKEOVER` |

Follow Me, manual teaching and AI can already build richer selector-backed routines. Phase B brings
those details into the same visual editor without exposing raw JSON.

## Review card

Every routine review must answer, in order:

1. When will this start?
2. What will Cyclone do?
3. How many phone actions are involved?
4. Will Cyclone ask before a consequential step?
5. What verifies success, or is verification still missing?

The save action is disabled until trigger details, at least one action and a non-blank name are
valid. Errors appear next to the relevant step, not as a generic toast.
