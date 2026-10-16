package com.cyclone.mobile.runtime.safe

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class SafeStartupGateTest {
    @After fun release() { SafeStartupGate.complete() }
    @Test fun noRuntimeStoreOpensBeforeRecoveryCompletes() {
        SafeStartupGate.initialize(true)
        var opens = 0
        SafeStartupGate.whenReady { opens++ }
        assertTrue(SafeStartupGate.blocked)
        assertEquals(0, opens)
        SafeStartupGate.complete()
        assertFalse(SafeStartupGate.blocked)
        assertEquals(1, opens)
        SafeStartupGate.complete()
        assertEquals(1, opens)
    }
    @Test fun existingReadyInstallStartsImmediately() {
        SafeStartupGate.initialize(false)
        var ready = false
        SafeStartupGate.whenReady { ready = true }
        assertTrue(ready)
    }
    @Test fun oneOptionalInitializationFailureDoesNotConcealOtherCallbacks() {
        SafeStartupGate.initialize(true)
        var ready = false
        SafeStartupGate.whenReady { error("unavailable") }
        SafeStartupGate.whenReady { ready = true }
        SafeStartupGate.complete()
        assertTrue(ready)
    }
}
