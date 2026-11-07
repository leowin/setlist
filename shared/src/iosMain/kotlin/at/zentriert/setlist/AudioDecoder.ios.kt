package at.zentriert.setlist

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import platform.AVFAudio.AVAudioFile
import platform.AVFAudio.AVAudioPCMBuffer
import platform.Foundation.NSURL

/** AVAudioFile reads MP3/AAC/WAV and converts to float; down-mix to mono, then linear resampling. */
@OptIn(ExperimentalForeignApi::class)
actual fun decodeAudio(path: String, targetRate: Int): FloatArray? {
    val url = NSURL.fileURLWithPath(path)
    val file = runCatching { AVAudioFile(forReading = url, error = null) }.getOrNull() ?: return null
    val format = file.processingFormat
    val frames = file.length.toInt()
    if (frames <= 0) return null
    val buffer = AVAudioPCMBuffer(pCMFormat = format, frameCapacity = frames.toUInt()) ?: return null
    if (!file.readIntoBuffer(buffer, error = null)) return null
    val channels = format.channelCount.toInt()
    val data = buffer.floatChannelData ?: return null
    val n = buffer.frameLength.toInt()
    val mono = FloatArray(n)
    for (c in 0 until channels) {
        val ch = data[c] ?: continue
        for (i in 0 until n) mono[i] += ch[i] / channels
    }
    val from = format.sampleRate.toInt()
    if (from == targetRate) return mono
    val out = FloatArray((n.toLong() * targetRate / from).toInt())
    val step = from.toDouble() / targetRate
    for (i in out.indices) {
        val x = i * step; val k = x.toInt(); val frac = (x - k).toFloat()
        val a = mono.getOrElse(k) { 0f }; val b = mono.getOrElse(k + 1) { a }
        out[i] = a + (b - a) * frac
    }
    return out
}
