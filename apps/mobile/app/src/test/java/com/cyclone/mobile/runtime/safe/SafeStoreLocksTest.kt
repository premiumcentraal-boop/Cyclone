package com.cyclone.mobile.runtime.safe

import com.cyclone.mobile.identity.HiveKeyring
import com.cyclone.mobile.identity.IdentityVault
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SafeStoreLocksTest {
    @Test fun coherentSealedCaptureIsSerializedInTheExistingHiveThenIdentityOrder() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val second = CountDownLatch(1)
        val first = Thread { HiveKeyring.forSafe { IdentityVault.forSafe { entered.countDown(); release.await(5, TimeUnit.SECONDS) } } }
        first.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
        val other = Thread { HiveKeyring.forSafe { IdentityVault.forSafe { second.countDown() } } }
        other.start()
        try { assertFalse(second.await(100, TimeUnit.MILLISECONDS)) }
        finally { release.countDown(); first.join(5000); other.join(5000) }
        assertEquals(0L, second.count)
        assertFalse(first.isAlive || other.isAlive)
    }
    @Test fun failedCaptureReleasesBothLocks() {
        assertThrows(IllegalStateException::class.java) { HiveKeyring.forSafe { IdentityVault.forSafe { error("fixture capture failure") } } }
        val completed = CountDownLatch(1)
        val thread = Thread { HiveKeyring.forSafe { IdentityVault.forSafe { completed.countDown() } } }
        thread.start()
        assertTrue(completed.await(5, TimeUnit.SECONDS)); thread.join(5000)
    }
}
