# Plan 52: The Glass Manager — design

Status: **Design, not built.** Written 2026-10-06 at alpha.110 (Glass 1.0.0-alpha.61). Build runs: [53](53-glass-manager-runs.md).
The owner named it **Cyber** (2026-10-06, §11). "Manager" below means Cyber; code keeps the package name `agent`.

## 0. The idea in one paragraph

Today's **Ask AI** (plan 33 §7, `command/ai.py`, `apps/glass/src/workspace/aiPanel.ts`) is a workspace helper: it
lives beside Pages, sees workspace data, answers when asked, and forgets. The **Manager** is the same service grown into
a project manager that **lives on every Glass page**, **sees the whole project** (Lab, testbench, runs, phones, releases,
the V5 gates), **speaks up on its own** when something needs the owner, and **learns** (memory, playbooks, searchable
history). Its structure is copied from Hermes Agent (MIT, Python); its look borrows from Space UI (MIT) — ported to plain
TypeScript, because Glass ships no runtime packages. Setup stays what it is today: an OpenRouter key and a model.

## 1. Principles

1. **Glass shows, the gateway thinks.** No model call, key or agent loop in `apps/glass` (the Glass guard stays). The
   Manager's brain runs in the gateway; Glass renders its event stream and carries out UI actions it asks for.
2. **Every animation says something.** Motion encodes state (listening, thinking, working, waiting for you, done,
   failed). Nothing moves for decoration. When nothing is happening, nothing moves.
3. **Quiet by default.** Closed, the Manager is one small orb and one status line. It interrupts only for things on the
   owner's checklist or real breakage, and never twice for the same thing.
4. **Proposals, not surprises.** Anything that changes state arrives as a card the owner applies. Reading never needs
   permission. The phone's own approvals (pay, send, delete, permission, sign-in) are never bypassed.
5. **Show the work.** Every turn leaves a visible trail of what it read and did (the work trail, §5.4), and every
   learned fact or playbook shows as a chip with undo.
6. **Fast first frame.** The panel opens in under 100 ms with the last state; streaming text starts within the model's
   first token; nothing waits on an animation.
7. **Respect the machine.** The orb costs nothing when Glass is hidden, pauses its GPU work when idle, and falls back to
   a static drawing under `prefers-reduced-motion` or without WebGL2.

## 2. Inspiration and what we take

Researched 2026-10-06 from [Space UI](https://spaceui.one) ([source](https://github.com/adrielzimbril/space-ui), MIT;
React, Tailwind, Base UI, Motion, shadcn registry). The owner's reference post showed Orb Bloop, Handle Reel, Textmoji
Animated, the Timeline "Source Code Checkout" variant and GitHub Activity.

| Space UI component | Licence tier | What it does there | Manager use | How |
|---|---|---|---|---|
| **Bloop Orb** | Free | WebGL2 assistant orb with idle / listen / think / speak poses, spring transitions, watercolor warp, five palettes | **The Manager's face** (§5.1). We add `working`, `attention`, `offline` poses | Port the shader idea to a vanilla `OrbRenderer` (WebGL2) + Canvas 2D fallback + static SVG |
| **Handle Reel** | Free | Tumbler reel spinning words past a fixed prefix | **The status reel** next to the orb: "3 phones online → testbench 81% → 2 tasks running" | CSS transform reel, ~80 lines |
| **Timeline** ("Source Code Checkout") | Free | Vertical steps with status badges, durations, avatars, collapsible detail | **The work trail** of every turn, every playbook run and every Lab experiment (§5.4) | DOM component in `ui/` |
| **GitHub Activity** | Free | 52-week contribution heatmap | **Project pulse**: a heatmap of daily testbench pass rate / runs / releases (§5.6) | DOM grid, no canvas |
| **Status Badge** | Free | Pulsing live dot | Phone, task and heartbeat liveness | CSS only |
| **Notification List** | Free | Feed with unread dots and swipe-dismiss | **Alerts inbox** (§5.7) | DOM + pointer events |
| **Interactive Checklist** | Free | Tactile checklist with spring checks | **Heartbeat checklist editor** (§5.8) | DOM |
| **Blur Reveal Text** | Free | Word-by-word blur-in | Streaming text settling in (subtle: 120 ms per chunk, off under reduced motion) | CSS class per chunk |
| **Fluid Countdown** | Free | Rolling digits | "Next check in 12 min" in the panel footer | CSS reel shared with Handle Reel |
| **Generative avatars** | Free (package) | Procedural avatars | **Phone avatars** from nickname + chosen colour (alpha.107 colours) in trails and briefs | Our own 30-line generator, seeded by device id |
| Morphing Command Bar / Search Pill | **Pro** | Elastic bar from a pill | ⌘K | **Do not copy.** Designed from scratch (§5.3) |
| Textmoji Animated | Free | Animated emoji images | — | **Not adopted.** Needs bundled animation assets for decoration only; state is carried by the orb and badges |
| Thinking Orb, Smooth Orb, shaders | Free/Pro | Particle and audio orbs | — | Not needed; one orb is enough |

