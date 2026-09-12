package com.cyclone.mobile.agent.tools

import com.cyclone.mobile.PhoneToolResult
import com.cyclone.mobile.agent.contract.*
import com.cyclone.mobile.ai.CycloneAiAccessPolicy
import com.cyclone.mobile.ai.CycloneAiAccessProfile
import com.cyclone.mobile.applearner.PageContext
import com.cyclone.mobile.gateway.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class CycloneAgentEnvironmentTest {
    @Test fun oneCaptureFeedsLegacyAndExecutableViewsDuringBannerArrival() {
        val initial = observation("first", "home", "fp1")
        val banner = observation("second", "consent", "fp2")
        val runtime = FakeRuntime(initial, null).apply { captureQueue.addLast(banner) }
        val bridge = com.cyclone.mobile.agent.integration.CyclonePcParityBridge(CycloneAgentEnvironment(runtime))
        val first = bridge.observe("login")!!
        assertSame(initial.page, first.legacyPage)
        assertEquals(first.pageKey, first.legacyPage!!.pageKey)
        assertEquals(1, runtime.captureQueue.size)
        assertTrue(first.controls.all { it.observationId == first.observationId })
        val second = bridge.observe("login")!!
        assertSame(banner.page, second.legacyPage)
        assertEquals(second.pageKey, second.legacyPage!!.pageKey)
        assertTrue(runtime.captureQueue.isEmpty())
    }

    @Test fun screenshotSkewRotationAndChangedSurfaceFailClosed() {
        val env = CycloneAgentEnvironment(FakeRuntime(observation("first", "home", "fp1"), null))
        val card = env.observe("login").page!!.let { it.copy(pageEvidence = JSONObject()
            .put("captureWidth", 100).put("captureHeight", 200)) }
        val shot = JSONObject().put("sessionId", card.sessionId).put("displayId", card.displayId)
            .put("width", 100).put("height", 200)
        val check = com.cyclone.mobile.agent.ObservationCoherence
        assertTrue(check.accepts(card, card.copy(observationId = "new"), shot, 100))
        assertFalse(check.accepts(card, card, shot, 1501))
        assertFalse(check.accepts(card, card.copy(packageName = "other"), shot, 100))
        assertFalse(check.accepts(card, card.copy(displayId = 7), shot, 100))
        assertFalse(check.accepts(card, card.copy(contentKey = "banner"), shot, 100))
        assertFalse(check.accepts(card, card.copy(pageEvidence = JSONObject().put("captureWidth", 200).put("captureHeight", 100)), shot, 100))
    }
    @Test fun cookieRejectionSurvivesCompactControlLimit() {
        val before = observationWithHiddenRawTarget()
        val controls = before.payload.getJSONArray("semanticControls")
        controls.put(JSONObject().put("elementId", "semantic:${before.id}:cookies")
            .put("observationId", before.id).put("label", "Reject Optional Cookies")
            .put("role", "button").put("clickable", true))
        val env = CycloneAgentEnvironment(FakeRuntime(before, null))
        val page = env.observe("open reddit.com and login for me").page!!
        assertTrue(page.controls.size <= 36)
        assertNotNull(com.cyclone.mobile.ai.CookieInterruptionPolicy().next(page, "open reddit.com and login for me"))
    }

    @Test fun executorSuccessWithoutSemanticChangeRemainsUnverifiedAndUnlearned() {
        val before = observation("obs-1", "home", "fp-1")
        val runtime = FakeRuntime(before, observation("obs-2", "home", "fp-1"))
        val env = CycloneAgentEnvironment(runtime)
        env.observe("Continue")
        val result = env.act("phone.click", JSONObject().put("elementId", elementId(before)), "Continue")
        assertTrue(result.androidExecutionOk)
        assertFalse(result.verification.passed)
        assertEquals(AgentVerificationStatus.OBSERVED, result.verification.status)
        assertEquals(AgentFailureClass.VERIFICATION_FAILED, result.errorClass)
        assertEquals(0, runtime.learningCalls)
    }

    @Test fun samePageSelectedCheckedFocusedAndEditableTextChangesAreVerifiedProgress() {
        val before = semantic(textState = "t1")
        listOf(
            semantic(selected = true, textState = "t1") to "SELECTED_STATE_CHANGED",
            semantic(checked = true, textState = "t1") to "CHECKED_STATE_CHANGED",
            semantic(focused = true, textState = "t1") to "FOCUSED_STATE_CHANGED",
            semantic(textState = "t2") to "EDITABLE_TEXT_CHANGED",
        ).forEach { (after, basis) ->
            val result = AgentSemanticVerifier.verify("phone.type", true, false, false, "", "", before, after)
            assertTrue(result.passed)
            assertEquals(basis, result.basis)
        }
    }

    @Test fun pageTransitionRequiresSemanticSurfaceChange() {
        val churnOnly = AgentSemanticVerifier.verify(
            "phone.click", true, false, false, "", "",
            semantic(pageKey = "home", fingerprint = "fp-1"),
            semantic(pageKey = "settings", fingerprint = "fp-2"),
        )
        assertFalse(churnOnly.passed)
        assertEquals("NO_SEMANTIC_PROGRESS", churnOnly.basis)

        val semanticTransition = AgentSemanticVerifier.verify(
            "phone.click", true, false, false, "", "",
            semantic(pageKey = "home", fingerprint = "fp-1", label = "Continue"),
            semantic(pageKey = "settings", fingerprint = "fp-2", label = "Settings"),
        )
        assertTrue(semanticTransition.passed)
        assertEquals("SEMANTIC_PAGE_CHANGED", semanticTransition.basis)
    }

    @Test fun elementIndexFromCurrentObservationResolvesToElementId() {
        val before = observation("obs-idx", "home", "fp-idx")
        val evidence = before.elements.values.first().evidence.put("elementIndex", 1).put("element_index", 1)
        val runtime = FakeRuntime(before, observation("obs-idx-after", "settings", "fp-idx-2", "Settings"))
        val env = CycloneAgentEnvironment(runtime)
        env.observe("Continue")
        val result = env.act("phone.click", JSONObject().put("elementIndex", 1), "Continue")
        assertTrue(result.androidExecutionOk)
        assertEquals(1, runtime.executionCalls)
        assertEquals(1, evidence.optInt("elementIndex"))
    }

    @Test fun staleElementIdExpiresImmediatelyAfterMutation() {
        val before = observation("obs-1", "home", "fp-1")
        val runtime = FakeRuntime(before, observation("obs-2", "settings", "fp-2"))
        val env = CycloneAgentEnvironment(runtime)
        env.observe("Continue")
        val stale = elementId(before)
        env.act("phone.click", JSONObject().put("elementId", stale), "Continue")
        val second = env.act("phone.click", JSONObject().put("elementId", stale), "Continue")
        assertEquals(AgentFailureClass.STALE_OBSERVATION, second.errorClass)
        assertTrue(second.retryable)
        assertEquals(1, runtime.executionCalls)
    }

    @Test fun freshRelocatePublishesNewUsableElementId() {
        val first = observation("obs-1", "home", "fp-1")
        val runtime = FakeRuntime(first, observation("obs-2", "settings", "fp-2"))
        val env = CycloneAgentEnvironment(runtime)
        env.observe("Continue")
        env.act("phone.click", JSONObject().put("elementId", elementId(first)), "Continue")
        val fresh = observation("obs-3", "settings", "fp-2", "Open details")
        runtime.captureQueue.addLast(fresh)
        runtime.afterObservation = observation("obs-4", "details", "fp-3", "Done")
        val candidate = env.locate("Open details").candidates.first()
        val result = env.act("phone.click", JSONObject().put("elementId", candidate.elementId), "Open details")
        assertEquals(fresh.id, candidate.observationId)
        assertTrue(result.androidExecutionOk)
        assertEquals(AgentFailureClass.NONE, result.errorClass)
        assertEquals(2, runtime.executionCalls)
    }

    @Test fun semanticSearchFindsCandidateAbsentFromCompactControls() {
        val source = observationWithHiddenRawTarget()
        val env = CycloneAgentEnvironment(FakeRuntime(source, source))
        env.observe("")
        val result = env.search("Deep hidden target", "Find target")
        assertTrue(result.candidates.any { it.label == "Deep hidden target" && it.source == "raw_accessibility" })
    }

    @Test fun screenshotDoesNotChangeActionAuthorityOrScope() {
        val before = observation("obs-1", "home", "fp-1")
        val runtime = FakeRuntime(before, observation("obs-2", "settings", "fp-2"))
        val env = CycloneAgentEnvironment(runtime)
        env.observe("Continue")
        val shot = env.screenshot("Visual evidence")
        val result = env.act("phone.click", JSONObject().put("elementId", elementId(before)), "Continue")
        assertNotNull(shot.filePath)
        assertEquals(before.id, shot.observationId)
        assertTrue(result.androidExecutionOk)
        assertEquals(1, runtime.executionCalls)
    }

    @Test fun agentInputCannotOverrideCycloneAiPolicyDenial() {
        val params = JSONObject()
            .put("user_authorized", true)
            .put("force", true)
            .put("selector", JSONObject().put("text", "Delete account"))
        val decision = CycloneAiAccessPolicy.evaluate(CycloneAiAccessProfile.FULL, "phone.click", params)
        assertFalse(decision.allowed)
        assertEquals("LOCAL_CONFIRMATION_REQUIRED", decision.reasonCode)
    }

    @Test fun unverifiedActionNeverCallsVerifiedLearningPort() {
        val before = observation("obs-1", "home", "fp-1")
        val runtime = FakeRuntime(before, observation("obs-2", "home", "fp-1"))
        val env = CycloneAgentEnvironment(runtime)
        env.observe("Continue")
        val result = env.act("phone.click", JSONObject().put("elementId", elementId(before)), "Continue")
        assertFalse(result.learning.recorded)
        assertEquals(0, runtime.learningCalls)
    }

    @Test fun verifiedSafePageRouteEntersCanonicalLearningPort() {
        val before = observation("obs-1", "home", "fp-1", "Continue")
        val runtime = FakeRuntime(before, observation("obs-2", "settings", "fp-2", "Settings"))
        val env = CycloneAgentEnvironment(runtime)
        env.observe("Continue")
        val result = env.act("phone.click", JSONObject().put("elementId", elementId(before)), "Continue")
        assertTrue(result.verification.passed)
        assertEquals("SEMANTIC_PAGE_CHANGED", result.verification.basis)
        assertTrue(result.learning.recorded)
        assertEquals(1, runtime.learningCalls)
    }

    @Test fun unsupportedExplicitExpectationCannotPromoteExecutorSuccess() {
        val unchanged = observation("obs-expect", "home", "fp-expect")
        val result = GatewayV33ActionAdapter.verifyAfterState(
            tool = "phone.type",
            expectedPackage = "",
            goalLabel = "Continue",
            beforeObservation = unchanged,
            afterObservation = observation("obs-expect-after", "home", "fp-expect"),
            androidExecutionOk = true,
            executorAssertionFailed = false,
            explicitExpectation = true,
        )
        assertFalse(result.passed)
        assertEquals(AgentVerificationStatus.OBSERVED, result.status)
        assertEquals("NO_SEMANTIC_PROGRESS", result.basis)
    }

    @Test fun reusedObservationCannotVerifyBack() {
        val same = observation("same", "home", "fp")
        val result = GatewayV33ActionAdapter.verifyAfterState(
            tool = "phone.back", expectedPackage = "", goalLabel = "Back",
            beforeObservation = same, afterObservation = same, androidExecutionOk = true,
            executorAssertionFailed = false, explicitExpectation = false)
        assertFalse(result.passed)
        assertEquals(AgentVerificationStatus.FAILED, result.status)
        assertEquals("OBSERVATION_IDENTITY_MISMATCH", result.basis)
    }

    @Test fun pcFacingVerifierRejectsIdentityChurnAndAcceptsSemanticWitness() {
        assertFalse(GatewayV33ActionAdapter.verifiedByAfterState(
            "phone.click", "", "home", "fp-1", "pkg", "home", "fp-1",
        ))
        assertFalse(GatewayV33ActionAdapter.verifiedByAfterState(
            "phone.click", "", "home", "fp-1", "pkg", "settings", "fp-2",
        ))
        assertTrue(GatewayV33ActionAdapter.verifiedByAfterState(
            "phone.click", "", "home", "fp-1", "pkg", "settings", "fp-2",
            goalLabel = "Settings",
            afterHaystack = "Settings",
        ))
    }

    @Test fun afterObservationFailureCannotClaimSemanticSuccess() {
        val before = observation("obs-1", "home", "fp-1")
        val env = CycloneAgentEnvironment(FakeRuntime(before, null))
        env.observe("Continue")
        val result = env.act("phone.click", JSONObject().put("elementId", elementId(before)), "Continue")
        assertTrue(result.androidExecutionOk)
        assertEquals(AgentVerificationStatus.DEGRADED, result.verification.status)
        assertFalse(result.semanticSuccessClaimed)
        assertEquals(AgentFailureClass.AFTER_OBSERVATION_FAILED, result.errorClass)
    }

    @Test fun recentHistoryKeepsNewestEightOutcomesInChronologicalOrder() {
        val first = observation("obs-0", "page-0", "fp-0")
        val runtime = FakeRuntime(first, first)
        val env = CycloneAgentEnvironment(runtime)
        repeat(12) { i ->
            val before = observation("obs-$i", "page-$i", "fp-$i")
            runtime.captureQueue.addLast(before)
            runtime.afterObservation = observation("after-$i", "next-$i", "next-fp-$i", "Next")
            env.observe("Step $i")
            val current = runtime.current()!!
            env.act("phone.click", JSONObject().put("elementId", elementId(current)), "Step $i")
        }
        assertEquals((4..11).map { "Step $it" }, env.history().takeLast(8).map { it.goal })
        assertEquals("Step 11", env.history().last().goal)
    }

    private fun semantic(
        pageKey: String = "home",
        fingerprint: String = "fp-1",
        selected: Boolean = false,
        checked: Boolean = false,
        focused: Boolean = false,
        textState: String? = null,
        label: String = "Continue",
    ) = SemanticObservationState(
        "pkg", pageKey, fingerprint, "Home $label",
        listOf(SemanticElementState(
            "control", label, if (textState == null) "button" else "textbox",
            selected, checked, focused, textState,
        )),
    )

    private fun observation(
        id: String,
        pageKey: String,
        fingerprint: String,
        label: String = "Continue",
    ): GatewayObservation {
        val elementId = "semantic:$id:control"
        val evidence = JSONObject()
            .put("elementId", elementId)
            .put("observationId", id)
            .put("source", "semantic")
            .put("controlKey", "control")
            .put("label", label)
            .put("semanticName", label.lowercase().replace(' ', '_'))
            .put("role", "button")
            .put("selector", JSONObject().put("text", label).put("clickable", true))
            .put("resourceId", "id/control")
            .put("selected", false)
            .put("checked", false)
            .put("focused", false)
        val element = GatewayElement(elementId, "semantic", label, evidence.getString("semanticName"), "button", evidence)
        val page = PageContext(
            pageKey = pageKey,
            packageName = "pkg",
            className = "pkg.Main",
            title = pageKey,
            structuralKey = "struct-$pageKey",
            contentKey = "content-$pageKey",
            controls = emptyList(),
            observationCount = 1,
            firstSeenAt = 1,
            lastSeenAt = 1,
        )
        val payload = JSONObject()
            .put("activity", "pkg.Main")
            .put("accessibilityFingerprint", fingerprint)
            .put("pageSummary", JSONObject().put("summary", pageKey))
            .put("pageText", JSONObject().put("text", "$pageKey $label").put("lines", JSONArray()).put("lineCount", 1))
            .put("pageEvidence", JSONObject().put("pageKey", pageKey))
            .put("nextHopHints", JSONArray())
            .put("semanticControls", JSONArray().put(evidence))
        return GatewayObservation(id, 1, page, payload, mapOf(elementId to element))
    }

    private fun observationWithHiddenRawTarget(): GatewayObservation {
        val source = observation("obs-search", "home", "fp-search")
        val elements = linkedMapOf<String, GatewayElement>()
        val semantic = JSONArray()
        repeat(40) { index ->
            val id = "semantic:" + source.id + ":c$index"
            val evidence = JSONObject()
                .put("elementId", id)
                .put("observationId", source.id)
                .put("source", "semantic")
                .put("controlKey", "c$index")
                .put("label", "Visible control $index")
                .put("semanticName", "visible_control_$index")
                .put("role", "button")
                .put("selector", JSONObject().put("text", "Visible control $index"))
            elements[id] = GatewayElement(id, "semantic", "Visible control $index", "visible_control_$index", "button", evidence)
            if (index < 36) semantic.put(evidence)
        }
        val rawId = "raw:" + source.id + ":deep"
        val raw = JSONObject()
            .put("elementId", rawId)
            .put("observationId", source.id)
            .put("source", "raw_accessibility")
            .put("label", "Deep hidden target")
            .put("text", "Deep hidden target")
            .put("role", "button")
            .put("resourceId", "id/deep")
        elements[rawId] = GatewayElement(rawId, "raw_accessibility", "Deep hidden target", "deep_hidden_target", "button", raw)
        return source.copy(
            payload = JSONObject(source.payload.toString()).put("semanticControls", semantic),
            elements = elements,
        )
    }

    private fun elementId(observation: GatewayObservation) = observation.elements.keys.first()

    private class FakeRuntime(
        initial: GatewayObservation,
        var afterObservation: GatewayObservation?,
    ) : CycloneAgentRuntimePort {
        var currentObservation: GatewayObservation? = null
        val captureQueue = ArrayDeque<GatewayObservation>().apply { addLast(initial) }
        var executionCalls = 0
        var learningCalls = 0

        override fun capture(): GatewayObservation {
            val value = if (captureQueue.isEmpty()) currentObservation ?: error("No observation") else captureQueue.removeFirst()
            currentObservation = value
            return value
        }
        override fun current() = currentObservation
        override fun search(observation: GatewayObservation, query: String, limit: Int) =
            GatewayObservationAdapter.search(observation, query, limit)
        override fun element(observation: GatewayObservation, elementId: String) =
            GatewayObservationAdapter.element(observation, elementId)
        override fun screenshot(goal: String) = JSONObject()
            .put("filePath", "/tmp/evidence.png").put("width", 720).put("height", 1280).put("timestampMs", 10)
        override fun readinessFailure(): AgentFailure? = null
        override fun policyFailure(tool: String, params: JSONObject): AgentFailure? = null
        override fun execute(requestId: String, tool: String, params: JSONObject): PhoneToolResult {
            executionCalls += 1
            return PhoneToolResult(requestId, tool, true, 1, 2)
        }
        override fun captureAfter(tool: String, params: JSONObject, before: GatewayObservation): GatewayObservation? {
            currentObservation = afterObservation
            return afterObservation
        }
        override fun verify(
            tool: String,
            expectedPackage: String,
            goalLabel: String,
            before: GatewayObservation,
            after: GatewayObservation?,
            androidExecutionOk: Boolean,
            executorAssertionFailed: Boolean,
            explicitExpectation: Boolean,
        ) = AgentSemanticVerifier.verify(
            tool, androidExecutionOk, executorAssertionFailed, explicitExpectation,
            expectedPackage, goalLabel, semantic(before), after?.let(::semantic),
        )
        override fun recordLearning(
            goal: String,
            tool: String,
            params: JSONObject,
            before: GatewayObservation,
            after: GatewayObservation?,
            androidExecutionOk: Boolean,
            verification: AgentSemanticVerification,
        ): AgentLearningResult {
            learningCalls += 1
            return AgentLearningResult(true, "Verified semantic route recorded", JSONObject().put("recorded", true))
        }
        override fun brainRecall(goal: String) = JSONObject().put("goal", goal)
        override fun knownRoutes(goal: String) = JSONObject().put("goal", goal)

        private fun semantic(observation: GatewayObservation) = SemanticObservationState(
            observation.page.packageName,
            observation.page.pageKey,
            observation.payload.optString("accessibilityFingerprint"),
            observation.page.title + " " + observation.elements.values.joinToString(" ") { it.label } + " " +
                observation.payload.optJSONObject("pageText")?.optString("text").orEmpty(),
            observation.elements.values.map { element ->
                val e = element.evidence
                SemanticElementState(
                    e.optString("controlKey").ifBlank { element.semanticName + "|" + element.role },
                    element.label,
                    element.role,
                    e.optBoolean("selected"),
                    e.optBoolean("checked"),
                    e.optBoolean("focused"),
                    e.optString("textStateDigest").takeIf { it.isNotBlank() && it != "null" },
                )
            },
        )
    }
}
