package com.cyclone.mobile.market

import com.cyclone.mobile.automation.AutomationDefinition
import com.cyclone.mobile.automation.TriggerDefinition
import com.cyclone.mobile.automation.TriggerType
import org.junit.Assert.*
import org.junit.Test

class InstagramSkillsTest {
    @Test fun startersAreValidPortableListingsWithoutAnInstallOrPrivateDetails() {
        InstagramSkills.all.forEach { skill ->
            MarketRules.validate(skill.listing)
            assertSame(skill, InstagramSkills.byId(skill.listing.id))
            assertSame(skill, InstagramSkills.forGoal(skill.listing.goal))
            assertTrue(skill.route.isNotEmpty())
            assertTrue(skill.checks.isNotEmpty())
            assertTrue(skill.limitation.isNotBlank())
            assertTrue(skill.evidence.isNotBlank())
            assertEquals(listOf(InstagramSkills.PACKAGE), skill.listing.apps)
            assertFalse(Regex("vincent|kortingcadeau|630915443", RegexOption.IGNORE_CASE).containsMatchIn(skill.toString()))
            assertTrue(skill.guidance().contains("never saved refs or coordinates"))
        }
        assertEquals(10, InstagramSkills.all.size)
        assertEquals(10, InstagramSkills.all.map { it.listing.id }.distinct().size)
        assertNull(InstagramSkills.forGoal("Please open my Instagram profile"))
        assertNull(InstagramSkills.forGoal("Someone quoted: " + InstagramSkills.all.first().marker))
    }

    @Test fun postingDefaultsToReviewAndAccountSetupCannotTakeSecretsAsRecipeInputs() {
        val post = InstagramSkills.byId(InstagramSkills.POST)!!.listing
        val goal = MarketRules.fill(post, mapOf("media" to "holiday.jpg"))
        assertTrue(goal.contains("Mode: Review only"))
        assertTrue(goal.contains("ask before Share"))
        assertTrue(goal.contains("submit once"))
        assertTrue(runCatching { MarketRules.fill(post, emptyMap()) }.isFailure)
        assertTrue(runCatching { MarketRules.fill(post, mapOf("media" to "holiday.jpg", "mode" to "silently publish")) }.isFailure)
        assertTrue(InstagramSkills.byId(InstagramSkills.ACCOUNT_SETUP)!!.listing.inputs.isEmpty())
    }

    @Test fun appLibrariesCombineSkillsAndRoutinesAndSearchReturnsTypedGroups() {
        val skills = InstagramSkills.all.map { it.listing }
        val routine = AutomationDefinition(id = "routine", name = "Evening reading", description = "Review saved posts",
            trigger = TriggerDefinition(TriggerType.MANUAL), steps = emptyList(), appPackages = listOf(InstagramSkills.PACKAGE))
        val apps = SkillLibrary.apps(mapOf(InstagramSkills.PACKAGE to "Instagram", "com.other.app" to "Other"), skills, listOf(routine))
        val instagram = apps.first { it.packageName == InstagramSkills.PACKAGE }
        assertEquals(10, instagram.skills.size)
        assertEquals(listOf(routine), instagram.routines)
        assertTrue(instagram.installed)
        val results = SkillLibrary.search(" INSTAGRAM ", apps, skills, listOf(routine))
        assertEquals(listOf(instagram), results.apps)
        assertEquals(skills, results.skills)
        assertEquals(listOf(routine), results.routines)
        val comments = SkillLibrary.search("comments", apps, skills, listOf(routine))
        assertTrue(comments.apps.isEmpty())
        assertEquals("cyclone.instagram-comments", comments.skills.single().id)
        assertTrue(comments.routines.isEmpty())
        assertTrue(SkillLibrary.search("nonexistent", apps, skills, listOf(routine)).empty)
        assertFalse(SkillLibrary.apps(emptyMap(), skills, emptyList()).single().installed)
        assertEquals("Instagram", SkillLibrary.apps(emptyMap(), skills, emptyList()).single().name)
    }

    @Test fun adviceHelpsNavigationButDoesNotPretendToBeALearnedHandle() {
        assertNull(InstagramSkills.advice("Instagram app"))
        val advice = InstagramSkills.advice("read comments")!!
        assertTrue(advice.contains("Read Instagram comments"))
        assertTrue(advice.contains("not a learned go_to handle"))
        assertTrue(advice.contains("clipboard copying"))
    }
}
