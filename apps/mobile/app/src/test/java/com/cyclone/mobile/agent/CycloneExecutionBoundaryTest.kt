package com.cyclone.mobile.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CycloneExecutionBoundaryTest {
    private class Tools : CycloneAgentTools {
        var executions = 0
        override fun observe(state: CycloneTaskState) = CycloneObservation("same", "same")
        override fun execute(state: CycloneTaskState, observation: CycloneObservation, turn: CycloneModelTurn): CycloneToolResult {
            executions++
            return CycloneToolResult(ok = false)
        }
        override fun verify(state: CycloneTaskState, observation: CycloneObservation, turn: CycloneModelTurn, toolResult: CycloneToolResult) = CycloneVerificationResult(false, false)
    }

    @Test fun cancellationDuringPlanningCannotExecuteReturnedAction() {
        val tools = Tools()
        var cancelled = false
        val model = object : CycloneAgentModel {
            override fun plan(state: CycloneTaskState, observation: CycloneObservation): CyclonePlanResult {
                cancelled = true
                return CyclonePlanResult.Valid(CycloneModelTurn(CycloneModelDirective.ACT, "click"))
            }
        }
        val result = CycloneLocalAgent("test", model, tools, externallyCancelled = { cancelled }).runUntilBoundary()
        assertTrue(result is CycloneAgentRunResult.Cancelled)
        assertEquals(0, tools.executions)
    }

    @Test fun deadlineExpiredDuringPlanningCannotExecuteReturnedAction() {
        val tools = Tools()
        var clock = 0L
        val model = object : CycloneAgentModel {
            override fun plan(state: CycloneTaskState, observation: CycloneObservation): CyclonePlanResult {
                clock = 100L
                return CyclonePlanResult.Valid(CycloneModelTurn(CycloneModelDirective.ACT, "click"))
            }
        }
        val result = CycloneLocalAgent("test", model, tools,
            convergence = CycloneConvergencePolicy(taskTimeoutMs = 100), now = { clock }).runUntilBoundary()
        assertEquals(CycloneTaskClassification.NON_CONVERGENCE, result.state.finalClassification)
        assertEquals(0, tools.executions)
    }

    @Test fun failedActionBetweenDoneClaimsDoesNotResetCompletionBudget() {
        val tools = Tools()
        var calls = 0
        val model = object : CycloneAgentModel {
            override fun plan(state: CycloneTaskState, observation: CycloneObservation): CyclonePlanResult {
                calls++
                return CyclonePlanResult.Valid(if (calls % 2 == 1)
                    CycloneModelTurn(CycloneModelDirective.DONE)
                else CycloneModelTurn(CycloneModelDirective.ACT, "retry"))
            }
        }
        val result = CycloneLocalAgent("test", model, tools).runUntilBoundary()
        assertEquals(CycloneTaskClassification.NON_CONVERGENCE, result.state.finalClassification)
        assertEquals(3, calls)
        assertEquals(1, tools.executions)
    }
}
