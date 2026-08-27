# Cyclone 3.5 Agent 3 test plan

## Completed on this branch

- `PYTHONPATH=tools/codex-phone-mcp python -m pytest -q tools/codex-phone-mcp/tests` — **46 passed**.
- `PYTHONPATH=tools/cyclone-agent-mcp python -m pytest -q tools/cyclone-agent-mcp/tests` — **36 passed**.
- MCP SDK stdio discovery now verifies the source checkout and includes all 15 typed tools.
- `git diff --check` — required before commit.

## Not run / blocked

- Android focused JVM tests for `AgentReliabilityPolicyTest` and `TeachingWorkflowQualityTest`:
  **not run** because this host has no `java`/JDK 17 runtime available.
- Full Android debug unit-test/assembly gate: **not run** for the same reason.
- Physical and virtual device acceptance: **not run on this branch**; those claims belong to the
  integration owner and must use real evidence.

## Integration owner gates

After this branch is integrated, run the focused Android tests, the complete mobile regression suite,
the two MCP suites once on the final SHA, version-coherence checks, and the physical/fleet matrix.
Do not label CI as physical verification.
