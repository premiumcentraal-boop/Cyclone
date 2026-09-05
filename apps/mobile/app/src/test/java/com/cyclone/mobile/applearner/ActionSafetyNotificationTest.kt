package com.cyclone.mobile.applearner

import org.junit.Assert.assertEquals
import org.junit.Test

class ActionSafetyNotificationTest {
    @Test
    fun blockRemainsSafeWhenModalContextContainsSend() {
        assertEquals(
            ActionRisk.SAFE,
            ActionSafetyPolicy.classify(
                label = "Block",
                contentDescription = "www.ad.nl wants to send you notifications",
            ),
        )
    }

    @Test
    fun allowNotificationRemainsConsequential() {
        assertEquals(
            ActionRisk.CONSEQUENTIAL,
            ActionSafetyPolicy.classify(
                label = "Allow",
                contentDescription = "www.ad.nl wants to send you notifications",
            ),
        )
    }

    @Test
    fun realSendActionStillRemainsConsequential() {
        assertEquals(
            ActionRisk.CONSEQUENTIAL,
            ActionSafetyPolicy.classify(
                label = "Send message",
                contentDescription = "Notifications are enabled",
            ),
        )
    }
}
