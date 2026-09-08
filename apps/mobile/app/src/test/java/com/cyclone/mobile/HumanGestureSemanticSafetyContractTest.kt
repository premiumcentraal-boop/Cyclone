package com.cyclone.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM contract guard for Android-service ordering that cannot be instantiated by plain local tests.
 * These assertions inspect the real production source and fail if authorization/semantic-native calls
 * are moved behind Human Gesture dispatch. Pure SessionContract behavior is covered separately.
 */
class HumanGestureSemanticSafetyContractTest {
    private val service by lazy { productionSource("com/cyclone/mobile/CycloneAccessibilityService.kt") }
    private val executor by lazy { productionSource("com/cyclone/mobile/PhoneToolExecutor.kt") }
    private val dispatch by lazy { productionSource("com/cyclone/mobile/HumanGestureDispatch.kt") }

    @Test
    fun `semantic click and select stay ahead of coordinate fallback`() {
        val click = slice(service, "fun click(", "private fun activateNode")
        assertOrdered(click, "ClickGateIntercept.decide", "HumanGestureDispatch.tap(")
        assertOrdered(click, "activateNode(targetLive", "HumanGestureDispatch.tap(")
        assertFalse("HumanGestureRuntimePolicy.resolve" in click)

        val activate = slice(service, "private fun activateNode", "private fun clickActivatableRelative")
        assertTrue("ACTION_CLICK" in activate)
        assertTrue("ACTION_SELECT" in activate)
        assertOrdered(activate, "ACTION_CLICK", "ACTION_SELECT")
    }

    @Test
    fun `semantic long click stays ahead of human gesture fallback`() {
        val longPress = slice(service, "fun longPress(\n        selector", "fun typeEditable")
        assertOrdered(longPress, "ACTION_LONG_CLICK", "HumanGestureDispatch.longPress(")
    }

    @Test
    fun `semantic scroll stays ahead of safely grounded synthesized fallback`() {
        val scroll = slice(executor, "private fun foregroundScroll(", "private fun workspaceGestureEvidence")
        assertOrdered(scroll, "s.scroll(selector, forward)", "s.swipe(")
        assertOrdered(scroll, "firstOrNull { it.scrollable }", "s.swipe(")
        assertOrdered(scroll, "node.bounds.height < 96", "s.swipe(")
    }

    @Test
    fun `normal profile cannot jump ahead of semantic click`() {
        val click = slice(service, "fun click(", "private fun activateNode")
        assertTrue("preference = humanize" in click)
        assertOrdered(click, "activateNode(targetLive", "preference = humanize")
        assertOrdered(click, "clickActivatableAncestor", "preference = humanize")
    }

    @Test
    fun `foreground authorization checks remain before dispatch`() {
        val internal = slice(executor, "private fun executeInternal", "private fun currentFingerprint")
        assertOrdered(internal, "DeviceState.controller != DeviceState.Controller.AGENT", "dispatch(context, request")
        assertOrdered(internal, "DeviceState.requireFreshObservation", "dispatch(context, request")
        assertOrdered(internal, "isDuplicateAction(request)", "dispatch(context, request")
    }

    @Test
    fun `workspace stale policy and mutation lock remain ahead of input`() {
        val workspace = slice(executor, "private fun executeWorkspace", "private fun executeInternal")
        assertOrdered(workspace, "GatewayObservationStore.current(scope.sessionId)", "runtime.input(")
        assertOrdered(workspace, "STALE_SESSION", "runtime.input(")
        assertOrdered(workspace, "CycloneAiAccessPolicy.evaluate", "runtime.input(")
        assertOrdered(workspace, "GateClassifier.classify", "runtime.input(")
        assertTrue("synchronized(mutationLock)" in executor)
    }

    @Test
    fun `off remains a legacy straight compatibility branch`() {
        val tap = slice(dispatch, "fun tap(", "fun longPress(")
        val swipe = slice(dispatch, "fun swipe(", "private fun viewport")
        assertOrdered(tap, "profile == HumanizeProfile.OFF", "legacyTap(")
        assertOrdered(swipe, "profile == HumanizeProfile.OFF", "legacySwipe(")
    }

    private fun assertOrdered(source: String, first: String, second: String) {
        val firstIndex = source.indexOf(first)
        val secondIndex = source.indexOf(second)
        assertTrue("Missing '$first'", firstIndex >= 0)
        assertTrue("Missing '$second'", secondIndex >= 0)
        assertTrue("Expected '$first' before '$second'", firstIndex < secondIndex)
    }

    private fun slice(source: String, start: String, end: String): String {
        val from = source.indexOf(start)
        val to = source.indexOf(end, from + start.length)
        assertTrue("Missing source start '$start'", from >= 0)
        assertTrue("Missing source end '$end'", to > from)
        return source.substring(from, to)
    }

    private fun productionSource(relative: String): String {
        val candidates = listOf(
            File("src/main/java/$relative"),
            File("apps/mobile/app/src/main/java/$relative"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Production source not found for $relative from ${File(".").absolutePath}")
    }
}
