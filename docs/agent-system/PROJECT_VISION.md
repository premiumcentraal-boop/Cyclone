# Project Vision — Autonomous Cyclone Mobile

## North star

A consumer should be able to tell Cyclone what they want done on their phone in plain language and, within the permissions they granted, Cyclone should complete the job reliably even across unfamiliar apps.

When Cyclone encounters something new, it should learn enough to make the next attempt easier. When the app changes, Cyclone should notice the mismatch, repair its knowledge and preserve the new evidence.

## The ideal user loop

### Ask

“Open my delivery app, check whether my order is delayed, and tell me the new arrival time.”

### Cyclone

- recognizes the app and retrieves its learned map;
- opens the correct route using semantic selectors;
- verifies every page transition;
- reads the relevant structured UI;
- reports the result;
- stores no sensitive typed information;
- updates route confidence from the successful run.

If the UI moved, Cyclone searches the fresh page, repairs the selector and remembers the new route.

## Teach without programming

Users should be able to teach in three ways:

1. **Follow Me** — demonstrate naturally while Cyclone observes before/after pages and actions.
2. **Goal learning** — tell Cyclone what to learn inside a selected app and let it explore safe navigation.
3. **Correction** — when Cyclone gets something wrong, show/tell it the correct next action and retain that evidence.

Teaching should produce understandable App Graph/Brain entries and, when sufficiently verified, reusable skill/automation candidates.

## Autonomous does not mean uncontrolled

Cyclone should be autonomous about low-risk navigation and routine execution, while preserving user-defined boundaries for consequential work.

The product should have clear controls for:

- which apps can be learned/controlled;
- what triggers can run in the background;
- whether a job may leave the current app;
- what kinds of actions always require confirmation;
- how much API/compute/time a job may use;
- when human takeover should happen.

Autonomy should expand because evidence/confidence improves, not because permissions silently broaden.

## Intelligence architecture

Cyclone should use intelligence where it creates value:

- deterministic code for known routes, policies, validation and scheduling;
- small/fast local logic for intent classification and retrieval where practical;
- capable API models for ambiguous planning/repair;
- vision for the subset of UI that structured Android evidence cannot explain.

The goal is not to maximize model calls. The goal is to maximize reliable goal completion with the minimum necessary uncertainty handling.

## Consumer UX

The app should feel like one product, not an engineering console.

- **Home** tells the user whether Cyclone is ready.
- **Teach** turns demonstrations into knowledge.
- **AI** is where the user asks for phone jobs and accesses advanced PC/Codex control.
- **Automations** shows repeatable jobs and triggers.
- **Brain** explains what Cyclone learned and lets the user correct it.
- **Settings** contains permissions/connections/preferences.

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
- become measurably faster/cheaper on repeated tasks.
