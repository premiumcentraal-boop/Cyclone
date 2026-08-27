# Cyclone 3.5 agent reliability

`AgentReliabilitySession` is a small, deterministic policy state machine used by the existing
mobile AI runtime. It is deliberately independent of model providers and transport.

## Guarantees

- bounded plan turns and task timeout;
- separate retry budgets for observation, action, transport, and verification failures;
- repeated action detection when the observed state is not progressing;
- observation oscillation detection;
- post-action verification events;
- structured planning, observation, action, retry, recovery, pause, completion, and failure events;
- explicit start, pause, resume, cancel, and bounded history snapshots.

The policy returns `CONTINUE`, `RETRY`, `PAUSE`, or `FAIL`. Callers remain responsible for invoking
the canonical `PhoneToolExecutor`; the policy never executes a tool itself.

## Safe persistence

Action signatures persist only a bounded tool name and SHA-256 witness of the target. Visible labels,
typed values, credentials, and model prompts are not written to reliability history. Trace and
teaching reports retain only bounded, redacted metadata.
