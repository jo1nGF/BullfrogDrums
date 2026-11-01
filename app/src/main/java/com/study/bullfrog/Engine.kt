package com.study.bullfrog

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.tanh

/** One sample-playback voice: sample player + decay envelope + DJ filter + drive + pan. */
class Voice {
    var data: FloatArray? = null
    var pos = 0.0
    var playing = false
    var rev = false
    var lo = 0.0
    var hi = 0.0
    var age = 0
    var accent = 1f
    var ic1 = 0f
    var ic2 = 0f
    var ft = 0                 // 0 bypass, 1 lowpass, 2 highpass
    var a1 = 0f
    var a2 = 0f
    var a3 = 0f
    var kk = 1f
    var speed = 1.0
    var decayS = -1
    var drive = 0f
    var loopOn = false
    var loopP = 0f
    var gL = 0f
    var gR = 0f
    var peak = 0f
    /** Parameter overrides (locks / automation) – NaN = follow the knob. */
    val ov = FloatArray(NLOCK) { Float.NaN }
}

/** Small mono synth that stands in for the instrument driven by the CV/Gate output (track 8). */
class Bass {
    var gate = false
    private var freq = 65.4
    private var target = 65.4
    private var glide = false
    private var env = 0f
    private var fenv = 0f
    private var p1 = 0.0
    private var p2 = 0.0
    private var l1 = 0f
    private var l2 = 0f

    fun noteOn(note: Int, slide: Boolean) {
        // 0 V = C0 on the CV output. The internal voice is shifted +2 octaves so it stays audible.
        target = 16.3516 * 2.0.pow(note / 12.0) * 4.0
        if (slide && gate) {
            glide = true
        } else {
            glide = false
            freq = target
            fenv = 1f
        }
        gate = true
    }

    fun noteOff() { gate = false }

    fun next(): Float {
        if (glide) freq += (target - freq) * 0.002 else freq = target
        if (gate) env += (1f - env) * 0.02f else env *= 0.9992f
        fenv *= 0.9997f
        p1 += freq / SR
        if (p1 >= 1.0) p1 -= 1.0
        p2 += freq * 0.5 / SR
        if (p2 >= 1.0) p2 -= 1.0
        val saw = (p1 * 2.0 - 1.0).toFloat()
        val sq = if (p2 < 0.5) 1f else -1f
        val x = saw * 0.6f + sq * 0.4f
        val fc = 180f + fenv * 2600f
        val a = min(0.6f, fc * 6.2832f / SR)
        l1 += a * (x - l1)
        l2 += a * (l1 - l2)
        return l2 * env * 0.45f
    }
}

/**
 * Audio engine + sequencer. All public mutators must be called while holding [lock]
 * (the Controller does this); the audio thread renders blocks while holding the same lock.
 */
class Engine(private val store: SampleStore, private val st: Settings) {
    val lock = Any()

    @Volatile var playing = false
    var paused = false
    var bpm = 120
    var pattern = Pattern()
    var kit = Kit()
    val muted = BooleanArray(NT)
    val solo = BooleanArray(NT)
    var volume = 0.7f
    val playhead = IntArray(NT) { -1 }
    val headClock = LongArray(NT)
    val peakLed = FloatArray(NV)
    val flash = FloatArray(NT)
    var autoTrack = -1
    val autoOn = BooleanArray(NLOCK)
    val autoVal = FloatArray(NLOCK)
    val scan = Array(3) { BooleanArray(NV) }   // START / END / LOOP linear step mapping per track
    var rollDiv = 1
    @Volatile var clock = 0L

    private class Ev(val time: Double, val type: Int, val track: Int, val step: Int, val acc: Boolean)

    private companion object {
        const val T_HEAD = 0
        const val T_TRIG = 1
        const val T_LOCKS = 2
        const val T_NOTE = 3
        const val T_GATEOFF = 4
    }

