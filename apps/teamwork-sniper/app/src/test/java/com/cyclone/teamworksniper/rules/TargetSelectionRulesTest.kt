package com.cyclone.teamworksniper.rules

import com.cyclone.teamworksniper.data.ShiftCode
import com.cyclone.teamworksniper.data.SniperSettings
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetSelectionRulesTest {
    private val date = LocalDate.of(2026, 9, 6)

    @Test fun nativeCalendarSelectionUsesTheClaimEngineExactRuleShape() {
        val selected = TargetSelectionRules.toggle(emptyList(), date, ShiftCode.S2).single()
        assertTrue(TargetSelectionRules.isExactTarget(selected, date, ShiftCode.S2))
        assertEquals("target:2026-09-06:S2", selected.id)
        assertTrue(TargetSelectionRules.toggle(listOf(selected), date, ShiftCode.S2).isEmpty())
    }

    @Test fun legacyExactTargetsRemainSelectableAndRemovable() {
        val legacy = TargetSelectionRules.toggle(emptyList(), date, ShiftCode.S1).single().copy(id = "overlay-target:2026-09-06:S1")
        assertTrue(TargetSelectionRules.isExactTarget(legacy, date, ShiftCode.S1))
        assertTrue(TargetSelectionRules.toggle(listOf(legacy), date, ShiftCode.S1).isEmpty())
    }

    @Test fun legacyOverlayStartsDisabled() {
        assertFalse(SniperSettings().legacyOverlayEnabled)
    }
}
