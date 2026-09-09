package com.cyclone.mobile.runtime.workspaces

import org.junit.Assert.*
import org.junit.Test

class ProfileStructural427Test {
    private val token = "Cyclone_0123456789abcdef"
    @Test fun fullUserCommandsAreTypedAndAllowOwnerReturn() {
        assertEquals("/system/bin/pm create-user $token", ProfileSetupPlan.shell(ProfileSetupPlan.createSecondaryUser(token)))
        assertEquals("/system/bin/am switch-user 12", ProfileSetupPlan.shell(ProfileSetupPlan.switchUser(12)))
        assertEquals("/system/bin/am switch-user 0", ProfileSetupPlan.shell(ProfileSetupPlan.switchUser(0)))
    }
    @Test fun failedCreationResumesExactJournaledFullUser() {
        val user = ProfileUserRecord(12, token, false, false, null, false, false)
        val journal = ProfileJournalSnapshot(token, 0, 12, setOf("com.android.chrome"), ProfileSetupStage.PROFILE_CREATED, true)
        assertEquals(ProfileRecoveryDecision.Resume(user), ProfileRecovery.resolve(journal, 0, listOf(user)))
        assertTrue(ProfileRecovery.resolve(journal, 0, listOf(user.copy(name = "Corporate"))) is ProfileRecoveryDecision.Fail)
    }
    @Test fun incompleteJournalRecoversOnlyItsGeneratedIdentity() {
        val user = ProfileUserRecord(12, token, false, false, null, false, false)
        val journal = ProfileJournalSnapshot(token, 0, null, emptySet(), ProfileSetupStage.PLANNED, true)
        assertEquals(ProfileRecoveryDecision.Resume(user), ProfileRecovery.resolve(journal, 0, listOf(user)))
        assertEquals(ProfileRecoveryDecision.Create, ProfileRecovery.resolve(journal, 0, listOf(user.copy(name = "Rooted Clone"))))
    }
    @Test fun unrelatedManagedProfileCannotBecomeOwnedFullUser() {
        val user = ProfileUserRecord(12, token, true, true, 0, false, false)
        assertFalse(ProfileRecovery.validOwned(user, 0, true))
    }
    @Test fun cycloneAutomaticallyIncludedWithoutRootManagerCloning() {
        assertEquals(setOf("com.cyclone.mobile", "com.android.chrome", "com.instagram.android"),
            ProfileRequiredPackages.resolve("com.cyclone.mobile", setOf("com.android.chrome", "com.instagram.android")))
    }
    @Test fun secondaryUserLimitIsTyped() {
        assertEquals(ProfileSetupFailureKind.MAX_USERS_REACHED,
            ProfileFailureClassifier.fromCommand(ProfileSetupOperation.CREATE_SECONDARY_USER, 1,
                "Error: maximum number of users")?.kind)
    }
}
