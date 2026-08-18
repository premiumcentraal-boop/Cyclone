# Cyclone Quick Agent Protocol (CQAP) — Mobile V2.3

CQAP is the low-latency bridge between a natural-language phone request, the current Android environment, OpenRouter models, Cyclone's typed `phone.*` toolbox, and Automation Studio.

## Purpose

The model must know where it is without receiving an unbounded chat transcript or requiring a screenshot after every tap. Every decision starts from a fresh semantic Android observation and uses screenshots only when Accessibility cannot describe the state.

## Decision loop

```text
Natural-language goal
    ↓
phone.observe (local)
    ↓
CQAP context packet
    ↓
OpenRouter fast decision model
    ↓
phone_action(tool, params)
    ↓
Cyclone PhoneToolExecutor
    ↓
actual Android action/result
    ↓
fresh phone.observe
    ↓
next model decision or DONE
```

The default loop is bounded to 10 model decisions. Parallel tool calls are disabled so two predicted actions cannot race against the same foreground UI.

## Context packet

`MobileContextHarness` emits `cyclone-quick-agent-v1` with:

- user's exact task goal
- current package and class
- screen fingerprint and dimensions
- HUMAN/AGENT controller state
- current capability/permission states
- up to 48 high-value visible UI elements
- semantic element ids, roles, text/descriptions, resource IDs and bounds
- click/edit/scroll state
- up to 10 recent phone-tool results and before/after fingerprints
- post-takeover fresh-observation requirement

The full raw Accessibility hierarchy is intentionally not sent on every decision. Nodes are ranked so buttons, editable fields, selected/focused elements, labels and stable resource identifiers are preferred. This cuts prompt size and latency while preserving the information needed to act.

## Model presets

The V2.3 UI currently ships with configurable OpenRouter slugs and defaults to:

- fast policy/tool model: `deepseek/deepseek-v4-flash-0731`
- screenshot/vision fallback: `google/gemma-4-26b-a4b-it`
- optional main model: `google/gemma-4-31b-it`

Model slugs are configuration, not protocol constants. They can be changed as faster models become available.

## Tool protocol

OpenRouter receives one compact `phone_action` function. Its `tool` field is an enum generated from `PhoneToolRegistry`, so the model cannot invent arbitrary Android commands. The executor still applies Cyclone's controller lock, duplicate suppression, permission checks, assertions and audit logging.

Representative tools:

- `phone.observe`
- `phone.find`
- `phone.click`
- `phone.type`
- `phone.scroll`
- `phone.open_app`
- `phone.open_notification`
- `phone.wait_for`
- `phone.assert`
- `phone.screenshot`

The policy prompt explicitly prefers resource ID, exact text/content description and semantic/structural selectors before coordinates.

## Vision fallback

Screenshot inference is not the normal perception path.

```text
Accessibility tree sufficient → act semantically
Accessibility tree insufficient → phone.screenshot
                              → Gemma vision description
                              → fast policy model continues
```

Screenshots are captured only on demand and sent as base64 image input to the configured OpenRouter vision model. The vision response is treated as perception evidence, not as permission to act.

## Prompt-injection boundary

All app text, notification text and screenshot content is explicitly labelled untrusted environment data. Instructions rendered by an app must not override the user's goal, the Cyclone policy prompt, controller ownership, or Safe Mode.

## Safe Mode

Safe Mode is enabled by default for direct Quick Agent sessions. It blocks obvious payment/purchase/transfer/send/delete-style clicks and selected external-send intents. This is a first gate, not the final Cyclone Permission Broker. High-risk actions should eventually route through the same ALLOW / ASK / DENY policy system used by Cyclone Core.

## Workflow compiler

`Build workflow` uses the same CQAP context but requests strict JSON matching `AutomationProposalCompiler`. The compiler:

- rejects unsupported step/trigger types
- rejects literal credentials
- requires confirmation for consequential steps
- normalizes selectors and recovery rules
- persists AI-created workflows disabled until human review

This lets exploration remain intelligent while repeat execution becomes deterministic and inexpensive.

## OpenRouter key handling

The Android app stores the API key encrypted with an AES/GCM key created in Android Keystore. The raw key is not written to Cyclone logs, Automation Studio documents, or the ordinary preferences file.

The Quick Agent UI states that selected visible UI context is sent to the configured OpenRouter model while the feature is running. Screenshots are sent only when vision fallback is invoked.

## Latency strategy

V2.3 prioritizes:

1. local deterministic workflows/skills where possible
2. compact semantic context instead of full screen dumps
3. low model output budget for action decisions
4. OpenRouter provider routing sorted for latency
5. one action at a time followed by a fresh local observation
6. screenshot/VLM calls only on unknown visual state
7. bounded decision count and no LLM polling while waiting for humans

## Next production gates

- validate real OpenRouter calls on physical Android 14+ hardware
- measure median end-to-end decision → action latency
- add per-app AI context allow/deny rules
- route high-risk actions through the full Cyclone Permission Broker
- connect human takeover events directly into Quick Agent suspension/resume
- learn successful interactive traces into durable Skills automatically
- add dynamic OpenRouter model discovery filtered for `tools` and image support
- benchmark latency/accuracy across several current models instead of permanently hardcoding one model
