package com.cyclone.mobile.agent.contract

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalContractTest {
    @Test fun redditLoginRequiresAuthenticatedEvidenceInRequestedBrowser() {
        val contract = GoalContractCompiler.compile("open reddit.com on Chrome and login for me")
        val host = page("com.android.chrome", "https://reddit.com/")
        assertFalse(GoalContractCompiler.evaluate(contract, host, emptyList()).satisfied)
        assertFalse(GoalContractCompiler.evaluate(contract,
            host.copy(controls = listOf(control("Log in", "button"))), emptyList()).satisfied)
        val authenticated = host.copy(controls = listOf(control("Log out", "menuitem")))
        assertTrue(GoalContractCompiler.evaluate(contract, authenticated, emptyList()).satisfied)
        assertFalse(GoalContractCompiler.evaluate(contract,
            authenticated.copy(packageName = "org.mozilla.firefox"), emptyList()).satisfied)
        assertFalse(GoalContractCompiler.evaluate(contract,
            authenticated.copy(controls = authenticated.controls + control("Log in", "button")), emptyList()).satisfied)
    }

    @Test fun loginTextOrOldLoginOutcomeDoesNotProveCurrentAuthentication() {
        val contract = GoalContractCompiler.compile("go to reddit.com and sign in")
        val authenticated = page("com.android.chrome", "reddit.com", listOf(control("Log out", "button")))
        val article = page("com.android.chrome", "reddit.com article explaining how to log out")
        assertFalse(GoalContractCompiler.evaluate(contract, article,
            listOf(outcome("phone.click", contract.sourceGoal, article, authenticated))).satisfied)
        val wrongHost = page("com.android.chrome", "example.com", authenticated.controls)
        assertFalse(GoalContractCompiler.evaluate(contract, wrongHost, emptyList()).satisfied)
    }

    @Test fun openingLoginFormDoesNotRequireSubmittingCredentials() {
        assertFalse(GoalContractCompiler.compile("open reddit.com and click login").requirements.any {
            it.kind == GoalRequirementKind.AUTHENTICATED_SESSION
        })
        assertTrue(GoalContractCompiler.compile("open reddit.com and log in").requirements.any {
            it.kind == GoalRequirementKind.AUTHENTICATED_SESSION
        })
    }

    @Test fun onlySimpleHostNavigationQualifiesForLocalCompletion() {
        listOf("open ad.nl", "Go to https://www.ad.nl/", "please navigate to victor.ceo").forEach {
            assertTrue(GoalContractCompiler.isSimpleWebNavigation(it))
        }
        listOf("open ad.nl and scroll down", "open ad.nl then click login", "open ad.nl/news/article", "read ad.nl", "search for ad.nl").forEach {
            assertFalse(GoalContractCompiler.isSimpleWebNavigation(it))
        }
    }

    @Test
    fun shortHostRequiresCurrentHostEvidenceAfterBrowserLaunch() {
        val goal = "open ad.nl"
        val before = page("com.cyclone.mobile", "Cyclone AI mode")
        val after = page("com.android.chrome", "News home")
        val history = listOf(outcome("phone.launch_intent", goal, before, after))

        val evaluation = GoalContractCompiler.evaluate(
            GoalContractCompiler.compile(goal),
            after,
            history,
        )

        assertFalse(evaluation.satisfied)
        assertTrue(GoalContractCompiler.evaluate(GoalContractCompiler.compile(goal),
            page("com.android.chrome", "https://ad.nl/"), history).satisfied)
    }

    @Test fun hostMentionInCycloneAndLookalikeDomainsCannotCompleteNavigation() {
        val contract = GoalContractCompiler.compile("open ad.nl")
        listOf(
            page("com.cyclone.mobile", "You: open ad.nl"),
            page("com.android.chrome", "https://bad.nl/"),
            page("com.android.chrome", "https://ad.nl.evil.com/"),
        ).forEach { assertFalse(GoalContractCompiler.evaluate(contract, it, emptyList()).satisfied) }
    }

    @Test fun oldSuccessfulLaunchDoesNotCompleteOnCurrentUnrelatedSite() {
        val before = page("com.cyclone.mobile", "Cyclone")
        val loaded = page("com.android.chrome", "https://ad.nl/")
        val unrelated = page("com.android.chrome", "https://example.com/")
        assertFalse(GoalContractCompiler.evaluate(GoalContractCompiler.compile("open ad.nl"), unrelated,
            listOf(outcome("phone.launch_intent", "open ad.nl", before, loaded))).satisfied)
    }

    @Test fun addressBarOverridesHostMentionInSearchResults() {
        val bar = control("https://google.com/search", "edittext").copy(
            evidence = JSONObject().put("resourceId", "com.android.chrome:id/url_bar"))
        val results = page("com.android.chrome", "Search results for ad.nl", listOf(bar))
        assertFalse(GoalContractCompiler.evaluate(GoalContractCompiler.compile("open ad.nl"), results, emptyList()).satisfied)
    }

    @Test
    fun shortHostDoesNotCompleteFromUnrelatedBrowserStateWithoutVerifiedLaunch() {
        val goal = "open ad.nl"
        val unrelated = page("com.android.chrome", "example.com unrelated page")

        val evaluation = GoalContractCompiler.evaluate(
            GoalContractCompiler.compile(goal),
            unrelated,
            emptyList(),
        )

        assertFalse(evaluation.satisfied)
    }

    @Test
    fun cookieDismissalUsesVerifiedDisappearanceAndToleratesTargetTypo() {
        val goal = "click akloord for the cookies"
        val before = page(
            "com.android.chrome",
            "Cookie preferences",
            controls = listOf(control("Akkoord", "button"), control("Weigeren", "button")),
        )
        val after = page(
            "com.android.chrome",
            "Nieuws",
            controls = listOf(control("Menu openen", "button")),
        )
        val history = listOf(outcome("phone.click", goal, before, after, basis = "SEMANTIC_PAGE_CHANGED"))

        val evaluation = GoalContractCompiler.evaluate(
            GoalContractCompiler.compile(goal),
            after,
            history,
        )

        assertTrue(evaluation.satisfied)
    }

    @Test
    fun webAndScrollGoalRequiresBothVerifiedEffects() {
        val goal = "go to victor.ceo and scroll down the page"
        val before = page("com.cyclone.mobile", "Cyclone")
        val web = page("com.android.chrome", "victor.ceo")
        val launch = outcome("phone.launch_intent", goal, before, web)

        val incomplete = GoalContractCompiler.evaluate(
            GoalContractCompiler.compile(goal),
            web,
            listOf(launch),
        )
        assertFalse(incomplete.satisfied)

        val scrolled = page("com.android.chrome", "victor.ceo portfolio lower section")
        val complete = GoalContractCompiler.evaluate(
            GoalContractCompiler.compile(goal),
            scrolled,
            listOf(launch, outcome("phone.scroll", goal, web, scrolled, basis = "SEMANTIC_PAGE_CHANGED")),
        )
        assertTrue(complete.satisfied)
    }

    @Test
    fun genericSequenceTargetsFinalGoalSegment() {
        val goal = "Open Settings, then Picture-in-picture"
        val root = page("com.android.settings", "Android Settings Apps Network Battery")
        val pip = page("com.android.settings", "Picture in picture app access")

        assertFalse(
            GoalContractCompiler.evaluate(GoalContractCompiler.compile(goal), root, emptyList()).satisfied,
        )
        assertTrue(
            GoalContractCompiler.evaluate(GoalContractCompiler.compile(goal), pip, emptyList()).satisfied,
        )
    }

    @Test
    fun hiddenLogoutCannotProveAuthenticatedSession() {
        val hidden = control("Log out", "button").copy(
            evidence = JSONObject().put("visibleToUser", false).put("enabled", true),
        )
        val current = page("com.android.chrome", "reddit.com", listOf(hidden))
        val result = GoalContractCompiler.evaluate(
            GoalContractCompiler.compile("open reddit.com on Chrome and login for me"), current, emptyList(),
        )
        assertFalse(result.satisfied)
    }

    private fun outcome(
        tool: String,
        goal: String,
        before: AgentPageCard,
        after: AgentPageCard,
        basis: String = "PACKAGE_CHANGED",
    ) = AgentActionEnvelope(
        tool = tool,
        goal = goal,
        androidExecutionOk = true,
        executorReportedOk = true,
        verification = AgentSemanticVerification(
            status = AgentVerificationStatus.PASSED,
            passed = true,
            semanticSuccessClaimed = true,
            basis = basis,
        ),
        before = before,
        after = after,
        pageChanged = before.pageKey != after.pageKey,
        delta = AgentStateDelta(
            pageChanged = before.pageKey != after.pageKey,
            packageChanged = before.packageName != after.packageName,
            accessibilityChanged = true,
            semanticStateChanges = listOf(basis),
            goalLabelAppeared = false,
            summary = basis,
        ),
        errorClass = AgentFailureClass.NONE,
        failureLayer = AgentFailureLayer.NONE,
        retryable = false,
        semanticSuccessClaimed = true,
        beforeObservationId = before.observationId,
        afterObservationId = after.observationId,
        observationGeneration = after.generation,
        learning = AgentLearningResult(true, "test"),
    )

    private fun page(
        packageName: String,
        summary: String,
        controls: List<AgentElementCandidate> = emptyList(),
    ) = AgentPageCard(
        observationId = "obs-${summary.hashCode()}",
        generation = 1,
        actionable = true,
        capturedAtMs = 1,
        packageName = packageName,
        activity = "Activity",
        pageKey = "page-${summary.hashCode()}",
        structuralKey = "struct-${summary.hashCode()}",
        contentKey = "content-${summary.hashCode()}",
        accessibilityFingerprint = "fp-${summary.hashCode()}",
        pageSummary = JSONObject().put("summary", summary),
        pageText = JSONObject().put("text", summary),
        pageEvidence = JSONObject().put("rawNodeCount", controls.size),
        controls = controls,
        nextHopHints = JSONArray(),
    )

    private fun control(label: String, role: String) = AgentElementCandidate(
        elementId = "element-${label.hashCode()}",
        observationId = "obs",
        label = label,
        semanticName = label,
        role = role,
        source = "semantic",
        relevance = 1.0,
        evidence = JSONObject()
            .put("resourceId", "id/${label.lowercase().replace(' ', '_')}")
            .put("clickable", true),
    )
}
