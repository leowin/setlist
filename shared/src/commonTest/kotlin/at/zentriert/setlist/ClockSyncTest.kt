package at.zentriert.setlist

import at.zentriert.setlist.band.ClockSync
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClockSyncTest {
    /** Simulated host clock: host = local * (1 + drift) + offset. */
    private fun simulate(offsetMs: Double, driftPpm: Double, seconds: Int, seed: Int): ClockSync {
        val rnd = Random(seed)
        val sync = ClockSync()
        var local = 1_000_000_000L
        fun host(l: Long) = (l * (1 + driftPpm / 1e6) + offsetMs * 1e6).toLong()
        repeat(seconds * 4) {
            local += 250_000_000L + rnd.nextLong(0, 20_000_000)
            val up = 2_000_000L + rnd.nextLong(0, 8_000_000)      // 2–10 ms Wi-Fi delay up
            val down = 1_000_000L + rnd.nextLong(0, 8_000_000)    // 1–9 ms down (asymmetric)
            val t1 = local
            val t2 = host(t1 + up)
            val t3 = t2 + 50_000
            val t4 = t1 + up + 50_000 + down
            sync.add(ClockSync.Sample(t1, t2, t3, t4))
        }
        return sync
    }

    @Test
    fun offsetWithinTwoMillisecondsDespiteJitter() {
        for (seed in 1..5) {
            val sync = simulate(offsetMs = 1234.5, driftPpm = 0.0, seconds = 20, seed = seed)
            val localNow = 21_000_000_000L
            val expectedHost = (localNow + 1234.5e6).toLong()
            val error = abs(sync.toHost(localNow) - expectedHost)
            assertTrue(error < 2_000_000, "seed $seed: error ${error / 1e6} ms")
            assertTrue(sync.errorNanos < 5_000_000)
        }
    }

    @Test
    fun driftIsTrackedOverAMinute() {
        val sync = simulate(offsetMs = -300.0, driftPpm = 50.0, seconds = 60, seed = 7)  // 50 ppm = 3 ms/min
        assertTrue(abs(sync.driftPpm - 50.0) < 15.0, "drift ${sync.driftPpm}")
        val localNow = 61_000_000_000L
        val expectedHost = (localNow * (1 + 50.0 / 1e6) - 300e6).toLong()
        assertTrue(abs(sync.toHost(localNow) - expectedHost) < 2_500_000)
        assertTrue(abs(sync.toLocal(expectedHost) - localNow) < 2_500_000)
    }

    @Test
    fun packetRoundTrip() {
        val bytes = ClockSync.encode(1L shl 40, -5, 123456789)
        assertEquals(Triple(1L shl 40, -5L, 123456789L), ClockSync.decode(bytes))
    }
}