    private val evs = ArrayList<Ev>()
    private val voices = Array(NV + 1) { Voice() }     // [7] = preview voice
    private val bass = Bass()
    private val rnd = Random(42)
    private val nextStep = DoubleArray(NT)
    private val counter = IntArray(NT)
    private var masterNext = 0.0
    private var masterStep = 0
    private var pendingPat: Pattern? = null
    private var pendingKit: Kit? = null
    private val rollOn = BooleanArray(NV)
    private val rollNext = DoubleArray(NV)
    private var bassOffAt = -1L
    private var accL = 0f
    private var accR = 0f
    private var thread: Thread? = null
    @Volatile private var running = false

    // ------------------------------------------------------------------ transport

    fun togglePlay() { if (playing) pause() else start() }

    private fun start() {
        if (!paused) {
            for (t in 0 until NT) { counter[t] = 0; playhead[t] = -1 }
            masterStep = 0
        }
        paused = false
        val c = clock.toDouble()
        for (t in 0 until NT) nextStep[t] = c
        masterNext = c + baseStep()
        evs.clear()
        playing = true
    }

    private fun pause() {
        playing = false
        paused = true
        evs.clear()
        bass.noteOff()
    }

    fun stop() {
        playing = false
        paused = false
        evs.clear()
        bass.noteOff()
        for (t in 0 until NT) { counter[t] = 0; playhead[t] = -1 }
        masterStep = 0
        for (v in 0 until NV) rollOn[v] = false
        if (pendingPat != null) applyPending(clock.toDouble())
        for (vo in voices) java.util.Arrays.fill(vo.ov, Float.NaN)
    }

    /** Pattern recall: immediate when stopped, otherwise at the end of the master cycle. */
    fun queuePattern(p: Pattern, k: Kit?) {
        if (!playing) {
            pattern = p
            if (k != null) kit = k
            if (st.v[S.BPM_LOAD] == 1) bpm = p.bpm
            pendingPat = null
            pendingKit = null
            paused = false
            for (t in 0 until NT) { counter[t] = 0; playhead[t] = -1 }
            for (vo in voices) java.util.Arrays.fill(vo.ov, Float.NaN)
        } else {
            pendingPat = p
            pendingKit = k
        }
    }

    fun setKit(k: Kit) {
        kit = k
        for (vo in voices) java.util.Arrays.fill(vo.ov, Float.NaN)
    }

    private fun applyPending(time: Double) {
        val p = pendingPat ?: return
        pattern = p
        val k = pendingKit
        if (k != null) kit = k
        pendingPat = null
        pendingKit = null
        if (st.v[S.BPM_LOAD] == 1) bpm = p.bpm
        for (t in 0 until NT) { counter[t] = 0; nextStep[t] = time }
        val iter = evs.iterator()
        while (iter.hasNext()) { if (iter.next().time >= time) iter.remove() }
        for (vo in voices) java.util.Arrays.fill(vo.ov, Float.NaN)
    }

    fun stepSamples(t: Int): Double = (60.0 / bpm) * 0.25 / SCALES[pattern.scale[t]] * SR
    private fun baseStep(): Double = (60.0 / bpm) * 0.25 * SR

    // ------------------------------------------------------------------ manual playing

    fun triggerManual(t: Int) { if (t in 0 until NV) fireVoice(t, -1, true) }

    fun audition(data: FloatArray, s: Float, e: Float) {
        val vo = voices[NV]
        vo.speed = 1.0; vo.ft = 0; vo.drive = 0f; vo.decayS = -1; vo.loopOn = false
        vo.gL = 0.8f; vo.gR = 0.8f; vo.accent = 1f
        startVoice(vo, data, s, e, false)
    }

    fun previewNote(note: Int) {
        bass.noteOn(note, false)
        bassOffAt = clock + SR / 4
    }

    fun setRoll(v: Int, on: Boolean) {
        if (v !in 0 until NV) return
        rollOn[v] = on
        if (on) rollNext[v] = clock.toDouble()
    }

    /** TAP programming: quantise a live hit to the nearest step of the track. */
    fun recordTap(t: Int) {
        if (!playing || t !in 0 until NV) return
        var idx = playhead[t]
        if (idx < 0) return
        val ss = stepSamples(t)
        val frac = (clock - headClock[t]) / ss
        val len = max(1, pattern.length[t])
        if (frac > 0.5) idx = (idx + 1) % len
        if (pattern.trig[t][idx] == 0) pattern.trig[t][idx] = 2
    }