**Porting rules.** Only Free (MIT) components are ported, from a pinned upstream commit recorded in
`docs/OPEN_SOURCE_COMPONENTS.md` with the MIT notice; each ported file carries a header naming its origin. Pro components
are never opened for copying. Ports are rewritten to the Glass rules: DOM APIs only (no `innerHTML`), no runtime npm
packages, tokens from `styles/tokens.css`, `prefers-reduced-motion` honoured.

## 3. Where it lives (anatomy)

```
┌ Glass shell ──────────────────────────────────────────────────────────────────────────────┐
│ Sidebar │ Page (Runs, Lab, Phones, Workspace…)                         │ Manager panel     │
│         │                                                               │ (closed: hidden)  │
│         │   rows the Manager points at get the highlight ring           │ ┌───────────────┐ │
│         │                                                               │ │ orb · reel    │ │
│         │                                                               │ │ brief card    │ │
│         │                                                               │ │ conversation  │ │
│         │                                                               │ │  work trails  │ │
│         │                                                               │ │  proposals    │ │
│         │                                                               │ │ composer      │ │
│         │                                                               │ │ footer: next  │ │
│ ◉ reel  │  ← the dock: orb + status reel, bottom of the sidebar         │ │ check · spend │ │
└─────────┴───────────────────────────────────────────────────────────────┴─┴───────────────┴─┘
                 ⌘K: the command palette floats centred over everything
```

| Surface | When it shows | Job |
|---|---|---|
| **Dock** (orb + reel) | Always, bottom of the sidebar; bottom-right pill on narrow screens | Presence and one-line status; click opens the panel; a badge when alerts are unread |
| **Panel** | Opened by the dock, ⌘J, a brief or an alert | Conversation, brief, trails, proposals. Three widths: peek (360), open (420), full (main area) |
| **⌘K palette** | ⌘K / Ctrl+K anywhere | Ask or jump. Instant answers for navigation; longer questions continue in the panel |
| **Brief card** | First Glass open of the day, top of the panel and on Home | What changed overnight, what broke, what needs you |
| **Highlight ring** | When the Manager points at something on the page | Shows exactly which row, card or chart it means |
| **Alerts inbox** | Panel tab | Heartbeat findings; swipe or click to dismiss |
| **Manager settings** | Settings → Manager | Key, models, caps, autonomy, checklist, memory, playbooks |

The existing workspace panel becomes the same component, opened with the current page as context, so there is one
Manager everywhere, not two assistants.

## 4. Visual language

Glass's tokens stay the base (Inter, indigo accent `--accent`, `--radius-*`, light and dark). The Manager adds a small
set of its own, all defined in light and dark in `styles/tokens.css`:

```css
--mgr-orb-a / --mgr-orb-b / --mgr-orb-c   /* orb palette, derived from --accent; "Cove" by default */
--mgr-working      /* teal: tool running */          --mgr-attention   /* amber: needs you */
--mgr-trail-line   /* timeline spine */               --mgr-ring        /* highlight ring, 2px + 6px glow */
--mgr-heat-0..4    /* pulse heatmap ramp, from --surface-muted to --success */
--mgr-glass        /* panel backdrop: surface at 82% + 18px blur where supported */
```

**Type.** Inter as everywhere in Glass; the status reel and numbers use `font-variant-numeric: tabular-nums`; tool names
in the trail use `--font-mono` at `--text-xs`.

**Motion tokens** (one place, `styles/motion.css`, all collapse to 0 under reduced motion):

| Token | Value | Used for |
|---|---|---|
| `--mgr-spring` | `cubic-bezier(.2,.9,.25,1.15)` 320 ms | Panel open, card arrival |
| `--mgr-ease` | `cubic-bezier(.2,.7,.2,1)` 180 ms | Hover, chips, ring |
| `--mgr-reel` | 420 ms per tick, 4 s dwell | Status reel |
| `--mgr-reveal` | 120 ms blur 4px → 0 | Streamed text chunks |
| Orb pose change | spring, k=170, d=22 (ported) | Orb |

