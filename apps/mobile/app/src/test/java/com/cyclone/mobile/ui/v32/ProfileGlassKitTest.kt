package com.cyclone.mobile.ui.v32

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The profile redesign's kit: words that never stack letter by letter, faces that tell profiles apart, one look. */
class ProfileGlassKitTest {
    private fun source(path: String) = sequenceOf(
        File("src/main/java/com/cyclone/mobile/$path"),
        File("apps/mobile/app/src/main/java/com/cyclone/mobile/$path"),
    ).first { it.isFile }.readText()

    @Test fun buttonLabelsStayOnOneLine() {
        val kit = source("ui/v32/ProfileGlassKit.kt")
        val button = kit.substring(kit.indexOf("internal fun ProfileButton("), kit.indexOf("internal fun ProfileAvatar("))
        assertTrue(button.contains("maxLines = 1"))
        assertTrue(button.contains("softWrap = false"))
        assertTrue(button.contains("overflow = TextOverflow.Ellipsis"))
        val status = kit.substring(kit.indexOf("internal fun ProfileStatus("), kit.indexOf("internal enum class ProfileNoticeKind"))
        assertTrue(status.contains("softWrap = false"))
    }

    @Test fun initialsSkipTheSharedWordProfile() {
        assertEquals("C", profileInitial("Profile C"))
        assertEquals("W", profileInitial("work"))
        assertEquals("1", profileInitial("Profile 12"))
        assertEquals("?", profileInitial("   "))
    }

    @Test fun appCountsReadNaturally() {
        assertEquals("No apps yet", ProfileWords.apps(0))
        assertEquals("1 app", ProfileWords.apps(1))
        assertEquals("6 apps", ProfileWords.apps(6))
        assertEquals("1 profile", ProfileWords.profiles(1))
    }

    @Test fun profileSurfacesUseTheGlassKitNotOldMaterialCards() {
        listOf(
            "ui/v32/CycloneProfilesPage.kt",
            "ui/v32/ProfileLifecycleUi.kt",
            "ui/ProfileProblemUi.kt",
            "ui/ProfileCornerstonesUi.kt",
            "ui/ProfileSetup429.kt",
            "ui/ProfileRescueBar.kt",
        ).forEach { path ->
            val text = source(path)
            assertFalse("$path still uses AlertDialog", text.contains("AlertDialog("))
            assertFalse("$path still uses Material cards", text.contains("CardDefaults"))
            assertFalse("$path still uses FilterChip", text.contains("FilterChip("))
            assertFalse("$path still uses OutlinedButton", text.contains("OutlinedButton("))
        }
    }

    @Test fun makeRoomNamesTheRestartAndKeepsRestoreDefault() {
        val ui = source("ui/ProfileProblemUi.kt")
        assertTrue(ui.contains("\"One restart\""))
        assertTrue(ui.contains("if (unlock)"))
        assertTrue(ui.contains("\"Restore default\""))
        assertTrue(ui.contains("ProfileRoom.choices(room.used)"))
        assertTrue(ui.contains("\"Restart to finish\""))
    }
}
