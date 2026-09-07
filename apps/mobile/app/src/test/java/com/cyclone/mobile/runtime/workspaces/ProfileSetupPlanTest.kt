package com.cyclone.mobile.runtime.workspaces

import org.junit.Assert.*
import org.junit.Test

class ProfileSetupPlanTest {
    private val name = "Cyclone_0123456789abcdef"
    @Test fun freshManagedProfileBelongsToTheCurrentUser() {
        assertEquals(listOf("/system/bin/pm", "create-user", "--profileOf", "0", "--managed", name), ProfileSetupPlan.create(0, name))
    }
    @Test fun onlyAppInstallationIsCopiedNotAccountsOrData() {
        assertEquals(listOf("/system/bin/cmd", "package", "install-existing", "--user", "10", "com.example.app"), ProfileSetupPlan.install(10, "com.example.app"))
        assertEquals(setOf("com.example.app"), ProfileSetupPlan.packages("package:com.example.app\nError: unavailable\n"))
    }
    @Test fun recoveryRequiresTheExactManagedProfile() {
        assertEquals(10, ProfileSetupPlan.ownedUser("UserInfo{10:$name:30} running", name))
        assertNull(ProfileSetupPlan.ownedUser("UserInfo{10:Unrelated:30}", name))
        assertNull(ProfileSetupPlan.ownedUser("UserInfo{0:$name:30}", name))
        assertNull(ProfileSetupPlan.ownedUser("UserInfo{10:$name:10}", name))
        assertNull(ProfileSetupPlan.ownedUser("UserInfo{10:$name:30}\nUserInfo{11:$name:30}", name))
    }
    @Test fun malformedOrFailedCreationIsNeverReady() {
        assertEquals(10, ProfileSetupPlan.createdUser("Success: created user id 10"))
        assertNull(ProfileSetupPlan.createdUser("Error: maximum profiles reached"))
        assertNull(ProfileSetupPlan.createdUser("Success: created user id 0"))
    }
    @Test fun rejectsShellInjectionAndChangingTheMainUser() {
        assertTrue(runCatching { ProfileSetupPlan.create(0, "x;reboot") }.isFailure)
        assertTrue(runCatching { ProfileSetupPlan.install(0, "com.example.app") }.isFailure)
        assertTrue(runCatching { ProfileSetupPlan.install(10, "com.example.app;reboot") }.isFailure)
        assertTrue(runCatching { ProfileSetupPlan.shell(listOf("/system/bin/pm", "$(reboot)")) }.isFailure)
    }
}