    // ------------------------------------------------------------------ voices

    private fun audible(t: Int): Boolean {
        var any = false
        for (i in 0 until NT) if (solo[i]) any = true
        return if (any) solo[t] else !muted[t]
    }

    private fun base(t: Int, p: Int): Float = when (p) {
        Prm.START -> kit.start[t]
        Prm.END -> kit.end[t]
        else -> kit.knob[t][p]
    }

    private fun pv(t: Int, p: Int): Float {
        val o = voices[t].ov[p]
        return if (o.isNaN()) base(t, p) else o
    }

    private fun applyLocks(t: Int, step: Int) {
        val vo = voices[t]
        for (p in 0 until NLOCK) vo.ov[p] = pattern.locks[t][p][step]
        val len = max(1, pattern.length[t]).toFloat()
        if (scan[0][t]) vo.ov[Prm.START] = step / len
        if (scan[1][t]) vo.ov[Prm.END] = (step + 1) / len
        if (scan[2][t]) vo.ov[Prm.LOOP] = step / len
    }

    private fun updateVoice(t: Int) {
        val vo = voices[t]
        var semis = (pv(t, Prm.PITCH) - 0.5f) * 48f
        if (st.v[S.PITCH_Q] == 1) semis = Math.round(semis).toFloat()
        vo.speed = 2.0.pow(semis / 12.0)
        val d = pv(t, Prm.DECAY)
        vo.decayS = if (d >= 0.995f) -1 else (0.01 * 600.0.pow(d.toDouble()) * SR).toInt()
        val c = pv(t, Prm.CUTOFF)
        val res = pv(t, Prm.RES)
        val q = 0.707 + res * res * 12.0
        val k = (1.0 / q).toFloat()
        if (c > 0.47f && c < 0.53f) {
            vo.ft = 0
        } else {
            var fc = 20000.0
            if (c <= 0.47f) {
                vo.ft = 1
                fc = 20000.0 * 0.002.pow(((0.47f - c) / 0.47f).toDouble())
            } else {
                vo.ft = 2
                fc = 20.0 * 400.0.pow(((c - 0.53f) / 0.47f).toDouble())
            }
            val f2 = min(fc, 0.45 * SR)
            val g = tan(PI * f2 / SR)
            val a1 = 1.0 / (1.0 + g * (g + k))
            vo.a1 = a1.toFloat()
            vo.a2 = (g * a1).toFloat()
            vo.a3 = (g * g * a1).toFloat()
            vo.kk = k
        }
        vo.drive = pv(t, Prm.DRIVE)
        val a = pv(t, Prm.PAN) * (PI / 2.0).toFloat()
        val lv = kit.level[t]
        val g = lv * lv * 2f
        vo.gL = cos(a) * 1.4142f * g
        vo.gR = sin(a) * 1.4142f * g
        vo.loopOn = kit.loop[t]
        vo.loopP = pv(t, Prm.LOOP)
    }

    private fun fireVoice(t: Int, step: Int, accent: Boolean) {
        flash[t] = 1f
        if (!audible(t)) return
        val data = store.peek(kit.sample[t]) ?: return
        val vo = voices[t]
        if (step >= 0) applyLocks(t, step)
        updateVoice(t)
        vo.accent = if (accent) 1f else 0.55f
        startVoice(vo, data, pv(t, Prm.START), pv(t, Prm.END), kit.reverse[t])
    }

    private fun startVoice(vo: Voice, data: FloatArray, s0: Float, e0: Float, reverse: Boolean) {
        val n = data.size
        if (n < 16) return
        val s = s0.coerceIn(0f, 1f) * (n - 1)
        val e = e0.coerceIn(0f, 1f) * (n - 1)
        vo.rev = (s > e) != reverse
        val lo = min(s, e).toDouble()
        var hi = max(s, e).toDouble()
        if (hi - lo < 8.0) hi = min((n - 1).toDouble(), lo + 8.0)
        vo.lo = lo
        vo.hi = hi
        vo.pos = if (vo.rev) hi else lo
        vo.age = 0
        vo.ic1 = 0f
        vo.ic2 = 0f
        vo.data = data
        vo.playing = true
    }

