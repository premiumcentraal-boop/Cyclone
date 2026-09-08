package com.cyclone.mobile.gesture

import org.junit.Assert.assertTrue
import org.junit.Test

class HumanGestureEnginePerformanceTest {
    @Test
    fun synthesisP99StaysBelowHalfMillisecondAfterWarmup() {
        val viewport = GestureBounds(0f, 0f, 1080f, 2400f)
        val start = GesturePoint(40f, 2200f)
        val end = GesturePoint(900f, 200f)
        val target = GestureBounds(700f, 300f, 940f, 430f)

        repeat(5_000) { seed ->
            HumanGestureEngine.planSwipe(start, end, viewport, HumanizeProfile.NORMAL, seed.toLong())
            HumanGestureEngine.planTap(target, viewport, HumanizeProfile.NORMAL, seed.toLong())
        }

        val samples = LongArray(20_000)
        var checksum = 0L
        for (i in samples.indices) {
            val before = System.nanoTime()
            val stroke = HumanGestureEngine.planSwipe(start, end, viewport, HumanizeProfile.NORMAL, i.toLong())
            val tap = HumanGestureEngine.planTap(target, viewport, HumanizeProfile.NORMAL, i.toLong())
            samples[i] = System.nanoTime() - before
            checksum = checksum xor stroke.durationMs xor tap.durationMs
        }
        samples.sort()
        val p50 = percentile(samples, 0.50)
        val p95 = percentile(samples, 0.95)
        val p99 = percentile(samples, 0.99)
        println("HumanGestureEngine pair latency ns: p50=$p50 p95=$p95 p99=$p99 checksum=$checksum")

        assertTrue(
            "p99 planning latency $p99 ns exceeded 500,000 ns target",
            p99 < 500_000L,
        )
    }

    private fun percentile(sorted: LongArray, fraction: Double): Long =
        sorted[((sorted.size - 1) * fraction).toInt()]
}
