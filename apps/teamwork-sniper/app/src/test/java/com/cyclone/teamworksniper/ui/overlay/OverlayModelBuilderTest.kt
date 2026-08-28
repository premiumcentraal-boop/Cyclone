package com.cyclone.teamworksniper.ui.overlay

import com.cyclone.teamworksniper.data.ActivityEntry
import com.cyclone.teamworksniper.data.RuleType
import com.cyclone.teamworksniper.data.ShiftCode
import com.cyclone.teamworksniper.data.ShiftRule
import com.cyclone.teamworksniper.data.TriggerSource
import com.cyclone.teamworksniper.rules.RuleJson
import com.cyclone.teamworksniper.rules.TargetSelectionRules
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayModelBuilderTest {
    private val date = LocalDate.of(2026, 9, 1)
    private val bounds = OverlayRect(0, 100, 1080, 330)
    private val anchor = OverlayRect(199, 176, 1006, 234)

    @Test fun hollowSelectedAndUnselectedRoundTripThroughExistingRules() {
        val empty = build(emptyList()).single().choices.first { it.code == ShiftCode.S2 }
        assertFalse(empty.selected)
        val selectedRules = TargetSelectionRules.toggle(emptyList(), date, ShiftCode.S2)
        val persisted = RuleJson.decode(RuleJson.encode(selectedRules))
        assertTrue(build(persisted).single().choices.first { it.code == ShiftCode.S2 }.selected)
        assertTrue(TargetSelectionRules.toggle(persisted, date, ShiftCode.S2).isEmpty())
    }

    @Test fun assignedShiftIsNotOffered() {
        val assigned = ObservedOverlayShift(
            ShiftCode.S1, null, null, ObservedShiftStatus.ASSIGNED, "semantic-s1", anchor,
        )
        val codes = build(emptyList(), shifts = listOf(assigned)).single().choices.map { it.code }
        assertFalse(ShiftCode.S1 in codes)
    }

    @Test fun verifiedClaimIsDistinctFromUnknown() {
        val success = activity("TARGET_NO_LONGER_OPEN", null)
        val unknown = activity("UNVERIFIED", "Calendar unavailable")
        assertTrue(build(emptyList(), listOf(success)).single().choices.first { it.code == ShiftCode.S2 }.claimed)
        assertFalse(build(emptyList(), listOf(unknown)).single().choices.first { it.code == ShiftCode.S2 }.claimed)
    }

    @Test fun openAndSelectedStatesCoexist() {
        val open = ObservedOverlayShift(
            ShiftCode.S3, null, null, ObservedShiftStatus.OPEN, "semantic-s3", anchor,
        )
        val rule = ShiftRule("s3", "S3", RuleType.EXACT, listOf(ShiftCode.S3), dates = setOf(date))
        val choice = build(listOf(rule), shifts = listOf(open)).single().choices.first { it.code == ShiftCode.S3 }
        assertTrue(choice.openNow)
        assertTrue(choice.selected)
    }

    @Test fun geometryNeverBecomesSelectionIdentity() {
        val first = build(emptyList()).single().choices.first()
        val moved = build(emptyList(), dayBounds = OverlayRect(55, 400, 950, 700)).single().choices.first()
        assertEquals(first.selectionKey, moved.selectionKey)
        assertNotEquals(bounds, OverlayRect(55, 400, 950, 700))
    }

    private fun build(
        rules: List<ShiftRule>,
        activity: List<ActivityEntry> = emptyList(),
        shifts: List<ObservedOverlayShift> = emptyList(),
        dayBounds: OverlayRect = bounds,
    ) = OverlayModelBuilder().build(
        ObservedOverlaySchedule(
            date,
            listOf(ObservedOverlayDay(date, dayBounds, anchor, shifts)),
        ),
        rules,
        activity,
    )

    private fun activity(verification: String, failure: String?) = ActivityEntry(
        id = verification,
        triggerSource = TriggerSource.MANUAL,
        triggerEpochMs = 1,
        openShifts = listOf("2026-09-01 S2 16:55"),
        evaluatedRules = listOf("s2"),
        decision = "CLAIM_FLOW",
        armedState = true,
        claimAttempted = true,
        claimResult = "CONFIRMED_ACTION_SENT",
        verificationResult = verification,
        failureReason = failure,
    )
}