    private fun tickVoice(vo: Voice) {
        val d = vo.data
        if (d == null) { vo.playing = false; return }
        val n = d.size
        var i = vo.pos.toInt()
        if (i < 0) i = 0
        if (i > n - 2) i = n - 2
        val fr = (vo.pos - i).toFloat()
        var x = d[i] + (d[i + 1] - d[i]) * fr
        var ended = false
        if (!vo.rev) {
            vo.pos += vo.speed
            if (vo.pos >= vo.hi) {
                if (vo.loopOn) {
                    val ls = max(vo.lo, min(vo.lo + vo.loopP * (vo.hi - vo.lo), vo.hi - 32.0))
                    var np = ls + (vo.pos - vo.hi)
                    if (np >= vo.hi) np = ls
                    vo.pos = np
                } else ended = true
            }
        } else {
            vo.pos -= vo.speed
            if (vo.pos <= vo.lo) {
                if (vo.loopOn) {
                    val ls = min(vo.hi, max(vo.hi - vo.loopP * (vo.hi - vo.lo), vo.lo + 32.0))
                    var np = ls - (vo.lo - vo.pos)
                    if (np <= vo.lo) np = ls
                    vo.pos = np
                } else ended = true
            }
        }
        if (ended) vo.playing = false
        var amp = 1f
        if (vo.decayS > 0) {
            val r = 1f - vo.age.toFloat() / vo.decayS
            if (r <= 0f) { vo.playing = false; return }
            amp = r * r
        }
        if (vo.age < 48) amp *= vo.age / 48f
        vo.age++
        x *= amp
        if (vo.ft != 0) {
            val v3 = x - vo.ic2
            val v1 = vo.a1 * vo.ic1 + vo.a2 * v3
            val v2 = vo.ic2 + vo.a2 * vo.ic1 + vo.a3 * v3
            vo.ic1 = 2f * v1 - vo.ic1
            vo.ic2 = 2f * v2 - vo.ic2
            x = if (vo.ft == 1) v2 else x - vo.kk * v1 - v2
        }
        if (vo.drive > 0.001f) {
            val g = 1f + vo.drive * 20f
            x = tanh(x * g) / (1f + 0.05f * (g - 1f))
        }
        val ax = abs(x)
        if (ax > vo.peak) vo.peak = ax
        x *= vo.accent
        accL += x * vo.gL
        accR += x * vo.gR
    }

    // ------------------------------------------------------------------ sequencer

    private fun period(dir: Int, len: Int): Int = when (dir) {
        3 -> 2 * len
        2 -> if (len > 1) 2 * len - 2 else 1
        else -> len
    }

    private fun orderIdx(dir: Int, c: Int, len: Int): Int {
        val m = c % period(dir, len)
        return when (dir) {
            1 -> len - 1 - m
            2 -> if (m < len) m else 2 * len - 2 - m
            3 -> if (m < len) m else 2 * len - 1 - m
            4 -> rnd.nextInt(len)
            else -> m
        }
    }

    private fun oddsOk(code: Int, pass: Int): Boolean {
        if (code == 0) return true
        if (code >= 11) return rnd.nextFloat() < ODDS_PCT[code - 11]
        val n = ODDS_N[code]
        val d = ODDS_D[code]
        val p = pass % d
        return if (st.v[S.INV_ODDS] == 0) p < n else p >= d - n
    }