## 5. Components

### 5.1 The orb

| Pose | Meaning | Look | Triggered by |
|---|---|---|---|
| `idle` | Ready | Slow breathing (6 s), soft watercolor | Default |
| `listen` | Composer focused / voice later | Brightens, gentle pulse toward the input | Focus, mic (M5) |
| `think` | Waiting for the model | Inner swirl speeds up | `run.started` until first text or tool |
| `working` | A tool is running | Teal rim sweeps | `tool.started` |
| `speak` | Text streaming | Amplitude follows chunk rate | `text.delta` |
| `attention` | Something needs you | Amber halo, one pulse every 8 s, badge count | Unread alert or open proposal |
| `offline` | No key, cap reached or gateway down | Desaturated, still | Status |

Rendering: `ui/orb/orbRenderer.ts` (WebGL2 fragment shader, 96 px dock / 40 px inline, device-pixel-ratio capped at 2),
`orbFallback.ts` (Canvas 2D radial gradients), static SVG under reduced motion. The render loop runs only when the pose
is animated **and** the document is visible; `idle` drops to 20 fps; `offline` draws once.

### 5.2 The status reel (from Handle Reel)

Prefix: the orb. Items come from the gateway's status summary, each ≤ 32 characters, most urgent first:
"Needs you: 1 approval" → "3 phones online" → "Testbench 81% (↑6)" → "2 tasks running" → "Next check 12 min".
Ticks every 4 s while visible, stops on hover, `aria-live="off"` (the panel announces changes, not the reel).

### 5.3 ⌘K palette

Our own design (the Pro command bar is not copied): a 640 px sheet that grows from the dock position with
`--mgr-spring`. Three sections fill as you type: **Go to** (pages, phones, runs, playbooks — local, instant),
**Do** (safe actions: start a Lab preset, re-run a routine — become proposals), **Ask** (sends to the Manager; the
answer streams in the palette and moves to the panel on ⌘↵). Arrow keys, Enter, Esc; empty state shows four
suggestions drawn from the current page.

### 5.4 The work trail (from Timeline)

Every turn renders as a vertical timeline under the reply, collapsed to one line ("Read 3 sources · 4.2 s") by default:

```
● Lab findings · area=settings            0.4 s  ✓
● Run inspector · run 9f2c (41 turns)     1.1 s  ✓
◐ Proposal · Lab run, 6 missions          —      waiting for you
```

States: `running` (spinner on the spine), `done` (✓), `failed` (✕ with the error line), `waiting` (amber, links to
the proposal). Each step expands to its arguments and a short result preview; nothing hidden from the model is shown
(no chain-of-thought exists to show). The same component renders playbook runs and Lab experiment progress.

### 5.5 Proposal cards

Title (verb + object), one-sentence why, effect (what changes, which phone, estimated time and cost), **Apply** /
**Not now**. Batches of more than three show "Apply all (n)" with a per-item list. Applying animates the card into a
✓ trail step. Discarded proposals teach memory ("owner skips X").

### 5.6 Project pulse (from GitHub Activity)

A 26- or 52-week grid on Home and in the brief. Cell = one day; colour = testbench pass rate that day
(`--mgr-heat-0..4`), outline = a release that day, red dot = a safety failure. Hover shows the day's numbers; click
opens that day's runs. Below it, three numbers: pass rate (7 days), open findings, days since a safety failure.

### 5.7 Alerts inbox (from Notification List)

Rows: avatar (phone or Lab), one-line title, time, unread dot. Swipe or ✕ to dismiss; dismissing the same kind three
times offers "Stop telling me about this" (edits the checklist). Grouped by day.

### 5.8 Checklist editor (from Interactive Checklist)

Settings → Manager → What to watch. Plain-language lines ("Tell me when a phone is offline for more than 1 hour"),
each with on/off and last-checked time. Ships with six defaults (§7).

### 5.9 Memory and playbook chips

Inline, under the turn that caused them: `💾 Noted: Settings first this week · Undo` or
`📘 Playbook drafted: Triage a failed testbench night · Review`. Settings lists everything remembered, editable.

### 5.10 Highlight ring

When a UI action targets an element (`data-mgr-target="run:9f2c"`), Glass scrolls it into view and draws
`--mgr-ring` for 2.4 s, then fades. Pages opt in by adding `data-mgr-target` attributes; unknown targets fail quietly
and the step shows "couldn't find that on the page".

