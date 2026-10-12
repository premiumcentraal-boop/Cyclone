package com.cyclone.mobile.secrets

import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

class LocalSignupPasswordTest {
    private var now = 1_800_000_000_000L
    private val place = "package:com.instagram.android"
    @Before fun setup() { SealedDelivery.resetForTests(); SealedDelivery.clock = { now } }
    @After fun cleanup() { SealedDelivery.resetForTests(); SealedDelivery.clock = { System.currentTimeMillis() } }

    @Test fun passwordIsWipedAtHandoffAndBoundToOneRunAndApp() {
        val password = "ExampleOnly!".toCharArray()
        SealedDelivery.holdLocalPassword("mexample1", place, password)
        assertTrue(password.all { it == '\u0000' })
        assertNull(SealedDelivery.take("mother", "password", place))
        assertNull(SealedDelivery.take("mexample1", "password", "package:com.other.app"))
        val taken = SealedDelivery.take("mexample1", "password", place)!!
        taken.lease.consume { assertArrayEquals("ExampleOnly!".toCharArray(), it); SecretFillExecution(true, true) }
        assertNull(SealedDelivery.take("mexample1", "password", place))
    }

    @Test fun unusedPasswordExpiresAndEndingTheRunRemovesIt() {
        SealedDelivery.holdLocalPassword("mexample1", place, "ExampleOnly!".toCharArray())
        now += 30 * 60_000L
        assertNull(SealedDelivery.take("mexample1", "password", place))
        assertFalse(SealedDelivery.has("mexample1", "password"))
        SealedDelivery.holdLocalPassword("mexample2", place, "ExampleOnly!".toCharArray())
        SealedDelivery.finish("mexample2")
        assertFalse(SealedDelivery.has("mexample2", "password"))
    }

    @Test fun invalidInputAndDuplicateDeliveryAreRejectedAndStillWiped() {
        listOf(Triple("../run", place, "ExampleOnly!"), Triple("mexample1", "package:invalid", "ExampleOnly!"),
            Triple("mexample1", place, "short")).forEach { (run, target, value) ->
            val chars = value.toCharArray()
            assertTrue(runCatching { SealedDelivery.holdLocalPassword(run, target, chars) }.isFailure)
            assertTrue(chars.all { it == '\u0000' })
        }
        SealedDelivery.holdLocalPassword("mexample1", place, "FirstValue!".toCharArray())
        val second = "SecondValue!".toCharArray()
        assertTrue(runCatching { SealedDelivery.holdLocalPassword("mexample1", place, second) }.isFailure)
        assertTrue(second.all { it == '\u0000' })
        SealedDelivery.take("mexample1", "password", place)!!.lease.consume {
            assertArrayEquals("FirstValue!".toCharArray(), it); SecretFillExecution(true, true)
        }
    }
}
