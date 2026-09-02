package com.cyclone.camera.ui

import com.cyclone.camera.engine.CameraMode
import com.cyclone.camera.engine.EngineState
import com.cyclone.camera.engine.FakeEngineApi
import com.cyclone.camera.engine.SourceType
import com.cyclone.camera.engine.VideoSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraContractTest {
    @Test
    fun `fake engine arms and injects after source and camera selection`() {
        val engine = FakeEngineApi()
        engine.setSource(VideoSource(SourceType.FILE, "content://video", null, "Demo", "1920×1080", 30))
        engine.setMode(CameraMode.FRONT)
        engine.arm(true)

        assertEquals(EngineState.INJECTING, engine.getStatus())

        engine.arm(false)
        assertEquals(EngineState.OFF, engine.getStatus())
    }

    @Test
    fun `stream validation accepts only supported network schemes`() {
        assertTrue(isValidStreamUrl("rtmp://studio.example/live"))
        assertTrue(isValidStreamUrl("rtsp://10.0.0.3/camera"))
        assertFalse(isValidStreamUrl("https://example.com/video"))
        assertFalse(isValidStreamUrl("rtmp://"))
    }
}
