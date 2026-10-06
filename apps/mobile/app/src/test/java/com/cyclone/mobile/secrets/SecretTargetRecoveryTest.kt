package com.cyclone.mobile.secrets

import com.cyclone.mobile.UiBounds
import com.cyclone.mobile.UiNodeSnapshot
import com.cyclone.mobile.UiSnapshot
import com.cyclone.mobile.uiSnapshotFromJson
import org.junit.Assert.*
import org.junit.Test

class SecretTargetRecoveryTest {
    private val field = node("old", "0/1", editable = true, password = true)
    private val title = node("title", "0/0", text = "Create a password", editable = false, password = false)
    private val before = screen(listOf(title, field))
    private val anchor = requireNotNull(SecretTargetRecovery.anchor(before, field, "scope", 7))

    @Test fun refreshKeyboardAndNodeReindexKeepTheIntendedField() {
        val moved = field.copy(id = "new", path = "0/4", focused = false,
            bounds = UiBounds(20, 150, 400, 210), text = "synthetic-hidden-value")
        val after = screen(listOf(title, moved,
            node("noise", "0/6", text = "Animation changed", editable = false, password = false)))
            .copy(fingerprint = "new-observation", screenHeight = 650)
        assertEquals(moved, SecretTargetRecovery.resolve(anchor, after, "scope", 7))
        assertFalse(anchor.toString().contains("synthetic-hidden-value"))
        val withBanner = after.copy(nodes = listOf(node("banner", "0/9", text = "New banner", editable = false, password = false)) + after.nodes)
        assertEquals(moved, SecretTargetRecovery.resolve(anchor, withBanner, "scope", 7))
    }

    @Test fun inputValuesAreNeverPartOfRetainedIdentity() {
        val sensitive = field.copy(text = "synthetic-input", contentDescription = "synthetic-description")
        val identity = requireNotNull(SecretTargetRecovery.anchor(screen(listOf(title, sensitive)), sensitive, "scope", 7))
        assertFalse(identity.toString().contains("synthetic-input"))
        assertFalse(identity.toString().contains("synthetic-description"))
    }

    @Test fun differentAppScopeAndControlHandoffAreRejected() {
        assertNull(SecretTargetRecovery.resolve(anchor, before.copy(packageName = "com.other.app"), "scope", 7))
        assertNull(SecretTargetRecovery.resolve(anchor, before, "different-profile", 7))
        assertNull(SecretTargetRecovery.resolve(anchor, before, "scope", 8))
    }

    @Test fun nextFormCannotReuseAGenericPasswordResource() {
        assertNull(SecretTargetRecovery.resolve(anchor, screen(listOf(title.copy(text = "Delete your account"), field)), "scope", 7))
    }

    @Test fun ambiguityDisabledHiddenAndOrdinaryInputsAreRejected() {
        assertNull(SecretTargetRecovery.resolve(anchor, screen(listOf(title, field, field.copy(id = "second", path = "0/2"))), "scope", 7))
        for (changed in listOf(field.copy(enabled = false), field.copy(visibleToUser = false), field.copy(password = false),
                field.copy(resourceId = "com.instagram.android:id/username"))) {
            assertNull(SecretTargetRecovery.resolve(anchor, screen(listOf(title, changed)), "scope", 7))
        }
    }

    @Test fun unlabelledFieldRecoversWhenItIsTheOnlyPasswordOnTheSameForm() {
        val generic = field.copy(resourceId = "", contentDescription = "")
        val identity = requireNotNull(SecretTargetRecovery.anchor(screen(listOf(title, generic)), generic, "scope", 7))
        assertEquals(generic, SecretTargetRecovery.resolve(identity, screen(listOf(title, generic)), "scope", 7))
        assertNull(SecretTargetRecovery.resolve(identity, screen(listOf(generic)), "scope", 7))
        assertNull(SecretTargetRecovery.anchor(screen(listOf(generic)), generic, "scope", 7))
    }

    @Test fun browserOriginChangeCannotReceiveTheSecret() {
        fun browser(url: String) = screen(listOf(title, field,
            node("url", "0/8", text = url, editable = true, password = false)
                .copy(resourceId = "com.android.chrome:id/url_bar", focused = false)))
            .copy(packageName = "com.android.chrome")
        val same = browser("https://example.com/signup")
        val identity = requireNotNull(SecretTargetRecovery.anchor(same, field, "scope", 7))
        assertEquals(field, SecretTargetRecovery.resolve(identity, browser("https://example.com/next"), "scope", 7))
        assertNull(SecretTargetRecovery.resolve(identity, browser("https://other.example/signup"), "scope", 7))
    }

    @Test fun snapshotRoundtripPreservesPasswordFlag() {
        assertTrue(uiSnapshotFromJson(before.toJson()).nodes.single { it.editable }.password)
    }

    @Test fun privacySanitizationPreservesControlFlagsButNeverPasswordValues() {
        val sensitive = before.copy(nodes = listOf(title, field.copy(text = "synthetic-secret")))
        val safe = com.cyclone.mobile.gateway.GatewayPrivacy.sanitizeAccessibilitySnapshot(sensitive.toJson())
        assertFalse(safe.toString().contains("synthetic-secret"))
        val roundtrip = uiSnapshotFromJson(safe)
        assertTrue(roundtrip.nodes.single { it.editable }.password)
        val identity = requireNotNull(SecretTargetRecovery.anchor(roundtrip, roundtrip.nodes.single { it.editable }, "scope", 7))
        assertEquals(field, SecretTargetRecovery.resolve(identity, before, "scope", 7))
        val payload = org.json.JSONObject().put("password", "synthetic-secret").put("secret", true)
        assertEquals("<redacted>", (com.cyclone.mobile.gateway.GatewayPrivacy.sanitizeDeep(payload) as org.json.JSONObject).getString("password"))
    }

    @Test fun staleBeforeDispatchIsRetriedWithoutReenteringTheValue() {
        var calls = 0
        val result = SecretFillRetry.run {
            calls++
            if (calls < 3) SecretFillExecution(false, false, "STALE_ELEMENT") else SecretFillExecution(true, true)
        }
        assertTrue(result.verified)
        assertEquals(3, calls)
    }

    @Test fun unverifiedWriteAndContextFailureAreNotRepeated() {
        for (failure in listOf(SecretFillExecution(true, false, "ASSERTION_FAILED"),
                SecretFillExecution(false, false, "STALE_SESSION"), SecretFillExecution(false, false, "HUMAN_HAS_CONTROL"))) {
            var calls = 0
            assertEquals(failure, SecretFillRetry.run { calls++; failure })
            assertEquals(1, calls)
        }
        var calls = 0
        SecretFillRetry.run { calls++; SecretFillExecution(false, false, "STALE_OBSERVATION") }
        assertEquals(3, calls)
    }

    private fun screen(nodes: List<UiNodeSnapshot>) = UiSnapshot("com.instagram.android", "Activity", 500, 900,
        1, "fingerprint", "agent", emptyList(), nodes)

    private fun node(id: String, path: String, text: String = "", editable: Boolean, password: Boolean) = UiNodeSnapshot(
        id, path, null, emptyList(), 1, 1, if (editable) "android.widget.EditText" else "android.widget.TextView",
        if (editable) "textbox" else "text", text, "", if (editable) "com.instagram.android:id/password" else "",
        UiBounds(0, 0, 400, 60), false, false, editable, false, true, false, false, false,
        true, true, true, password = password)
}
