package com.cyclone.teamworksniper.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

enum class ShiftCode(val order: Int) {
    M1(0), M2(1), S1(2), S2(3), S3(4);

    companion object {
        fun fromRaw(raw: String?): ShiftCode? =
            raw?.trim()?.uppercase()?.let { value -> entries.firstOrNull { it.name == value } }
    }
}

data class OpenShift(
    val date: LocalDate,
    val dayOfWeek: DayOfWeek = date.dayOfWeek,
    val code: ShiftCode,
    val startTime: LocalTime? = null,
    val endTime: LocalTime? = null,
    val semanticIdentity: String,
) {
    val stableKey: String
        get() = date.toString() + "|" + code.name + "|" + (startTime ?: "?") + "|" + (endTime ?: "?")
}

enum class RuleType { EXACT, SEQUENCE, COMBINATION }

data class ShiftRule(
    val id: String,
    val name: String,
    val type: RuleType,
    val codes: List<ShiftCode>,
    val enabled: Boolean = true,
    val weekOffsets: Set<Int> = emptySet(),
    val dates: Set<LocalDate> = emptySet(),
    val days: Set<DayOfWeek> = emptySet(),
)

data class SniperSettings(val enabled: Boolean = true, val armed: Boolean = false)
data class AiSettings(val enabled: Boolean = false, val model: String = "openrouter/auto")
data class UiMapHint(val resourceId: String? = null, val semanticLabel: String? = null, val updatedAtEpochMs: Long = 0)

data class RuleMatch(
    val ruleId: String,
    val ruleName: String,
    val ruleType: RuleType,
    val date: LocalDate,
    val shifts: List<OpenShift>,
)

data class EvaluationSummary(
    val matches: List<RuleMatch>,
    val evaluatedRuleIds: List<String>,
    val missReasons: List<String>,
)

enum class TriggerSource { NOTIFICATION, MANUAL, ACCESSIBILITY }

data class TriggerEvent(
    val source: TriggerSource,
    val wallClockEpochMs: Long,
    val elapsedRealtimeMs: Long,
    val notificationTitle: String? = null,
    val notificationText: String? = null,
    val launchOutcome: String? = null,
)

data class ActivityEntry(
    val id: String,
    val triggerSource: TriggerSource,
    val triggerEpochMs: Long,
    val notificationTitle: String? = null,
    val notificationText: String? = null,
    val teamworkOpenLatencyMs: Long? = null,
    val firstComparisonLatencyMs: Long? = null,
    val evaluationDurationMs: Long? = null,
    val claimDurationMs: Long? = null,
    val openShifts: List<String> = emptyList(),
    val evaluatedRules: List<String> = emptyList(),
    val decision: String,
    val armedState: Boolean,
    val claimAttempted: Boolean,
    val claimResult: String? = null,
    val verificationResult: String? = null,
    val failureReason: String? = null,
    val decisionEngine: String = "DETERMINISTIC",
    val aiAdvice: String? = null,
)