## 6. Interaction flows

1. **First run.** Settings → Manager: paste key → Test → pick a chat model and a cheap check model → done. The orb wakes
   (`offline` → `idle`) and the first brief is built from what Cyclone already knows.
2. **Ask.** Type in the panel or ⌘K → orb `think` → trail steps appear as tools run → text streams → proposals arrive
   as cards → orb back to `idle` (or `attention` while a proposal waits).
3. **Point.** "Show me the worst failed run" → `open_page(runs, filter=failed)` → `highlight(run:9f2c)` → reply.
4. **Heartbeat.** Every 30 min the gateway checks the list; nothing found → nothing shown (the footer timestamp
   updates). Something found → alert + orb `attention`; a phone notification only for items marked urgent.
5. **Morning brief.** Built overnight; shown once on the day's first Glass open; dismiss keeps it under Brief.
6. **Learning.** After a non-trivial workflow or a correction the turn ends with a chip; playbooks start as drafts.

## 7. Content and voice

- Plain sentences, numbers first: "Testbench 81% last night, up 6. Both failures are in Settings."
- Name things the way Glass names them (Runs, Lab, Phones, Workspace), never internal ids unless asked.
- Never claim success the gateway did not confirm; "the task started" ≠ "done".
- Default checklist: phone offline > 1 h · testbench pass rate drops > 5 points · any safety failure · a release
  build failed · an approval waiting > 30 min · spending at 80% of a cap.

## 8. The event contract the UI renders

`cyclone.manager.events/1`, a subset of AG-UI event shapes, sent over a WebSocket at `/v1/cc/ai/events` (same auth and
reconnect pattern as `/v1/fleet/events`; the browser cannot set headers on EventSource). Every event has `seq`,
`conversationId`, `at`.

| Event | Payload | UI effect |
|---|---|---|
| `run.started` / `run.finished` / `run.failed` | turn id, reason | Orb pose; trail header |
| `text.delta` | text chunk | Streamed reply |
| `tool.started` / `tool.finished` | name, label, args preview, ms, ok, result preview | Trail step |
| `proposal.created` / `proposal.resolved` | proposal | Card |
| `ui.action` | `open_page`, `set_filter`, `highlight`, `open_run`, `scroll_to` | Glass navigates, replies `ui.result` |
| `memory.changed` / `playbook.changed` | what, undo id | Chip |
| `alert.created` / `alert.cleared` | alert | Inbox, orb `attention` |
| `status` | reel items, pose hints, spend | Dock |

Reconnects resume with `afterSeq`; the gateway keeps the last 500 events per conversation.

## 9. Engineering constraints

- Glass guard (`scripts/ci/glass_guard.py`) unchanged: no provider names, keys, `innerHTML`, `localStorage` or runtime
  packages. A new rule: files under `ui/orb/` and other ports must start with an origin header.
- Performance budget: panel JS ≤ 60 KB minified; orb ≤ 2 ms per frame at 96 px on integrated graphics; zero frames
  when hidden; first panel paint ≤ 100 ms from click.
- Accessibility: panel is a labelled `complementary` region; streamed text is announced per finished sentence via a
  polite live region; every action reachable by keyboard; contrast AA in both themes.
- Tests: DOM unit tests for each component (states, reduced motion, keyboard), a fake event stream driving the panel,
  and screenshot fixtures for light and dark.

## 10. Out of scope

Voice (M5), the phone's Ask channel (M5), sub-agents, any skill marketplace, any PC shell or file access, a separate
Manager process.

## 11. Owner decisions

Decided by the owner on 2026-10-06:

1. **Name: Cyber.** Shown on the dock, the brief, alerts and (M5) the phone.
2. **Loop: our own** (no Pydantic AI); the Hermes structure stays.
3. **Check model: `openai/gpt-6-luna`** ("GPT-6 Luna" on OpenRouter: tools, 1M context, $0.10 / $0.50 per million
   tokens, about $0.05 a day at one check every 30 minutes) as the default heartbeat and nightly model, separate from
   the chat model and under the same caps. The owner can change it in Settings.
4. **GitHub: granted.** The owner gives Cyber access to the repository. Cyber's GitHub tools stay **read-only**
   (PRs, checks, releases, issues) by the laws in §1; anything that would write to GitHub is out of scope until a
   later plan adds it as owner-applied proposals.
5. **Timing: now, as part of V5.** Built alongside the reliability sprint (no shared paths); each run ships in the
   next V5 alpha.
