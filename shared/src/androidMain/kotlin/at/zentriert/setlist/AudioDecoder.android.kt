package at.zentriert.setlist

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** MediaExtractor + MediaCodec: any format the device plays, down-mixed to mono and resampled linearly. */
actual fun decodeAudio(path: String, targetRate: Int): FloatArray? {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(path)
        val trackIndex = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()
        val mono = ArrayList<FloatArray>()
        var total = 0
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buf = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) { codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0); extractor.advance() }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIndex >= 0 -> {
                    val out = codec.getOutputBuffer(outIndex)!!
                    if (info.size > 0) {
                        val pcm = ByteBuffer.allocate(info.size).order(ByteOrder.LITTLE_ENDIAN)
                        out.position(info.offset); out.limit(info.offset + info.size); pcm.put(out); pcm.flip()
                        val frames = info.size / 2 / channels
                        val block = FloatArray(frames)
                        for (f in 0 until frames) {
                            var sum = 0f
                            for (c in 0 until channels) sum += pcm.getShort((f * channels + c) * 2) / 32768f
                            block[f] = sum / channels
                        }
                        mono += block; total += frames
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }
            }
        }
        codec.stop(); codec.release()
        if (total == 0) return null
        val all = FloatArray(total)
        var pos = 0
        for (b in mono) { b.copyInto(all, pos); pos += b.size }
        return resample(all, rate, targetRate)
    } catch (e: Exception) {
        return null
    } finally {
        extractor.release()
    }
}

/** Linear interpolation is plenty for backing tracks (the stretcher smooths the rest). */
internal fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
    if (from == to || from <= 0) return input
    val n = (input.size.toLong() * to / from).toInt()
    val out = FloatArray(n)
    val step = from.toDouble() / to
    for (i in 0 until n) {
        val x = i * step
        val k = x.toInt()
        val frac = (x - k).toFloat()
        val a = input.getOrElse(k) { 0f }; val b = input.getOrElse(k + 1) { a }
        out[i] = a + (b - a) * frac
    }
    return out
}
