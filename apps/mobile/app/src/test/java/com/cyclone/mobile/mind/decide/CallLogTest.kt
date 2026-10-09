package com.cyclone.mobile.mind.decide

import com.cyclone.mobile.decisions.DecisionsFailure
import com.cyclone.mobile.decisions.DecisionsResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Plan 58: every decisions call is counted per provider and board, with no request or answer text. */
class CallLogTest {
    @Test fun `calls are counted per provider and board`() {
        val records = listOf(
            CallRecord.of(1, "luna", "watch", DecisionsResult.Ok("""{"answers":{},"usage":{"input_tokens":400,"output_tokens":0,"cost":0.00004}}""", 300)),
            CallRecord.of(2, "luna", "watch", DecisionsResult.Ok("""{"answers":{}}""", 500)),
            CallRecord.of(3, "luna", "watch", DecisionsResult.Failed(DecisionsFailure.TIMEOUT, null, 6000)),
            CallRecord.of(4, "jev", "board0", DecisionsResult.Failed(DecisionsFailure.REJECTED, 400, 90)),
        )
        val s = CallLog.summary(records)
        assertEquals(2, s.length())
        val jev = s.getJSONObject(0)
        assertEquals("jev", jev.getString("provider"))
        assertEquals(0.0, jev.getDouble("answerRate"), 0.0)
        assertEquals(1, jev.getJSONObject("failures").getInt("rejected"))
        val luna = s.getJSONObject(1)
        assertEquals(3, luna.getInt("calls"))
        assertEquals(0.667, luna.getDouble("answerRate"), 1e-9)
        assertEquals(1, luna.getJSONObject("failures").getInt("timeout"))
        assertEquals(0.00004, luna.getDouble("cost"), 1e-9)
        assertEquals(records, CallLog.decode(CallLog.encode(records)))
        assertFalse(CallLog.encode(records).contains("answers"))
    }

    @Test fun `the log stays bounded`() {
        val many = (1..1_200).map { CallRecord(it.toLong(), "jev", "board0", 100, CallRecord.OK) }
        assertEquals(CallLog.MAX_KEPT, CallLog.decode(CallLog.encode(many)).size)
    }
}
