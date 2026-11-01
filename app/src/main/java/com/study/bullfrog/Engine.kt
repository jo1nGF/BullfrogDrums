```kotlin
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

    fun changeKit(k: Kit) {
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