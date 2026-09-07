package com.cyclone.mobile.runtime.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceLayer2Test {
    private class MemoryPersistence(seed: List<CycloneWorkspace> = emptyList()) : WorkspacePersistence {
        var values = seed
        override fun load(): List<CycloneWorkspace> = values
        override fun save(workspaces: List<CycloneWorkspace>) { values = workspaces }
    }

    private class FakePlatform(
        var launched: Boolean = true,
        var verification: WorkspaceVerification? = null,
        var rebound: Boolean = true,
    ) : WorkspaceSwitchPlatform {
        override fun launch(workspace: CycloneWorkspace): Boolean = launched
        override fun verify(workspace: CycloneWorkspace): WorkspaceVerification? = verification
            ?: WorkspaceVerification(workspace.appPackage, workspace.androidUserId, workspace.displayId)
        override fun rebindObservation(workspace: CycloneWorkspace): Boolean = rebound
    }

    @Test fun registryPersistsNWorkspaces() {
        val persistence = MemoryPersistence()
        val registry = WorkspaceRegistry(persistence)
        registry.upsert(CycloneWorkspace("a", "Profile A", "com.example.a"))
        registry.upsert(CycloneWorkspace("b", "Profile B", "com.example.b"))
        assertEquals(2, WorkspaceRegistry(persistence).list().size)
        assertEquals("com.example.b", WorkspaceRegistry(persistence).require("b").appPackage)
    }

    @Test fun mutateLockIsExclusive() {
        val lock = WorkspaceMutateLock()
        assertTrue(lock.acquire("a"))
        assertFalse(lock.acquire("b"))
        assertEquals("a", lock.holder())
        assertTrue(lock.release("a"))
        assertTrue(lock.acquire("b"))
    }

    @Test fun switchFailsClosedOnIdentityMismatchAndLeavesNoHolder() {
        val registry = WorkspaceRegistry().apply {
            upsert(CycloneWorkspace("a", "A", "com.example.a", state = WorkspaceState.RUNNING))
            upsert(CycloneWorkspace("b", "B", "com.example.b"))
        }
        val lock = WorkspaceMutateLock().apply { acquire("a") }
        val platform = FakePlatform(verification = WorkspaceVerification("com.wrong", 0, 0))
        val result = WorkspaceSwitchEngine(registry, lock, platform).switch("b")
        assertFalse(result.ok)
        assertEquals("IDENTITY_MISMATCH", result.code)
        assertNull(lock.holder())
        assertEquals(WorkspaceState.PAUSED, registry.require("a").state)
    }

    @Test fun switchAcquiresOnlyAfterVerifiedRebind() {
        val registry = WorkspaceRegistry().apply { upsert(CycloneWorkspace("b", "B", "com.example.b")) }
        val lock = WorkspaceMutateLock()
        val platform = FakePlatform(rebound = false)
        val failed = WorkspaceSwitchEngine(registry, lock, platform).switch("b")
        assertFalse(failed.ok)
        assertEquals("OBSERVE_REBIND_FAILED", failed.code)
        assertNull(lock.holder())

        platform.rebound = true
        val success = WorkspaceSwitchEngine(registry, lock, platform).switch("b")
        assertTrue(success.ok)
        assertEquals("b", lock.holder())
        assertEquals(WorkspaceState.RUNNING, registry.require("b").state)
    }

    @Test fun gateCannotBeBypassedAfterGateReleasesMutateLock() {
        val registry = WorkspaceRegistry().apply {
            upsert(CycloneWorkspace("a", "A", "com.example.a", state = WorkspaceState.GATED))
            upsert(CycloneWorkspace("b", "B", "com.example.b"))
        }
        val lock = WorkspaceMutateLock()
        val result = WorkspaceSwitchEngine(registry, lock, FakePlatform()).switch("b")
        assertFalse(result.ok)
        assertEquals("GATE_ACTIVE", result.code)
        assertNull(lock.holder())
        assertEquals(WorkspaceState.GATED, registry.require("a").state)
    }

    @Test fun nonzeroDisplayStaysFailClosed() {
        val registry = WorkspaceRegistry().apply {
            upsert(CycloneWorkspace("b", "B", "com.example.b", displayId = 4))
        }
        val lock = WorkspaceMutateLock()
        val result = WorkspaceSwitchEngine(registry, lock, FakePlatform()).switch("b")
        assertFalse(result.ok)
        assertEquals("DISPLAY_MISMATCH", result.code)
        assertNull(lock.holder())
    }

    @Test fun rootStatusMappingIsConservative() {
        assertEquals(RootStatus.ROOTED, RootStatusMapper.map(RootSignals(suOnPath = true)))
        assertEquals(RootStatus.NOT_ROOTED, RootStatusMapper.map(RootSignals(false, false, false, false)))
        assertEquals(RootStatus.UNKNOWN, RootStatusMapper.map(RootSignals(false, null, false, false)))
    }

    @Test fun wizardStateMachineReachesDone() {
        var state = RootWizardFlow.start(RootWizardKind.TEST_SWITCH)
        assertFalse(state.done)
        state = state.advance()
        assertEquals(1, state.step)
        state = state.advance()
        assertEquals(2, state.step)
        state = state.advance()
        assertTrue(state.done)
    }
}
