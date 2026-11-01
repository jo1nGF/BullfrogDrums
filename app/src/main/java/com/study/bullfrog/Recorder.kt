package com.study.bullfrog

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.abs
import kotlin.math.min

/**
 * Live sampling (SRC MIC/LIN). On a phone both sources go through the active Android input
 * (built-in mic, or a USB/headset input when connected).
 * States: 0 closed, 1 metering, 2 armed (waiting for threshold), 3 capturing, 4 done.
 */
class Recorder {
    @Volatile var level = 0f
    @Volatile var state = 0
    @Volatile var progress = 0f
    @Volatile var gain = 1f
    @Volatile var thr = 0f
    @Volatile var maxSamples = SR
    @Volatile var result: FloatArray? = null
    @Volatile private var stopReq = false
    @Volatile private var run = false
    private var th: Thread? = null

    fun open(): Boolean {
        if (run) return true
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val rec: AudioRecord? = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SR, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 8192)
            )
        } catch (e: Exception) {
            null
        }
        if (rec == null) return false
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        val ar: AudioRecord = rec
        run = true
        state = 1
        result = null
        progress = 0f
        val t = Thread {
            val buf = ShortArray(1024)
            val cap = FloatArray(MAX_SAMPLE_LEN)
            var n = 0
            ar.startRecording()
            while (run) {
                val r = ar.read(buf, 0, buf.size)
                if (r <= 0) continue
                val g = gain
                var pk = 0f
                for (k in 0 until r) {
                    val a = abs(buf[k] / 32768f) * g
                    if (a > pk) pk = a
                }
                level = pk
                if (state == 2 && pk >= thr) {
                    n = 0
                    state = 3
                }
                if (state == 3) {
                    val lim = min(maxSamples, MAX_SAMPLE_LEN)
                    var k = 0
                    while (k < r && n < lim) {
                        cap[n] = (buf[k] / 32768f * g).coerceIn(-1f, 1f)
                        n++
                        k++
                    }
                    progress = n.toFloat() / lim
                    if (n >= lim || stopReq) {
                        result = cap.copyOf(n)
                        state = 4
                        stopReq = false
                    }
                } else if (stopReq) {
                    stopReq = false
                    if (state == 2) state = 1
                }
            }
            try { ar.stop() } catch (e: Exception) { }
            ar.release()
        }
        th = t
        t.start()
        return true
    }

    fun arm(thr: Float, maxSec: Float, gain: Float) {
        this.thr = thr
        this.gain = gain
        maxSamples = (maxSec * SR).toInt()
        progress = 0f
        result = null
        state = 2
    }

    fun finishEarly() { stopReq = true }

    fun close() {
        run = false
        try { th?.join(500) } catch (e: Exception) { }
        th = null
        state = 0
        level = 0f
    }
}
