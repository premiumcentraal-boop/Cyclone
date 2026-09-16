package com.cyclone.mobile.agent.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskDifficultyTest {
    @Test
    fun easyIsNamedOpenOrSimpleWebsite() {
        assertEquals(TaskDifficultyTier.EASY, TaskDifficulty.classify("open Facebook"))
        assertEquals(TaskDifficultyTier.EASY, TaskDifficulty.classify("open shopify.com"))
        assertTrue(TaskDifficulty.isNamedAppOpenOnly("open Facebook"))
        assertFalse(TaskDifficulty.isNamedAppOpenOnly("open Facebook and login"))
        assertFalse(com.cyclone.mobile.agent.contract.GoalContractCompiler.isSimpleWebNavigation("open Facebook"))
    }

    @Test
    fun mediumIsOneAppWithInSceneWork() {
        assertEquals(TaskDifficultyTier.MEDIUM, TaskDifficulty.classify("open Facebook and login"))
        assertEquals(TaskDifficultyTier.MEDIUM, TaskDifficulty.classify("Open Chrome and search for Pixel 8"))
        assertEquals(TaskDifficultyTier.MEDIUM, TaskDifficulty.classify("Open Settings, then Picture-in-picture"))
    }

    @Test
    fun hardIsMultiAppOrCrossAppHandoff() {
        assertEquals(TaskDifficultyTier.HARD, TaskDifficulty.classify("open Gmail then send this to WhatsApp"))
        assertEquals(TaskDifficultyTier.HARD, TaskDifficulty.classify("open Facebook then share the post on Instagram"))
        assertEquals(TaskDifficultyTier.HARD, TaskDifficulty.classify("go to shopify.com then email the receipt with Gmail"))
    }
}
