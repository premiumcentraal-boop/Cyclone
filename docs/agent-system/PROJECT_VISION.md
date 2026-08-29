# Project Vision — Autonomous Cyclone Mobile

## North star

A consumer should be able to tell Cyclone what they want done on their phone in plain language and, within the permissions they granted, Cyclone should complete the job reliably even across unfamiliar apps.

When Cyclone encounters something new, it should learn enough to make the next attempt easier. When the app changes, Cyclone should notice the mismatch, repair its knowledge and preserve the new evidence.

V4 consumer language: **stay in the conversation, let Cyclone finish the job.** Analysis card on the current app, one confirm, background work, live view, Stop task / Take control, gate before purchase, draft skill for next time. Any PC AI uses the same skill store. See `V4_DIRECTIONS.md`.

## The ideal user loop

### Ask

From Messages, or from Ask Cyclone: “Order this pizza from the family chat.”

### Cyclone

- shows an Analysis card of the intent (no eight-step essay);
- waits for Confirm / Order this from;
- opens the shop with a known skill or a compact locate/act loop;
- verifies every page transition with after-state;
- lets the user leave, or open Live view;
- stops at GATE before pay;
- reports the result;
- stores no sensitive typed information;
- compiles a draft skill if two or more steps verified.

If the UI moved, Cyclone searches the fresh page, repairs the selector and remembers the new route.

## Teach without programming

Users should be able to teach in three ways:

1. **Follow Me** — demonstrate naturally while Cyclone observes before/after pages and actions.
2. **Goal learning** — tell Cyclone what to learn inside a selected app and let it explore safe navigation.
3. **Correction** — when Cyclone gets something wrong, show/tell it the correct next action and retain that evidence.

V4 adds a fourth teacher that writes the **same store**: a PC AI or overlay run that survives verification.

Teaching should produce understandable App Graph/Brain entries and, when sufficiently verified, reusable skill/automation candidates.

## Autonomous does not mean uncontrolled

Cyclone should be autonomous about low-risk navigation and routine execution, while preserving user-defined boundaries for consequential work.

The product should have clear controls for:

- which apps can be learned/controlled;
- what triggers can run in the background;
- whether a job may leave the current app;
- what kinds of actions always require confirmation;
- how much API/compute/time a job may use;
- when human takeover should happen (Stop task / Take control always visible while driving).

Autonomy should expand because evidence/confidence improves, not because permissions silently broaden.

## Intelligence architecture

Cyclone should use intelligence where it creates value:

- deterministic code for known routes, policies, validation and scheduling;
- small/fast local logic for intent classification and retrieval where practical;
- capable API models for ambiguous planning/repair;
- vision for the subset of UI that structured Android evidence cannot explain.

The goal is not to maximize model calls. The goal is to maximize reliable goal completion with the minimum necessary uncertainty handling. Primary V4 metric: skill hit rate (repeated goals with zero model calls).

## Consumer UX

The app should feel like one product, not an engineering console.

- **Home** tells the user whether Cyclone is ready.
- **Teach** turns demonstrations into knowledge.
- **AI** is where the user asks for phone jobs and accesses advanced PC/Codex control.
- **Automations** shows repeatable jobs and triggers, including overlay-compiled draft skills.
- **Brain** explains what Cyclone learned and lets the user correct it.
- **Settings** contains permissions/connections/preferences.
- **Overlay** is not a seventh tab. It appears over the host app.

Advanced diagnostics should be reachable but should not dominate normal use.

## Definition of a mature Cyclone

Cyclone is mature when it can:

- learn a new app/task with minimal demonstration/exploration;
- replay it later with high semantic reliability;
- recover from common UI drift;
- compose known skills across multiple apps;
- run approved background jobs under explicit triggers/budgets;
- explain what it did and what evidence it used;
- protect sensitive values and respect consequence boundaries;
- become measurably faster/cheaper on repeated tasks;
- run a verified skill on a second phone without re-planning.
