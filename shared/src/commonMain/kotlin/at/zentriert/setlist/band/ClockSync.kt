package at.zentriert.setlist.band

import kotlin.math.abs

/**
 * Estimates the host's monotonic clock from NTP-style round trips (t1 client send, t2 host receive,
 * t3 host send, t4 client receive). Only the round trips with the smallest RTT count, the offset is
 * their median and the drift a regression over them – robust against Wi-Fi jitter.
 */
class ClockSync(private val best: Int = 8, private val keep: Int = 256) {
    data class Sample(val t1: Long, val t2: Long, val t3: Long, val t4: Long) {
        val rtt: Long get() = (t4 - t1) - (t3 - t2)
        /** host - client, in ns, at local time [t4]. */
        val offset: Long get() = ((t2 - t1) + (t3 - t4)) / 2
    }

    private val samples = ArrayDeque<Sample>()
    var offsetNanos = 0L; private set
    var driftPpm = 0.0; private set
    var rttNanos = Long.MAX_VALUE; private set
    /** Half the spread of the offsets that were used – a rough error bound in ns. */
    var errorNanos = Long.MAX_VALUE; private set
    private var referenceLocal = 0L
    val sampleCount get() = samples.size
    val ready get() = samples.size >= 3

    fun add(sample: Sample) {
        if (sample.rtt < 0) return
        samples.addLast(sample)
        while (samples.size > keep) samples.removeFirst()
        recompute()
    }

    private fun recompute() {
        // Offset: median of the lowest-RTT round trips among the most recent ones (drift-safe).
        val recent = samples.takeLast(best * 4)
        val chosen = recent.sortedBy { it.rtt }.take(best)
        rttNanos = chosen.first().rtt
        val offsets = chosen.map { it.offset }.sorted()
        val median = if (offsets.size % 2 == 1) offsets[offsets.size / 2] else (offsets[offsets.size / 2 - 1] + offsets[offsets.size / 2]) / 2
        errorNanos = ((offsets.last() - offsets.first()) / 2).coerceAtLeast(rttNanos / 4)
        referenceLocal = chosen.sortedBy { it.t4 }[chosen.size / 2].t4
        offsetNanos = median
        // Drift: regression of offset over local time across the whole window (best quarter by RTT),
        // only once the samples span enough time for the slope to mean something.
        val forDrift = samples.sortedBy { it.rtt }.take(maxOf(best, samples.size / 4))
        val span = forDrift.maxOf { it.t4 } - forDrift.minOf { it.t4 }
        driftPpm = if (span > 15_000_000_000L && forDrift.size >= 8) {
            val mx = forDrift.map { it.t4.toDouble() }.average()
            val my = forDrift.map { it.offset.toDouble() }.average()
            val num = forDrift.sumOf { (it.t4 - mx) * (it.offset - my) }
            val den = forDrift.sumOf { (it.t4 - mx) * (it.t4 - mx) }
            if (den > 0) num / den * 1e6 else 0.0
        } else 0.0
    }

    fun toHost(localNanos: Long): Long =
        localNanos + offsetNanos + ((localNanos - referenceLocal) * driftPpm / 1e6).toLong()

    fun toLocal(hostNanos: Long): Long {
        // Invert toHost; drift is tiny so one fixed-point step is enough.
        var local = hostNanos - offsetNanos
        local = hostNanos - offsetNanos - ((local - referenceLocal) * driftPpm / 1e6).toLong()
        return local
    }

    companion object {
        /** Wire format of a sync packet: three big-endian longs (t1, t2, t3); the reply fills t2/t3. */
        fun encode(t1: Long, t2: Long = 0, t3: Long = 0): ByteArray {
            val out = ByteArray(24)
            for ((i, v) in listOf(t1, t2, t3).withIndex()) for (b in 0 until 8) out[i * 8 + b] = (v shr (56 - 8 * b)).toByte()
            return out
        }
        fun decode(bytes: ByteArray): Triple<Long, Long, Long>? {
            if (bytes.size < 24) return null
            fun at(i: Int): Long { var v = 0L; for (b in 0 until 8) v = (v shl 8) or (bytes[i * 8 + b].toLong() and 0xFF); return v }
            return Triple(at(0), at(1), at(2))
        }
        fun abs(v: Long) = kotlin.math.abs(v)
    }
}
