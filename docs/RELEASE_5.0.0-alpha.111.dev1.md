# Cyclone V5 Alpha 111: Cyber starts — answers that stream

This developer alpha is the first step of **Cyber**, the Glass Manager (plans 52 and 53): the AI in Glass's workspace
panel now answers live. You see its words as they are written and every tool as it runs, you can write your next
message while it is still answering, and long conversations stay sharp. Setup is unchanged: your OpenRouter key and a
model in Command Center → AI.

## What changes in Glass

- **Live answers.** The answer appears word by word, with a cursor at the end. Each step it takes ("Checked the
  phones", "Read “Weekly plan”") shows as it starts and gets a ✓, ✋ (a proposal for you) or ⚠ when it finishes.
- **Write while it answers.** A message sent during an answer waits below it ("Waiting — answered next") and is
  answered right after, in order. Up to five messages can wait. **Stop** ends the answer where it is and drops the
  waiting messages, and says how many were not sent.
- **Longer work.** One answer may take up to 25 steps (was 10). The daily and monthly spending limits still stop it
  first.
- **Long conversations.** When older turns no longer fit, Cyber keeps a short summary of them for itself instead of
  forgetting them. You still see every message. If a summary cannot be made, older turns fall away as before.
- **Fallback.** If the live connection drops, the panel reconnects (1 s, 2 s, 4 s … up to 15 s) without missing
  anything, and checks every second in the meantime, as before.

## Under the hood

- The AI service moved from one file to the agent package `command/agent/` (registry, workspace toolset, tiered
  prompt, store, loop). Tools register with a kind and a strict schema; a tool whose name reads as shell, file,
  network, delete, approve, vault, secret, key or pay access cannot be registered. The 25 workspace tools, their
  order and the instructions the model gets are unchanged.
- New live event socket `/v1/cc/ai/events` (`cyclone.manager.events/1`): the same local bearer as every route, sent
  like the fleet socket's; resume with `afterSeq`; a `gap` says when events were missed and Glass reloads.
- Answers stream from OpenRouter over the same checked, pinned https connection as every API call. Streamed text is
  masked exactly like the stored answer, and the newest 200 characters wait until they cannot be part of a password,
  code or key. Tool calls are assembled from their streamed pieces. Hidden provider reasoning is never read or kept.
- An answer stopped mid-stream still counts toward the spending limits (estimated from its size when the provider
  sends no usage).

No approvals, proposals or boundaries changed: tasks and routines are still proposals you apply, page edits are
proposals unless you allowed direct edits, and phones still ask before sending, paying, deleting or signing in.

## Versions and evidence

- Product / Android / gateway / MCP: **5.0.0-alpha.111.dev1**; Android version code **259**. The Android app has no
  code change in this alpha.
- Glass: **1.0.0-alpha.62**. The retired desktop-window component remains unchanged.
- Validation for this exact source: gateway tests (including `test_agent_registry.py` and `test_agent_stream.py`),
  Glass tests (including `ai-stream.test.mjs`), CI guards, release-version and product guards; recorded in the release
  PR and its CI runs.
- Physical acceptance: **UNVERIFIED.** Streaming has not yet been watched in Glass against a real OpenRouter key on the
  owner's PC; the scripted provider in the tests stands in for it.
