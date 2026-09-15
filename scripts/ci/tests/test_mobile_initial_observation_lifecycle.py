from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3]
RUNTIME = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/overlay/OverlayChromeRuntime.kt"
AGENT = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ai/OpenRouterAdaptiveAgent.kt"
HEALTH = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/agent/ObservationHealth.kt"


class InitialObservationLifecycleGuards(unittest.TestCase):
    def test_authoritative_capture_change_is_retryable_before_terminal(self):
        source = HEALTH.read_text(encoding="utf-8")
        self.assertIn("ObservationState.CAPTURE_CHANGED", source)
        self.assertIn("attempts >= 2", source)
        self.assertIn("now + 500", source)
        self.assertIn('"same_scope_capture_after_cooldown"', source)

    def test_446_regression_shape_is_guarded_at_foreground_entrypoint(self):
        agent = AGENT.read_text(encoding="utf-8")
        runtime = RUNTIME.read_text(encoding="utf-8")

        # 4.4.6 can return before AgentTraceRuntime.start when the first authoritative capture
        # changes mid-traversal. The foreground entrypoint must therefore recognize a pre-trace
        # result, wait one bounded settle interval, and retry exactly once.
        self.assertIn("var state = observeState(goal, initialBridge)", agent)
        self.assertIn("val traceId = AgentTraceRuntime.start(context, goal, config.model.id)", agent)
        self.assertLess(
            agent.index("var state = observeState(goal, initialBridge)"),
            agent.index("val traceId = AgentTraceRuntime.start(context, goal, config.model.id)"),
        )
        self.assertIn("var result = agent.execute(request, config, progress)", runtime)
        self.assertIn("if (result.taskId == null && result.decisions == 0)", runtime)
        self.assertIn("delay(INITIAL_OBSERVATION_RETRY_MS)", runtime)
        self.assertIn("INITIAL_OBSERVATION_RETRY_MS = 550L", runtime)
        self.assertNotIn("while (result.taskId == null", runtime)

    def test_pretrace_failure_is_always_persisted_to_outcomes(self):
        source = RUNTIME.read_text(encoding="utf-8")
        self.assertIn("persistPreflightFailure(context, request, config.model.id, result)", source)
        self.assertIn("val traceId = AgentTraceRuntime.start(context, request, modelId)", source)
        self.assertIn('kind = "OBSERVATION_FAILED"', source)
        self.assertIn('code = "observation.initial_failed"', source)
        self.assertIn('AgentTraceRuntime.finish(context, traceId, "FAILED", result.message, result.decisions)', source)
        self.assertIn("result.copy(taskId = traceId, classification = classification)", source)

    def test_retry_does_not_consume_attachment_twice(self):
        source = RUNTIME.read_text(encoding="utf-8")
        # The same QuickAgentConfig instance is reused for the bounded retry. Taking the pending
        # attachment again would silently drop user-provided context on attempt two.
        self.assertEqual(source.count("PendingTaskAttachment.take()"), 1)
        self.assertIn("val config = QuickAgentConfig(", source)
        self.assertGreaterEqual(source.count("agent.execute(request, config, progress)"), 2)


if __name__ == "__main__":
    unittest.main()
