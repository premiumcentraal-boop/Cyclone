# Agent 3 handoff — AI, Teach, workflow reliability and MCP

## Contract

- Base: `e0149ab0638c77fa3d99d9d383f1d912fcbca25e`
- Branch: `feature/v35-ai-integration-release`
- Head: recorded in the commit that adds this handoff
- Owned paths: mobile AI/guided runtime, `tools/codex-phone-mcp/**`, `tools/cyclone-agent-mcp/**`,
  and `docs/cyclone-3.5/**`.
- No version, release workflow, Agent 2 backend, merge, tag, or publication changes were made.

## Delivered

- bounded `AgentReliabilitySession` with retry/convergence/event controls wired into the existing
  OpenRouter/Brain replay path;
- Teach evidence fields, selector repair and `TeachingWorkflowQuality` quality gate;
- explicit-target typed `phone_group_act` in both MCP surfaces;
- recursive host-command parameter rejection and sensitive teaching-analysis redaction;
- source-aware MCP SDK tool-list verification;
- architecture, workflow, reliability, security and test-plan documentation.

## Verification and limits

Phone MCP: 46 passed. Agent MCP: 36 passed. Android tests and assembly are blocked locally by the
absence of Java/JDK 17. Physical and virtual acceptance remain unverified until integration runs the
hardware/provider matrix. The branch is not a release and must be reviewed and merged by the
integration owner before any version bump or publication.
