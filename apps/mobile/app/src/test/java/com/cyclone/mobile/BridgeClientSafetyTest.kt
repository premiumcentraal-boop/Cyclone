package com.cyclone.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeClientSafetyTest {
    @Test
    fun websocketUrlValidationAcceptsOnlyExplicitWsSchemesWithHosts() {
        assertTrue(BridgeClient.isSupportedWebSocketUrl("ws://127.0.0.1:8787/mobile"))
        assertTrue(BridgeClient.isSupportedWebSocketUrl("wss://example.com/mobile"))

        assertFalse(BridgeClient.isSupportedWebSocketUrl(""))
        assertFalse(BridgeClient.isSupportedWebSocketUrl("not a url"))
        assertFalse(BridgeClient.isSupportedWebSocketUrl("http://127.0.0.1:8787/mobile"))
        assertFalse(BridgeClient.isSupportedWebSocketUrl("ws:///missing-host"))
    }
}
