package com.cyclone.mobile.mind.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Plan 58: the decisions Lab's report: score, speed, failures, cost and calibration, with no request text. */
class DecisionLabReportTest {
    private val camera = GoldenRequest("g001", "open camera", "en", "instant", listOf("instant"), capability = "camera")
    private val pay = GoldenRequest("g002", "pay the electricity bill", "en", "mind", listOf("mind"), risks = listOf("money"))
    private val dark = GoldenRequest("g003", "turn on dark mode", "en", "flash", listOf("flash", "mind"))

    @Test fun `the report scores, times and calibrates`() {
        val report = DecisionLabReport.build("luna", "openai/gpt-6-luna-decisions", 9L, listOf(
            LabSample(camera, "instant", 300, cost = 0.0001, capability = "camera", capabilityConfidence = 0.95),
            LabSample(pay, "flash", 500, cost = 0.0001),
            LabSample(dark, null, 6000, failure = "timeout"),
        ))
        assertEquals(3, report.getInt("requests"))
        assertEquals(2, report.getInt("answered"))
        assertEquals(1, report.getJSONObject("failures").getInt("timeout"))
        assertEquals(0.0002, report.getDouble("cost"), 1e-9)
        val score = report.getJSONObject("score")
        assertEquals("g002", score.getJSONArray("riskyUnder").getString(0))
        assertEquals(1, score.getJSONObject("verdicts").getInt("undecided"))
        val bin = report.getJSONArray("calibration").getJSONObject(0)
        assertEquals(0.9, bin.getDouble("from"), 1e-9)
        assertEquals(1, bin.getInt("right"))
        assertFalse(report.toString().contains("electricity"))
    }
}
