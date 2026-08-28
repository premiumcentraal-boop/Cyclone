package com.cyclone.teamworksniper.ai

import com.cyclone.teamworksniper.data.OpenShift
import com.cyclone.teamworksniper.data.RuleMatch
import com.cyclone.teamworksniper.data.RuleType
import com.cyclone.teamworksniper.data.ShiftCode
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class AiDecisionPolicyTest {
    private fun match(id: String, day: Int, code: ShiftCode, hour: Int) =
        RuleMatch(
            ruleId = id,
            ruleName = id,
            ruleType = RuleType.EXACT,
            date = LocalDate.of(2026, 8, day),
            shifts = listOf(
                OpenShift(
                    date = LocalDate.of(2026, 8, day),
                    code = code,
                    startTime = LocalTime.of(hour, 0),
                    semanticIdentity = id,
                ),
            ),
        )

    @Test
    fun deterministicOrderPrefersEarlierSafeShift() {
        val later = match("later", 30, ShiftCode.M2, 10)
        val earlier = match("earlier", 29, ShiftCode.M1, 8)
        assertEquals(listOf(earlier, later), AiDecisionPolicy.deterministic(listOf(later, earlier)))
    }

    @Test
    fun validAiPriorityOnlyReordersExistingSafeCandidates() {
        val first = match("first", 29, ShiftCode.M1, 8)
        val second = match("second", 30, ShiftCode.M2, 10)
        val reordered = AiDecisionPolicy.applyPriority(
            listOf(first, second),
            AiDecisionPolicy.candidateId(second),
        )
        assertEquals(listOf(second, first), reordered)
    }

    @Test
    fun inventedAiCandidateFallsBackToDeterministicOrder() {
        val first = match("first", 29, ShiftCode.M1, 8)
        val second = match("second", 30, ShiftCode.M2, 10)
        assertEquals(
            listOf(first, second),
            AiDecisionPolicy.applyPriority(listOf(second, first), "invented"),
        )
    }
}
