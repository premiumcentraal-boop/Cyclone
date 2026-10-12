package com.cyclone.mobile.ui.v32

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CycloneProfileProvisioningUiTest {
    @Test fun profileFailureIsHeadlineReasonActionFixesAndTheDebugFile() {
        val page = source("CycloneProfilesPage.kt")
        assertTrue(page.contains("ProfileSetupRuntime.state.collectAsState()"))
        assertTrue(page.contains("profileSetup.issue"))
        // Plan 57 W9: the error screen lives in ProfileProblemUi.kt and is shared with setup and the rescue screen.
        assertTrue(page.contains("ProfileProblemPanel(issue, onRetry = { setup = true }"))
        val panel = sequenceOf(File("src/main/java/com/cyclone/mobile/ui/ProfileProblemUi.kt"),
            File("apps/mobile/app/src/main/java/com/cyclone/mobile/ui/ProfileProblemUi.kt")).first { it.isFile }.readText()
        assertTrue(panel.contains("Text(issue.headline"))
        assertTrue(panel.contains("Text(issue.reason"))
        assertTrue(panel.contains("Text(issue.action"))
        assertTrue(panel.contains("ProfileDebugButtons(issue)"))
        // Plan 57 W7: + always starts a new plan.
        assertTrue(page.contains("FilledIconButton(onClick = { startNewProfile() }"))
        assertFalse(page.contains("FilledIconButton(onClick = { setup = true }"))
    }

    @Test fun profileErrorUiDoesNotMisrouteRootFailuresToShizuku() {
        val page = source("CycloneProfilesPage.kt")
        assertFalse(page.contains("authorize Shizuku", ignoreCase = true))
        assertFalse(page.contains("Shizuku permission", ignoreCase = true))
    }

    private fun source(name: String): String {
        val relative = "src/main/java/com/cyclone/mobile/ui/v32/$name"
        return sequenceOf(File(relative), File("apps/mobile/app/$relative"))
            .first { it.isFile }.readText()
    }
}