    private fun stepTrack(t: Int, g: Double) {
        val pat = pattern
        val len = max(1, pat.length[t])
        val c = counter[t]
        val dir = pat.dir[t]
        val idx = orderIdx(dir, c, len)
        val pass = c / period(dir, len)
        val ss = stepSamples(t)
        evs.add(Ev(g, T_HEAD, t, idx, false))
        if (autoTrack == t) {
            for (p in 0 until NLOCK) if (autoOn[p]) pat.locks[t][p][idx] = autoVal[p]
        }
        val tv = pat.trig[t][idx]
        if (tv != 0 && oddsOk(pat.odds[t][idx], pass)) {
            var tt = g
            if (c % 2 == 1) tt += pat.shuffle[t] * 0.5 * ss
            tt += pat.utime[t][idx] * ss
            if (tt < clock) tt = clock.toDouble()
            if (t == CVT) {
                evs.add(Ev(tt, T_NOTE, t, idx, false))
                val gl = pat.gate[idx] / 10.0
                if (gl < 1.0) evs.add(Ev(tt + gl * ss, T_GATEOFF, t, idx, false))
            } else {
                val r = max(1, pat.ratchet[t][idx])
                for (k in 0 until r) evs.add(Ev(tt + k * ss / r, T_TRIG, t, idx, tv == 2))
            }
        } else if (t != CVT && st.v[S.PLK] == 1) {
            evs.add(Ev(g, T_LOCKS, t, idx, false))
        }
        counter[t] = c + 1
        nextStep[t] = g + ss
    }

    private fun sequencerTick() {
        if (!playing) return
        val c = clock.toDouble()
        if (c + 0.5 * baseStep() >= masterNext) {
            masterStep++
            val cyc = if (pattern.master > 0) pattern.master else 16
            if (masterStep % cyc == 0 && pendingPat != null) applyPending(masterNext)
            masterNext += baseStep()
        }
        for (t in 0 until NT) {
            val look = 0.5 * stepSamples(t)
            var guard = 0
            while (c + look >= nextStep[t] && guard < 8) {
                stepTrack(t, nextStep[t])
                guard++
            }
        }
    }

    private fun fireEvents() {
        if (evs.isEmpty()) return
        val c = clock.toDouble()
        var i = 0
        while (i < evs.size) {
            val e = evs[i]
            if (e.time <= c) {
                evs.removeAt(i)
                when (e.type) {
                    T_HEAD -> { playhead[e.track] = e.step; headClock[e.track] = clock }
                    T_TRIG -> fireVoice(e.track, e.step, e.acc)
                    T_LOCKS -> { applyLocks(e.track, e.step); updateVoice(e.track) }
                    T_NOTE -> {
                        flash[CVT] = 1f
                        if (audible(CVT)) bass.noteOn(pattern.note[e.step], pattern.slide[e.step])
                    }
                    T_GATEOFF -> bass.noteOff()
                }
            } else i++
        }
    }

    // ------------------------------------------------------------------ rendering

    fun render(out: FloatArray, frames: Int) {
        val mv = volume * volume * 2f
        for (f in 0 until frames) {
            if ((f and 63) == 0) for (t in 0 until NV) if (voices[t].playing) updateVoice(t)
            sequencerTick()
            fireEvents()
            val c = clock.toDouble()
            for (v in 0 until NV) {
                if (rollOn[v] && c >= rollNext[v]) {
                    fireVoice(v, -1, true)
                    rollNext[v] += stepSamples(v) / ROLL_DIVS[rollDiv]
                }
            }
            if (bassOffAt >= 0 && clock >= bassOffAt) { bass.noteOff(); bassOffAt = -1L }
            accL = 0f
            accR = 0f
            for (t in 0..NV) {
                val vo = voices[t]
                if (vo.playing) tickVoice(vo)
            }
            val b = bass.next() * 0.5f
            accL += b
            accR += b
            out[2 * f] = tanh(accL * mv)
            out[2 * f + 1] = tanh(accR * mv)
            clock++
        }
        for (t in 0 until NV) {
            val p = voices[t].peak
            if (p > peakLed[t]) peakLed[t] = p
            peakLed[t] *= 0.96f
            voices[t].peak = 0f
        }
        for (t in 0 until NT) flash[t] *= 0.95f
    }

    fun startAudio() {
        if (running) return
        val frames = 256
        val minBuf = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val bufBytes = max(minBuf, frames * 2 * 4 * 4)
        val at = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(SR)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        running = true
        val th = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = FloatArray(frames * 2)
            at.play()
            while (running) {
                synchronized(lock) { render(buf, frames) }
                at.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
            }
            try { at.stop() } catch (ex: Exception) { }
            at.release()
        }
        thread = th
        th.start()
    }

    fun stopAudio() {
        running = false
        try { thread?.join(800) } catch (ex: Exception) { }
        thread = null
    }
}
