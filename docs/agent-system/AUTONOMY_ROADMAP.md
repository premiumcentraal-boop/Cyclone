# Cyclone Autonomy Roadmap

This roadmap is capability-based. Version numbers can change; the order of architectural maturity matters more.

**V4 build order** (overlay, page card, act envelope, skill compile, fleet) lives in
[`V4_ROADMAP.md`](V4_ROADMAP.md). Use that file for the next engineering slices.
Use this file for the long-horizon capability stages those slices serve.

## Stage 1 — reliable phone primitives

**Status: substantially implemented.**

Goals:

- semantic Accessibility observation;
- typed click/swipe/scroll/type/back/home/open-app/wait actions;
- controller ownership and risk policy;
- action result/after-state verification;
- optional independent/root diagnostics without model shell access.

Exit criterion: harmless navigation can be executed and verified reliably on physical Android hardware.

V4 note: after-state must reach MCP compact, not only the on-device executor.

## Stage 2 — stable semantic page model

**Status: implemented/beta and still being improved.**

Goals:

- page identity stable across dynamic values;
- semantic controls/selectors;
- transitions;
- compact goal-relevant agent context;
- diagnostic explanation of where information is lost.

Exit criterion: the agent usually finds targets from semantic state without screenshots.

V4 note: compact must keep `pageText` / `pageSummary` and goal-rank controls. The 12-control cap is not the product.

## Stage 3 — learn and reuse app maps

**Status: beta foundation exists.**

Goals:

- Guided / goal-directed / Follow Me learning;
- App Graph with confidence/provenance;
- knowledge states and stale detection;
- Brain mirror/corrections;
- separate run proves learned route reuse.

Exit criterion: first run learns; second run is materially more deterministic.

V4 note: MCP must hit the same Brain-first planner as on-device AI.

## Stage 4 — skill/routine compiler

**Status: V3 capsule and durable-run foundations implemented; product migration remains gradual.**

Goals:

- graph route → reusable skill/automation;
- variables and parameter slots;
- waits/assertions;
- conditions/branching;
- retry/recovery policy;
- review/enable lifecycle;
- clear evidence linking a skill to learned pages/actions.

Exit criterion: a user can teach a routine and run it later without AI step-by-step control when the UI is known.

V4 note: overlay and PC AI write the same AutomationStore. Draft until review. Workers run `verified` only.

## Stage 5 — self-healing knowledge

**Status: V3 temporal graph, bounded recovery primitives, module quarantine and runtime rollback
contracts implemented; production adapters and physical acceptance remain.**

Goals:

- app/version drift detection;
- selector alternatives and promotion;
- automatic re-observe/search after failure;
- confidence decay;
- targeted AI repair;
- repair verification before updating canonical route;
- re-learning queue for badly stale apps.

Exit criterion: common app UI changes do not permanently break learned routines.

## Stage 6 — multi-app mission planner

Goals:

- compose known skills across apps;
- explicit cross-app transitions;
- shared mission state/variables;
- resume/recovery after interruption;
- budget for AI/time/actions;
- user-visible mission timeline.

Exit criterion: Cyclone completes useful multi-app goals with mostly known skills and bounded planning.

V4 first vertical: chat → shop → cart GATE is the proving mission.

## Stage 7 — background autonomy

Goals:

- triggers: schedule, notification, app event and user-defined conditions;
- job queue and concurrency policy;
- battery/network constraints;
- permission/consequence budgets per automation;
- foreground takeover when Android requires it;
- notification/report when work completes or needs approval.

Exit criterion: approved routines run reliably without the user manually opening Cyclone each time.

V4 note: Working overlay + “you can leave this screen” is the consumer language for this stage.

## Stage 8 — optimization and local intelligence

Goals:

- measure route cost, latency and failure patterns;
- select faster verified alternatives;
- cache/retrieve compact context efficiently;
- optional on-device/local model for low-latency intent/routing;
- stronger remote models only for hard uncertainty;
- automatic skill consolidation from repeated evidence.

Exit criterion: repeated tasks become measurably faster, cheaper and more reliable.

Primary V4 metric: skill hit rate (zero-model replays).

## Stage 9 — consumer autonomy platform

Goals:

- simple natural-language control over apps/routines;
- visual app map/Brain that normal users can understand;
- import/export/share skills with provenance and safety metadata;
- multiple devices;
- PC/Codex/Hermes as advanced extensions rather than prerequisites;
- transparent privacy and permission controls.

V4 overlay + teacher/worker fleet is the start of this stage, not the end.

## Cross-cutting metric targets

Every stage should improve some combination of:

- success rate;
- route reuse percentage;
- deterministic vs AI/vision step ratio;
- recovery success;
- AI calls/tokens per mission;
- learning effort;
- user takeovers;
- accidental consequence rate (target effectively zero);
- secret/privacy leakage (target zero).
