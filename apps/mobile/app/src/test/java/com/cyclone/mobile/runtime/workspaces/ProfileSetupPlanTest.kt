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
    @Test fun createUserIsManagedProfileOfParentAndNeverDeletesUsers() {
        val command = ProfileSetupPlan.create(0, name)
        assertEquals("/system/bin/pm", command.first())
        assertEquals("create-user", command[1])
        assertTrue("--profileOf" in command)
        assertEquals("0", command[command.indexOf("--profileOf") + 1])
        assertTrue("--managed" in command)
        assertFalse("remove-user" in command)
        assertFalse("delete-user" in command)
        assertTrue(command.none { "delete" in it.lowercase() || "remove" in it.lowercase() })
        assertEquals(
            listOf("/system/bin/pm", "create-user", "--profileOf", "11", "--managed", name),
            ProfileSetupPlan.create(11, name),
        )
    }
    @Test fun installExistingCopiesAppsNotAccounts() {
        val command = ProfileSetupPlan.install(10, "com.example.app")
        assertTrue("install-existing" in command)
        assertFalse(command.any { "account" in it.lowercase() })
        assertEquals("com.example.app", command.last())
        assertEquals(setOf("com.example.app"), ProfileSetupPlan.packages("package:com.example.app\n"))
        assertEquals(emptySet<String>(), ProfileSetupPlan.packages("Account {name=user@example.com}\n"))
    }
    @Test fun shellTokensStayAllowlisted() {
        val create = ProfileSetupPlan.create(0, name)
        val install = ProfileSetupPlan.install(10, "com.example.app")
        assertEquals(create.joinToString(" "), ProfileSetupPlan.shell(create))
        assertEquals(install.joinToString(" "), ProfileSetupPlan.shell(install))
        assertTrue(runCatching { ProfileSetupPlan.shell(emptyList()) }.isFailure)
        assertTrue(runCatching { ProfileSetupPlan.shell(listOf("/system/bin/pm", "create-user;reboot")) }.isFailure)
        assertTrue(runCatching { ProfileSetupPlan.shell(listOf("/system/bin/pm", "a|b")) }.isFailure)
        assertTrue(runCatching { ProfileSetupPlan.shell(listOf("echo hello")) }.isFailure)
        assertTrue(runCatching { ProfileSetupPlan.shell(listOf("/system/bin/pm", "\$HOME")) }.isFailure)
        assertTrue(runCatching { ProfileSetupPlan.shell(listOf("sh", "`reboot`")) }.isFailure)
        assertTrue(runCatching { ProfileSetupPlan.shell(listOf("cmd", "&&reboot")) }.isFailure)
    }
}
