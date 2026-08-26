# Cyclone New-Chat Bootstrap Prompt

Copy this into a fresh ChatGPT/Codex conversation when starting Cyclone work:

---

Use the connected GitHub repository `premiumcentraal-boop/Cyclone` as the project source of truth.

Start by reading the durable Project Brain from branch `project/cyclone-brain`:

1. `/AGENTS.md`
2. `docs/project-brain/NOW.md`
3. only the relevant section(s) of `docs/project-brain/BUILD_BIBLE.md` for this task

Then inspect the current executable code/tests for the subsystem and source branch named by `NOW.md` or the task. Do not assume `main` is current without checking release/current-state evidence.

Do not rely on old chat history when repository evidence is available. Distinguish implemented code, CI evidence and physical-device verification.

Preserve Cyclone's canonical semantic phone tool path, policy/privacy boundaries and observe → act → verify model.

If my instruction makes a **major** project change, update the Project Brain according to `docs/project-brain/WORKFLOW.md`. Do not update it for ordinary bug fixes or minor refactors.

Now continue with this task:

[PASTE THE NEW TASK HERE]

---

## Short version

For quick follow-ups:

> Load `AGENTS.md` + `docs/project-brain/NOW.md` from branch `project/cyclone-brain` in `premiumcentraal-boop/Cyclone`, then fetch only the Build Bible/current-code sections needed for my task. Treat GitHub/code as durable context instead of old chat history. If this is a major architectural/product change, refresh the Project Brain using its workflow.