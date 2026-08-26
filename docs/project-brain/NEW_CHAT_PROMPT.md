# Cyclone New-Chat Bootstrap Prompt

Copy this into a fresh ChatGPT/Codex conversation when starting Cyclone work:

---

Use the connected GitHub repository `premiumcentraal-boop/Cyclone` as the project source of truth.

Before answering or changing code:

1. Read `/AGENTS.md` from the current Project Brain/integration line.
2. Read `docs/project-brain/NOW.md`.
3. Read only the relevant section(s) of `docs/project-brain/BUILD_BIBLE.md` for this task.
4. Then inspect the current executable code/tests for the subsystem you need to change.

Do not rely on old chat history when repository evidence is available. Do not assume `main` is current without checking the current checkpoint/release evidence. Distinguish implemented code, CI evidence and physical-device verification.

Preserve Cyclone's canonical semantic phone tool path, policy/privacy boundaries and observe → act → verify model.

If my instruction makes a **major** project change, update the Project Brain according to `docs/project-brain/WORKFLOW.md`. Do not update it for ordinary bug fixes or minor refactors.

Now continue with this task:

[PASTE THE NEW TASK HERE]

---

## Short version

For quick follow-ups:

> Load `AGENTS.md` + `docs/project-brain/NOW.md` from `premiumcentraal-boop/Cyclone`, then fetch only the Build Bible/code sections needed for my task. Treat GitHub/code as durable context instead of old chat history. If this is a major architectural/product change, refresh the Project Brain using its workflow.