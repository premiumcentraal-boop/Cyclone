# Cyclone 3.5 architecture (Agent 3 lane)

Cyclone 3.5 keeps one semantic `phone.*` control model and one canonical Android mutation path.
This lane adds reliability and workflow-quality policy above that path; it does not call Android
accessibility APIs, spawn host commands, or create a second workflow engine.

## Runtime loop

1. Read a fresh semantic page and retrieve a verified Brain/App Graph route.
2. Create a bounded `AgentReliabilitySession` for the task.
3. Plan only the remaining uncertainty, then request typed `phone.*` actions.
4. Execute through `PhoneToolExecutor`, observe again, and require an after-state witness.
5. Emit trace events, update route evidence, and stop safely on non-convergence.

`AgentReliabilitySession` is state/policy only. It provides bounded turns, per-failure retry
budgets, timeouts, repeated-action detection, oscillation detection, pause/resume/cancel controls,
and a bounded event history. The existing `OpenRouterAdaptiveAgent` remains the orchestrator.

## Teach path

`RoutineTeachingRuntime` remains the durable session store. Each step now carries bounded evidence:
before/after fingerprints, expected result, verifier, action/verification outcomes, fallback path,
and confidence. `TeachingWorkflowQuality` evaluates that evidence before
`TeachingRoutineCompilerV292` writes the existing Automation definition. Weak steps remain review
evidence and sensitive-looking plaintext rejects compilation.

## Fleet AI boundary

The MCP surfaces expose explicit target selection and a bounded `phone_group_act` for non-secret
typed actions. Every target is observed before mutation and returns an independent result. Generic
shell, ADB, root, subprocess, Docker, and script-shaped parameters are rejected recursively.

Virtual-device lifecycle remains behind Agent 2's typed provider contract; this branch does not
claim a virtual provider is bootable on every host.
