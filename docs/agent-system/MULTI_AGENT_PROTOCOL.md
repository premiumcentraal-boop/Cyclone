# Multi-Agent Engineering Protocol

Cyclone is well-suited to parallel development only when ownership is strict. Parallel agents should reduce cycle time, not create three competing architectures.

## Roles

### Coordinator / integration agent

Owns:

- exact base SHA;
- work decomposition;
- shared contracts;
- branch naming;
- merge/integration order;
- conflict resolution;
- cross-layer tests;
- release packaging.

### Feature agents

Own one bounded layer and its tests. They do not merge other agents, rewrite shared contracts unilaterally or “help” by changing unrelated paths.

### Review/verification agent (optional)

Can independently inspect diffs, contract compliance, CI and device evidence without owning implementation.

## Task contract

Every parallel task should start with this information:

```text
MISSION:
EXACT BASE SHA:
BRANCH:
OWNER LANE:
OWNED PATHS:
FORBIDDEN PATHS:
INPUT CONTRACT:
OUTPUT CONTRACT:
ACCEPTANCE TESTS:
PHYSICAL DEVICE REQUIREMENT:
HANDOFF REQUIRED:
```

If a task cannot be expressed this way, it is probably not separated enough for safe parallel execution.

## Recommended branch names

```text
agent/android-<task>
agent/learning-<task>
agent/mobile-ai-<task>
agent/pc-gateway-<task>
agent/mcp-<task>
agent/release-<task>
integration/<release-or-feature>
```

All parallel branches in one sprint should start from the same exact SHA unless the coordinator explicitly sequences them.

## Interface-first workflow

When Agent A produces data consumed by Agent B:

1. coordinator freezes a contract/fixture;
2. both agents implement against that fixture;
3. each agent owns contract tests on its side;
4. integration runs an end-to-end test using real producer/consumer code.

Example: Android semantic observation JSON should not be discovered independently by PC and MCP implementations.

## Ownership map

Use the lane map in root `AGENTS.md`. Especially avoid overlapping edits to:

- `CycloneMobileV292App.kt` / large UI files;
- `PhoneToolExecutor.kt`;
- `AndroidManifest.xml`;
- `build.gradle.kts`;
- gateway schemas;
- release workflows.

Shared files should be owned by integration or explicitly assigned to one agent.

## Required handoff

Every implementation agent returns:

```text
Branch:
Base SHA:
Head SHA:
Commits:
Owned scope respected: yes/no
Files changed:
Behavior implemented:
Contracts changed:
Tests run:
CI state:
Physical device state:
Security/privacy notes:
Known limitations:
Integration instructions:
```

“Handoff ready” without exact SHAs/tests is not sufficient.

## Integration checklist

The integration agent should:

1. verify each head is descended from the declared base;
2. inspect changed paths against ownership;
3. review contract differences before merging;
4. merge/compose branches without dropping commits;
5. run cross-layer contract tests;
6. run the relevant release gate;
7. verify artifact source SHA/hash;
8. record unresolved physical-device limitations.

## Avoid merge-by-report

Agent reports are useful but not authoritative. Integration must inspect the GitHub branch/commit itself. A report saying “all tests passed” is not a substitute for current CI or reproducible commands.

## Conflict policy

If two agents changed the same contract in incompatible ways:

- do not choose by newest commit;
- compare requirements and frozen interface;
- preserve the simpler canonical path;
- add a regression test for the resolved contract;
- document the architectural decision.

## Multi-agent sprint pattern for Cyclone autonomy

A strong four-track sprint is:

1. **Perception/control** — improve page/action primitives and evidence.
2. **Learning/automation** — consume those primitives into graphs/skills/routines.
3. **AI/UX** — reason over the stable contracts and expose a coherent product.
4. **Integration/release** — contract tests, CI, physical acceptance, packaging.

PC Gateway and MCP can be additional tracks when external agent control is part of the sprint.

## Agent safety rule

No feature agent may relax action policy, secret redaction or shell/root restrictions merely to make an acceptance test pass. Escalate the missing capability to the coordinator as a contract/design question.
