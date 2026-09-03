package com.cyclone.mobile.agent.tools

import com.cyclone.mobile.agent.contract.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class CycloneCompoundAgentToolsTest {
    @Test fun openAppResolvesHumanLabelWithoutHardcodedPackage() {
        val runtime = FakeRuntime().apply {
            apps = listOf(CompoundInstalledApp("Chrome", "org.fixture.browser", "org.fixture.Browser"))
            actionHandler = { tool, params, goal ->
                assertEquals("phone.open_app", tool)
                assertEquals("org.fixture.browser", params.optString("package"))
                envelope(tool, goal, afterPackage = "org.fixture.browser", verification = passed("EXPECTED_PACKAGE"))
            }
        }
        val result = CycloneCompoundAgentTools(runtime).openApp("Chrome")
        assertTrue(result.optBoolean("success"))
        assertEquals("Chrome", result.optString("resolvedApp"))
        assertEquals("org.fixture.browser", result.optString("package"))
        assertTrue(result.optBoolean("packageVerified"))
        assertEquals(listOf(true), runtime.inventoryRefreshes)
    }

    @Test fun ambiguousAppNameReturnsCandidatesInsteadOfGuessing() {
        val runtime = FakeRuntime().apply {
            apps = listOf(
                CompoundInstalledApp("Files", "org.fixture.files.a", "A"),
                CompoundInstalledApp("Files", "org.fixture.files.b", "B"),
            )
        }
        val result = CycloneCompoundAgentTools(runtime).openApp("Files")
        assertFalse(result.optBoolean("success"))
        assertEquals("AMBIGUOUS_APP", result.optString("reasonCode"))
        assertEquals(2, result.optJSONArray("candidates")?.length())
        assertEquals(0, runtime.actCalls)
    }

    @Test fun openAppRequiresVerifiedAfterPackage() {
        val runtime = FakeRuntime().apply {
            apps = listOf(CompoundInstalledApp("Browser", "org.fixture.browser", "Browser"))
            actionHandler = { tool, _, goal -> envelope(tool, goal, afterPackage = "org.wrong.app", verification = passed("PAGE_KEY_CHANGED")) }
        }
        val result = CycloneCompoundAgentTools(runtime).openApp("Browser")
        assertFalse(result.optBoolean("success"))
        assertFalse(result.optBoolean("packageVerified"))
        assertEquals("org.wrong.app", result.optString("currentPackage"))
    }

    @Test fun understandPageUsesOneObservationGeneration() {
        val runtime = FakeRuntime()
        val result = CycloneCompoundAgentTools(runtime).understandPage("Open downloads")
        assertTrue(result.optBoolean("success"))
        assertEquals(1, runtime.observeCalls)
        assertEquals(runtime.page.generation, result.optLong("generation"))
        assertEquals(runtime.page.observationId, result.optString("observationId"))
    }

    @Test fun understandPageIsBoundedAndGoalRanked() {
        val runtime = FakeRuntime().apply {
            page = pageCard(controlCount = 40, longText = true)
        }
        val result = CycloneCompoundAgentTools(runtime).understandPage("downloads")
        assertTrue(result.optBoolean("success"))
        assertTrue((result.optJSONArray("controls")?.length() ?: 0) <= 24)
        assertTrue(result.optJSONObject("pageText")?.optString("text").orEmpty().length <= 4000)
        assertFalse(result.has("rawAccessibility"))
        assertEquals("downloads", result.optJSONArray("controls")?.optJSONObject(0)?.optString("label"))
    }

    @Test fun searchAcceptsMultipleQueriesOnSameGeneration() {
        val runtime = FakeRuntime()
        val result = CycloneCompoundAgentTools(runtime).search(listOf("saved episodes", "downloads", "library"), "Find offline content")
        assertTrue(result.optBoolean("success"))
        assertEquals(3, runtime.searchCalls)
        assertEquals(runtime.page.generation, result.optLong("generation"))
        assertEquals(3, result.optJSONArray("perQuery")?.length())
        assertTrue((result.optJSONArray("candidates")?.length() ?: 0) >= 1)
    }

    @Test fun inspectAcceptsMultipleIds() {
        val runtime = FakeRuntime()
        val ids = runtime.page.controls.take(3).map { it.elementId }
        val result = CycloneCompoundAgentTools(runtime).inspect(ids)
        assertTrue(result.optBoolean("success"))
        assertEquals(3, runtime.inspectCalls)
        assertEquals(3, result.optJSONArray("elements")?.length())
        assertEquals(runtime.page.generation, result.optLong("generation"))
    }

    @Test fun inspectRejectsStaleIdsDeterministically() {
        val runtime = FakeRuntime()
        val stale = "semantic:old-observation:gone"
        runtime.staleIds += stale
        val result = CycloneCompoundAgentTools(runtime).inspect(listOf(stale))
        assertFalse(result.optBoolean("success"))
        assertEquals(AgentFailureClass.STALE_OBSERVATION.name, result.optString("errorClass"))
        assertTrue(result.optBoolean("retryable"))
    }

    @Test fun visualContextDoesNotChangeActionAuthority() {
        val runtime = FakeRuntime()
        val tools = CycloneCompoundAgentTools(runtime)
        val visual = tools.visualContext("Continue")
        assertTrue(visual.optBoolean("success"))
        val id = runtime.page.controls.first().elementId
        val click = tools.click(id, "Continue")
        assertTrue(click.optBoolean("success"))
        assertEquals(1, runtime.screenshotCalls)
        assertEquals(1, runtime.actCalls)
        assertEquals(id, runtime.lastParams?.optString("elementId"))
    }

    @Test fun screenshotIsExplicitlyEvidenceOnly() {
        val result = CycloneCompoundAgentTools(FakeRuntime()).visualContext("Look closely")
        val shot = result.optJSONObject("screenshot")
        assertNotNull(shot)
        assertTrue(shot!!.optBoolean("evidenceOnly"))
        assertFalse(shot.optBoolean("provesSuccess"))
        assertFalse(result.optBoolean("screenshotProvesSuccess"))
    }

    @Test fun executorAcceptanceWithoutSemanticChangeRemainsUnverified() {
        val runtime = FakeRuntime().apply {
            actionHandler = { tool, _, goal -> envelope(tool, goal, verification = observed(), androidOk = true, executorOk = true, errorClass = AgentFailureClass.VERIFICATION_FAILED) }
        }
        val id = runtime.page.controls.first().elementId
        val result = CycloneCompoundAgentTools(runtime).click(id, "Continue")
        assertFalse(result.optBoolean("success"))
        assertTrue(result.optJSONObject("execution")?.optBoolean("androidExecutionOk") == true)
        assertEquals(AgentVerificationStatus.OBSERVED.name, result.optJSONObject("verification")?.optString("status"))
    }

    @Test fun samePageSemanticStateChangeVerifies() {
        val runtime = FakeRuntime().apply {
            actionHandler = { tool, _, goal -> envelope(tool, goal, verification = passed("CHECKED_STATE_CHANGED")) }
        }
        val result = CycloneCompoundAgentTools(runtime).click(runtime.page.controls.first().elementId, "Enable")
        assertTrue(result.optBoolean("success"))
        assertEquals("CHECKED_STATE_CHANGED", result.optJSONObject("verification")?.optString("basis"))
    }

    @Test fun brainAndGraphRecallRemainAdvisory() {
        val result = CycloneCompoundAgentTools(FakeRuntime()).recall("Open downloads")
        assertEquals("ADVISORY_ONLY", result.optString("authority"))
        assertFalse(result.optBoolean("mayAuthorizeAction"))
        assertEquals("ADVISORY_ONLY", result.optJSONObject("brain")?.optString("authority"))
        assertFalse(result.optJSONObject("appGraph")?.optBoolean("mayAuthorizeAction") == true)
    }

    @Test fun verifiedOnlySkillPolicyRejectsUnverifiedLearning() {
        assertTrue(CompoundSkillPolicy.isRunnableVerifiedEvidence("LOCAL_AGENT_VERIFIED_ROUTE", 3, 0, .82, "phone.click"))
        assertFalse(CompoundSkillPolicy.isRunnableVerifiedEvidence("AI_EXECUTION", 20, 0, .99, "phone.click"))
        assertFalse(CompoundSkillPolicy.isRunnableVerifiedEvidence("LOCAL_AGENT_VERIFIED_ROUTE", 1, 2, .82, "phone.click"))
        assertFalse(CompoundSkillPolicy.isRunnableVerifiedEvidence("LOCAL_AGENT_VERIFIED_ROUTE", 4, 0, .90, "phone.type"))
    }

    @Test fun mutationActionsRemainSerial() {
        val runtime = FakeRuntime().apply { actionDelayMs = 80 }
        val tools = CycloneCompoundAgentTools(runtime)
        val id = runtime.page.controls.first().elementId
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        repeat(2) {
            Thread {
                start.await()
                tools.click(id, "serial")
                done.countDown()
            }.start()
        }
        start.countDown()
        done.await()
        assertEquals(1, runtime.maxConcurrentActs.get())
        assertEquals(2, runtime.actCalls)
    }

    @Test fun policyAndGateCannotBeOverriddenByArguments() {
        val runtime = FakeRuntime().apply {
            actionHandler = { tool, params, goal ->
                assertFalse(params.has("force"))
                assertFalse(params.has("autoApprove"))
                envelope(tool, goal, verification = notRequired(), androidOk = false, executorOk = false, errorClass = AgentFailureClass.GATE_REQUIRED, failureLayer = AgentFailureLayer.POLICY)
            }
        }
        val id = runtime.page.controls.first().elementId
        val result = CycloneCompoundAgentTools(runtime).call("click", JSONObject().put("elementId", id).put("force", true).put("autoApprove", true))
        assertFalse(result.optBoolean("success"))
        assertEquals(AgentFailureClass.GATE_REQUIRED.name, result.optJSONObject("failure")?.optString("errorClass"))
    }

    @Test fun sensitiveCredentialTypingRemainsHumanBoundaryAndPlaintextIsNotReturned() {
        val secret = "SuperSecretPassword!"
        val runtime = FakeRuntime().apply {
            actionHandler = { tool, params, goal ->
                assertEquals("phone.type", tool)
                assertEquals(secret, params.optString("value"))
                envelope(tool, goal, verification = notRequired(), androidOk = false, executorOk = false, errorClass = AgentFailureClass.POLICY_DENIED, failureLayer = AgentFailureLayer.POLICY)
            }
        }
        val result = CycloneCompoundAgentTools(runtime).call("type", JSONObject()
            .put("elementId", runtime.page.controls.first().elementId)
            .put("value", secret)
            .put("force", true))
        assertFalse(result.optBoolean("success"))
        assertEquals(AgentFailureClass.POLICY_DENIED.name, result.optJSONObject("failure")?.optString("errorClass"))
        assertFalse(result.toString().contains(secret))
        assertFalse(result.toString().contains("force"))
    }

    @Test fun noRawBase64ScreenshotAppearsInToolText() {
        val rawBase64 = "iVBORw0KGgoAAA_FAKE_SCREEN_BYTES"
        val runtime = FakeRuntime().apply { forbiddenScreenshotPayload = rawBase64 }
        val result = CycloneCompoundAgentTools(runtime).visualContext("Look")
        assertFalse(result.toString().contains(rawBase64))
        assertFalse(result.toString().contains("pngBase64"))
        assertFalse(result.toString().contains("data:image"))
        assertFalse(result.optBoolean("base64Included"))
    }

    @Test fun runSkillUsesOnlyExposedVerifiedSkillAndStopsOnFailedVerification() {
        val runtime = FakeRuntime().apply {
            skills = listOf(CompoundVerifiedSkill(
                "micro:verified", "Open item", .85, "LOCAL_AGENT_VERIFIED_ROUTE", "open item",
                listOf(CompoundVerifiedSkillStep("phone.click", JSONObject().put("selector", JSONObject().put("text", "downloads")), "Open downloads")),
            ))
            actionHandler = { tool, _, goal -> envelope(tool, goal, verification = observed(), errorClass = AgentFailureClass.VERIFICATION_FAILED) }
        }
        val result = CycloneCompoundAgentTools(runtime).runSkill("micro:verified", "Open item")
        assertFalse(result.optBoolean("success"))
        assertTrue(result.optBoolean("stopped"))
        assertEquals(0, result.optInt("failedStep"))
    }

    private class FakeRuntime : CompoundAgentRuntimePort {
        var page = pageCard()
        var apps: List<CompoundInstalledApp> = listOf(CompoundInstalledApp("Chrome", "org.fixture.chrome", "Main"))
        var skills: List<CompoundVerifiedSkill> = emptyList()
        var history: List<AgentActionEnvelope> = emptyList()
        var observeCalls = 0
        var searchCalls = 0
        var inspectCalls = 0
        var actCalls = 0
        var screenshotCalls = 0
        var actionDelayMs = 0L
        val inventoryRefreshes = mutableListOf<Boolean>()
        val staleIds = mutableSetOf<String>()
        val concurrentActs = AtomicInteger(0)
        val maxConcurrentActs = AtomicInteger(0)
        var lastParams: JSONObject? = null
        var forbiddenScreenshotPayload: String? = null
        var actionHandler: (String, JSONObject, String) -> AgentActionEnvelope = { tool, _, goal -> envelope(tool, goal) }

        override fun observe(goal: String): AgentObservationResult {
            observeCalls++
            return AgentObservationResult(page = page)
        }

        override fun search(query: String, goal: String): AgentSearchResult {
            searchCalls++
            val first = page.controls.first()
            val candidate = first.copy(label = query, semanticName = query.replace(' ', '_'), relevance = .95)
            return AgentSearchResult(page, page.observationId, page.generation, query, goal, listOf(candidate))
        }

        override fun inspect(elementId: String): AgentInspectResult {
            inspectCalls++
            if (elementId in staleIds) return AgentInspectResult(
                page.observationId, page.generation, elementId,
                failure = AgentFailure(AgentFailureClass.STALE_OBSERVATION, AgentFailureLayer.OBSERVATION, true, "stale", "STALE_OBSERVATION"),
            )
            val candidate = page.controls.firstOrNull { it.elementId == elementId } ?: page.controls.first()
            return AgentInspectResult(page.observationId, page.generation, elementId, candidate.evidence)
        }

        override fun act(tool: String, params: JSONObject, goal: String): AgentActionEnvelope {
            actCalls++
            lastParams = JSONObject().also { copy ->
                params.keys().forEach { key -> if (key != "value") copy.put(key, params.opt(key)) }
            }
            val concurrent = concurrentActs.incrementAndGet()
            while (true) {
                val old = maxConcurrentActs.get()
                if (concurrent <= old || maxConcurrentActs.compareAndSet(old, concurrent)) break
            }
            try {
                if (actionDelayMs > 0) Thread.sleep(actionDelayMs)
                return actionHandler(tool, params, goal)
            } finally {
                concurrentActs.decrementAndGet()
            }
        }

        override fun history(): List<AgentActionEnvelope> = history
        override fun brainRecall(goal: String) = AgentKnowledgeResult(goal, JSONObject().put("memory", "verified hints"))
        override fun knownRoutes(goal: String) = AgentKnowledgeResult(goal, JSONObject().put("route", "advisory route"))

        override fun screenshotEvidence(goal: String): CompoundScreenshotEvidence {
            screenshotCalls++
            return CompoundScreenshotEvidence(page.observationId, "/tmp/shot.png", "abc123", 720, 1280, 10L)
        }

        override fun installedApps(refresh: Boolean): List<CompoundInstalledApp> {
            inventoryRefreshes += refresh
            return apps
        }

        override fun verifiedSkills(goal: String): List<CompoundVerifiedSkill> = skills
    }

    companion object {
        private fun pageCard(controlCount: Int = 4, longText: Boolean = false): AgentPageCard {
            val observation = "obs-current"
            val controls = (0 until controlCount).map { index ->
                val label = if (index == 0) "downloads" else "control $index"
                candidate(observation, index, label)
            }
            val text = if (longText) "x".repeat(10_000) else "Downloads Library Saved episodes"
            return AgentPageCard(
                observationId = observation,
                generation = 7,
                actionable = true,
                capturedAtMs = 10L,
                packageName = "org.fixture.app",
                activity = "org.fixture.Main",
                pageKey = "page-home",
                structuralKey = "struct-home",
                contentKey = "content-home",
                accessibilityFingerprint = "fp-home",
                pageSummary = JSONObject().put("summary", "Home page"),
                pageText = JSONObject().put("text", text).put("lineCount", 1).put("lines", JSONArray().put(JSONObject().put("text", text))),
                pageEvidence = JSONObject().put("evidence", "bounded"),
                controls = controls,
                nextHopHints = JSONArray(),
            )
        }

        private fun candidate(observation: String, index: Int, label: String): AgentElementCandidate {
            val id = "semantic:$observation:c$index"
            val evidence = JSONObject()
                .put("elementId", id)
                .put("observationId", observation)
                .put("label", label)
                .put("semanticName", label.replace(' ', '_'))
                .put("role", if (index == 2) "textbox" else "button")
                .put("source", if (index == 3) "semantic_supplement" else "semantic")
                .put("selected", false)
                .put("checked", false)
                .put("focused", index == 2)
                .put("editable", index == 2)
                .put("clickable", index != 2)
                .put("scrollable", false)
                .put("enabled", true)
                .put("bounds", JSONObject().put("left", 0).put("top", index * 10).put("right", 100).put("bottom", index * 10 + 8))
                .put("androidActions", JSONArray().put("ACTION_CLICK"))
            return AgentElementCandidate(id, observation, label, label.replace(' ', '_'), evidence.optString("role"), evidence.optString("source"), 1.0 - index * .01, evidence)
        }

        private fun passed(basis: String) = AgentSemanticVerification(AgentVerificationStatus.PASSED, true, true, basis)
        private fun observed() = AgentSemanticVerification(AgentVerificationStatus.OBSERVED, false, false, "NO_SEMANTIC_PROGRESS")
        private fun notRequired() = AgentSemanticVerification(AgentVerificationStatus.NOT_REQUIRED, false, false, "EXECUTION_NOT_ACCEPTED")

        private fun envelope(
            tool: String,
            goal: String,
            afterPackage: String = "org.fixture.app",
            verification: AgentSemanticVerification = passed("PAGE_KEY_CHANGED"),
            androidOk: Boolean = true,
            executorOk: Boolean = true,
            errorClass: AgentFailureClass = AgentFailureClass.NONE,
            failureLayer: AgentFailureLayer = AgentFailureLayer.NONE,
        ): AgentActionEnvelope {
            val before = pageCard()
            val after = pageCard().copy(packageName = afterPackage, pageKey = if (verification.passed) "page-after" else before.pageKey, observationId = "obs-after", generation = 8)
            return AgentActionEnvelope(
                tool = tool,
                goal = goal,
                androidExecutionOk = androidOk,
                executorReportedOk = executorOk,
                verification = verification,
                before = before,
                after = after,
                pageChanged = before.pageKey != after.pageKey,
                delta = AgentStateDelta(before.pageKey != after.pageKey, before.packageName != after.packageName, verification.passed, listOfNotNull(verification.basis), false, verification.basis ?: "none"),
                errorClass = errorClass,
                failureLayer = failureLayer,
                retryable = errorClass in setOf(AgentFailureClass.STALE_OBSERVATION, AgentFailureClass.VERIFICATION_FAILED, AgentFailureClass.GATE_REQUIRED),
                semanticSuccessClaimed = verification.semanticSuccessClaimed,
                beforeObservationId = before.observationId,
                afterObservationId = after.observationId,
                observationGeneration = before.generation,
                learning = AgentLearningResult(verification.passed && errorClass == AgentFailureClass.NONE, if (verification.passed) "verified" else "not verified"),
                safeMessage = if (errorClass == AgentFailureClass.NONE) null else errorClass.name,
            )
        }
    }
}
