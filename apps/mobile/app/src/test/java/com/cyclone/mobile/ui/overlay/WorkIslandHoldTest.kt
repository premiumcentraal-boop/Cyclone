package com.cyclone.mobile.ui.overlay

import com.cyclone.mobile.ui.overlay.WorkIslandHold.End
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The folded work bar stays, finished, when its task ends while it shows; it goes only when the owner acts. */
class WorkIslandHoldTest {
    private fun WorkIslandHold.working(minimized: Boolean = true, key: String = "t1", title: String = "Sending the email") =
        step(minimized, true, key, title, End.DONE, typing = false, dismissed = null)

    @Test fun `a task that ends while its bar shows leaves a finished bar`() {
        val hold = WorkIslandHold()
        assertNull(hold.working())
        val done = hold.step(true, false, "t1", null, End.DONE, typing = false, dismissed = null)
        assertEquals(WorkIslandHold.Finished("t1", End.DONE, "Sending the email"), done)
        // It stays on later frames.
        assertEquals(done, hold.step(true, false, "t1", null, End.DONE, typing = false, dismissed = null))
        assertEquals("Done" to "Sending the email", WorkIslandHold.lines(done!!))
    }

    @Test fun `how it ended is shown`() {
        for ((end, line) in listOf(End.FAILED to "Couldn't finish", End.STOPPED to "Stopped")) {
            val hold = WorkIslandHold()
            hold.working()
            val f = hold.step(true, false, null, null, end, typing = false, dismissed = null)!!
            assertEquals(line, WorkIslandHold.lines(f).first)
        }
    }

    @Test fun `no bar showing when it ended means no finished bar`() {
        val hold = WorkIslandHold()
        hold.working(minimized = false)
        assertNull(hold.step(true, false, "t1", null, End.DONE, typing = false, dismissed = null))
    }

    @Test fun `opening the stack, typing, closing it or new work ends it`() {
        fun finished() = WorkIslandHold().also { it.working(); it.step(true, false, "t1", null, End.DONE, false, null) }
        assertNull(finished().step(false, false, "t1", null, End.DONE, false, null))
        assertNull(finished().step(true, false, "t1", null, End.DONE, true, null))
        assertNull(finished().step(true, false, "t1", null, End.DONE, false, "t1"))
        assertNull(finished().step(true, true, "t2", "Next", End.DONE, false, null))
        // Once gone, it doesn't come back for the same task.
        val h = finished()
        h.step(true, false, "t1", null, End.DONE, true, null)
        assertNull(h.step(true, false, "t1", null, End.DONE, false, null))
    }
}
