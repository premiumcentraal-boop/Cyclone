# Cyclone V5 Alpha 111: Meet Cyber

This developer alpha brings **Cyber**, the Glass Manager (plans 52 and 53): the AI that used to sit beside the
workspace now lives on every Glass page, as a small character that shows what it is doing, and it answers live.
Setup is unchanged: your OpenRouter key and a model in Command Center → AI.

## Cyber on every page

- **The dock.** Cyber sits at the foot of the sidebar on every Glass page (a floating pill on narrow windows), with a
  rolling status line — "Needs you: 1 approval", "1 of 2 phones ready", "2 tasks running", today's spending — and a
  badge when something waits for you. Press it to open Cyber.
- **The character.** Cyber has its own pose and motion for each thing it does: ready (floats, blinks, follows your
  pointer), listening (leans in while you type), thinking (looks away, hand to chin), working (code on its visor, a
  gear turning), answering (its mouth moves with the words), needs you (hops, waves, amber badge), done (a happy jump),
  something failed (crossed eyes, a shake) and offline (asleep). With reduced motion on, nothing moves but each pose
  stays different.
- **The panel** opens on any page, not just the workspace. Each message tells Cyber which page you are on ("Runs",
  "Lab · exp-…"), so "why did this fail?" means what you are looking at.
- **The palette.** From any page, type to go to a page (Glass screens, Command Center tabs, workspace pages by title),
  do something quick (open Cyber, its settings, search the workspace), or ask Cyber — a question opens the panel and is
  sent from the page you are on.

### Keys, on Windows and on a Mac

| | Windows | Mac |
|---|---|---|
| Palette (ask or go to) | **Ctrl+K** | **⌘K** |
| Cyber's panel | **Ctrl+.** | **⌘.** |

Glass shows the right label for your computer. Cyber's panel moved off Ctrl+J because Chrome and Edge on Windows use
Ctrl+J for Downloads; ⌘J / Ctrl+J still work where the browser lets the page have the key. Esc closes either one.

## Answers that stream

- **Live answers.** The answer appears word by word, with a cursor at the end. Each step it takes ("Checked the
  phones", "Read “Weekly plan”") shows as it starts and gets a ✓, ✋ (a proposal for you) or ⚠ when it finishes.
- **Write while it answers.** A message sent during an answer waits below it ("Waiting — answered next") and is
  answered right after, in order. Up to five messages can wait. **Stop** ends the answer where it is and drops the
  waiting messages, and says how many were not sent.
- **Longer work.** One answer may take up to 25 steps (was 10). The daily and monthly spending limits still stop it
  first.
- **Long conversations.** When older turns no longer fit, Cyber keeps a short summary of them for itself instead of
  forgetting them. You still see every message. If a summary cannot be made, older turns fall away as before.
- **Fallback.** If the live connection drops, Glass reconnects (1 s, 2 s, 4 s … up to 15 s) without missing anything,
  and checks every second in the meantime.

## Preview of the parts still to come

`#/dev/cyber` in Glass (not in the sidebar; example data only) shows Cyber's character in all nine moods and the parts
the next runs wire in: the work trail, the project pulse (26 weeks of daily pass rates), status dots, the alerts list,
the watch list with its six defaults and phone avatars in each phone's own colour. Their designs follow Space UI's
free components (MIT) as a reference; no Space UI code is copied.

## Under the hood

- The AI service moved from one file to the agent package `command/agent/` (registry, workspace toolset, tiered
  prompt, store, loop). Tools register with a kind and a strict schema; a tool whose name reads as shell, file,
  network, delete, approve, vault, secret, key or pay access cannot be registered. The 25 workspace tools, their
  order and the instructions the model gets are unchanged.
- New live event socket `/v1/cc/ai/events` (`cyclone.manager.events/1`): the same local bearer as every route, sent
  like the fleet socket's; resume with `afterSeq`; a `gap` says when events were missed and Glass reloads.
- New `GET /v1/cc/ai/presence` for the dock: counts only (approvals, proposals, phones ready, tasks running, spending),
  never task or page text. Messages accept an optional `where` (the Glass page, at most 160 characters, screened for
  secrets).
- Answers stream from OpenRouter over the same checked, pinned https connection as every API call. Streamed text is
  masked exactly like the stored answer, and the newest 200 characters wait until they cannot be part of a password,
  code or key. Tool calls are assembled from their streamed pieces. Hidden provider reasoning is never read or kept.
- An answer stopped mid-stream still counts toward the spending limits (estimated from its size when the provider
  sends no usage).

No approvals, proposals or boundaries changed: tasks and routines are still proposals you apply, page edits are
proposals unless you allowed direct edits, and phones still ask before sending, paying, deleting or signing in. Glass
still makes no model calls and holds no key.

## Versions and evidence

- Product / Android / gateway / MCP: **5.0.0-alpha.111.dev1**; Android version code **259**. The Android app has no
  code change in this alpha.
- Glass: **1.0.0-alpha.62**. The retired desktop-window component remains unchanged.
- Validation for this exact source: gateway tests (including `test_agent_registry.py`, `test_agent_stream.py` and
  `test_agent_presence.py`), Glass tests (including `ai-stream.test.mjs`, `cyber-components.test.mjs` and
  `cyber-presence.test.mjs`), CI guards, release-version and product guards; recorded in the release PR and its CI runs.
  The character, dock and palette were also checked in Chromium in light and dark and at phone width.
- Physical acceptance: **UNVERIFIED.** Cyber has not yet been used in Glass against a real OpenRouter key on the
  owner's PC, on Windows or on a Mac; scripted providers in the tests stand in for it.
