package com.study.bullfrog

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Procedural stand-in for the 7 x 32 factory samples (A = kick, B = snare, C = toms/low perc,
 * D = clap/noisy perc, E = various, F = hi-hats, G = cymbals). Deterministic: the same
 * (folder, index) always renders the same sound.
 */
object SampleSynth {

    private fun v(i: Int, k: Int): Float {
        var h = i * 374761393 + k * 668265263 + 1013904223
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535f
    }

    private fun noise(r: Random): Float = r.nextFloat() * 2f - 1f

    private fun fade(a: FloatArray, n: Int) {
        val m = minOf(n, a.size)
        for (k in 0 until m) {
            val g = k.toFloat() / m
            a[a.size - 1 - k] *= g
        }
    }

    private fun normalize(a: FloatArray, target: Float) {
        var mx = 0f
        for (x in a) { val ax = abs(x); if (ax > mx) mx = ax }
        if (mx > 0.0001f) {
            val g = target / mx
            for (k in a.indices) a[k] *= g
        }
    }

    fun generate(folder: Int, i: Int): FloatArray {
        val r = Random(folder * 1000L + i * 7L + 3L)
        val out = when (folder) {
            0 -> kick(i, r)
            1 -> snare(i, r)
            2 -> tom(i, r)
            3 -> clap(i, r)
            4 -> various(i, r)
            5 -> hat(i, r)
            else -> cymbal(i, r)
        }
        normalize(out, 0.9f)
        return out
    }

    private fun kick(i: Int, r: Random): FloatArray {
        val dur = 0.25f + 0.55f * v(i, 1)
        val n = (dur * SR).toInt()
        val f0 = 40f + 35f * v(i, 2)
        val f1 = f0 + 90f + 260f * v(i, 3)
        val pd = 0.015f + 0.05f * v(i, 4)
        val ad = dur * (0.18f + 0.15f * v(i, 5))
        val click = 0.5f * v(i, 6)
        val drive = 1f + 3.5f * v(i, 7)
        val out = FloatArray(n)
        var ph = 0.0
        for (k in 0 until n) {
            val t = k.toFloat() / SR
            val f = f0 + (f1 - f0) * exp(-t / pd)
            ph += 2.0 * PI * f / SR
            var s = sin(ph).toFloat() * exp(-t / ad)
            s += noise(r) * click * exp(-t / 0.003f)
            out[k] = tanh(s * drive)
        }
        fade(out, 240)
        return out
    }

    private fun snare(i: Int, r: Random): FloatArray {
        val dur = 0.15f + 0.3f * v(i, 1)
        val n = (dur * SR).toInt()
        val ft = 160f + 100f * v(i, 2)
        val na = 0.5f + 0.5f * v(i, 3)
        val nd = dur * (0.25f + 0.3f * v(i, 4))
        val td = 0.05f + 0.07f * v(i, 5)
        val hp = 0.2f + 0.5f * v(i, 6)
        val out = FloatArray(n)
        var ph = 0.0
        var lp = 0f
        for (k in 0 until n) {
            val t = k.toFloat() / SR
            ph += 2.0 * PI * (ft * (1f + 0.4f * exp(-t / 0.01f))) / SR
            val tone = sin(ph).toFloat() * exp(-t / td)
            val w = noise(r)
            lp += hp * (w - lp)
            val y = w - lp
            out[k] = tanh((tone * 0.7f + y * na * exp(-t / nd)) * 1.6f)
        }
        fade(out, 200)
        return out
    }

    private fun tom(i: Int, r: Random): FloatArray {
        if (i < 16) {
            val dur = 0.3f + 0.4f * v(i, 1)
            val n = (dur * SR).toInt()
            val f0 = 70f + 14f * i
            val pd = 0.03f + 0.05f * v(i, 2)
            val ad = dur * 0.3f
            val out = FloatArray(n)
            var ph = 0.0
            for (k in 0 until n) {
                val t = k.toFloat() / SR
                val f = f0 * (1f + 0.6f * exp(-t / pd))
                ph += 2.0 * PI * f / SR
                out[k] = sin(ph).toFloat() * exp(-t / ad) + noise(r) * 0.15f * exp(-t / 0.004f)
            }
            fade(out, 200)
            return out
        }
        val dur = 0.08f + 0.2f * v(i, 2)
        val n = (dur * SR).toInt()
        val f = 400f + 800f * v(i, 3)
        val ratio = 1.5f + 1.7f * v(i, 4)
        val out = FloatArray(n)
        var p1 = 0.0
        var p2 = 0.0
        for (k in 0 until n) {
            val t = k.toFloat() / SR
            p1 += 2.0 * PI * f / SR
            p2 += 2.0 * PI * f * ratio / SR
            val e = exp(-t / (dur * 0.25f))
            out[k] = (sin(p1).toFloat() + 0.5f * sin(p2).toFloat()) * e + noise(r) * 0.1f * exp(-t / 0.003f)
        }
        fade(out, 100)
        return out
    }

    private fun clap(i: Int, r: Random): FloatArray {
        if (i < 16) {
            val bursts = 3 + (i % 3)
            val gap = 0.009f + 0.004f * v(i, 1)
            val tail = 0.08f + 0.12f * v(i, 2)
            val n = ((gap * bursts + tail * 3f) * SR).toInt()
            val out = FloatArray(n)
            var lp = 0f
            var lp2 = 0f
            val lc = 0.25f + 0.4f * v(i, 3)
            for (k in 0 until n) {
                val t = k.toFloat() / SR
                var env = 0f
                for (b in 0 until bursts - 1) {
                    val tb = t - b * gap
                    if (tb >= 0f) env += exp(-tb / 0.004f)
                }
                val tl = t - (bursts - 1) * gap
                if (tl >= 0f) env += exp(-tl / tail)
                val w = noise(r)
                lp += 0.5f * (w - lp)
                lp2 += lc * (lp - lp2)
                out[k] = (lp - lp2 * 0.4f) * env
            }
            fade(out, 200)
            return out
        }
        val dur = 0.04f + 0.25f * v(i, 1)
        val n = (dur * SR).toInt()
        val a1 = 0.05f + 0.7f * v(i, 2)
        val a2 = 0.02f + 0.3f * v(i, 3)
        val out = FloatArray(n)
        var l1 = 0f
        var l2 = 0f
        for (k in 0 until n) {
            val t = k.toFloat() / SR
            val w = noise(r)
            l1 += a1 * (w - l1)
            l2 += a2 * (l1 - l2)
            out[k] = (l1 - l2) * 2f * exp(-t / (dur * 0.3f))
        }
        fade(out, 100)
        return out
    }

    private fun various(i: Int, r: Random): FloatArray {
        when (i % 4) {
            0 -> { // FM blip
                val dur = 0.25f + 0.3f * v(i, 1)
                val n = (dur * SR).toInt()
                val fc = 200f + 800f * v(i, 2)
                val ratio = 1f + 3f * v(i, 3)
                val out = FloatArray(n)
                var pc = 0.0
                var pm = 0.0
                for (k in 0 until n) {
                    val t = k.toFloat() / SR
                    pm += 2.0 * PI * fc * ratio / SR
                    val idx = 2f + 6f * exp(-t / 0.05f)
                    pc += 2.0 * PI * fc / SR
                    out[k] = sin(pc + idx * sin(pm)).toFloat() * exp(-t / (dur * 0.3f))
                }
                fade(out, 200)
                return out
            }
            1 -> { // chord stab
                val dur = 0.5f + 0.4f * v(i, 1)
                val n = (dur * SR).toInt()
                val root = 110f * 2f.pow((i % 12) / 12f)
                val third = if (v(i, 2) > 0.5f) 3 else 4
                val ints = intArrayOf(0, third, 7)
                val ph = DoubleArray(3)
                val out = FloatArray(n)
                var lp = 0f
                for (k in 0 until n) {
                    val t = k.toFloat() / SR
                    var s = 0f
                    for (c in 0 until 3) {
                        val f = root * 2f.pow(ints[c] / 12f) * (1f + 0.004f * (c - 1))
                        ph[c] += (f / SR).toDouble()
                        if (ph[c] >= 1.0) ph[c] -= 1.0
                        s += (ph[c].toFloat() * 2f - 1f) * 0.33f
                    }
                    val a = 0.03f + 0.4f * exp(-t / 0.1f)
                    lp += a * (s - lp)
                    out[k] = lp * exp(-t / (dur * 0.35f))
                }
                fade(out, 300)
                return out
            }
            2 -> { // noise riser
                val dur = 1.0f + 0.8f * v(i, 1)
                val n = (dur * SR).toInt()
                val out = FloatArray(n)
                var lp = 0f
                for (k in 0 until n) {
                    val x = k.toFloat() / n
                    val a = 0.01f + 0.55f * x * x
                    lp += a * (noise(r) - lp)
                    out[k] = lp * x
                }
                fade(out, 120)
                return out
            }
            else -> { // drone
                val dur = 1.6f + 1.2f * v(i, 1)
                val n = (dur * SR).toInt()
                val f = 55f * (1f + v(i, 2) * 1.5f)
                val out = FloatArray(n)
                var p1 = 0.0
                var p2 = 0.0
                var p3 = 0.0
                for (k in 0 until n) {
                    val t = k.toFloat() / SR
                    p1 += 2.0 * PI * f / SR
                    p2 += 2.0 * PI * f * 1.5 / SR
                    p3 += 2.0 * PI * f * 2.01 / SR
                    val trem = 0.8f + 0.2f * sin(2.0 * PI * 3.0 * t).toFloat()
                    out[k] = (sin(p1).toFloat() + 0.6f * sin(p2).toFloat() + 0.3f * sin(p3).toFloat()) * trem
                }
                val m = minOf(2400, n / 2)
                for (k in 0 until m) { val g = k.toFloat() / m; out[k] *= g; out[n - 1 - k] *= g }
                return out
            }
        }
    }

    private val METAL = floatArrayOf(205.3f, 304.4f, 369.6f, 522.7f, 540f, 800f)

    private fun hat(i: Int, r: Random): FloatArray {
        val open = (i % 2 == 1)
        val dur = if (open) 0.28f + 0.35f * v(i, 1) else 0.04f + 0.08f * v(i, 1)
        val n = (dur * SR).toInt()
        val base = 0.85f + 0.5f * v(i, 2)
        val ph = DoubleArray(6)
        val out = FloatArray(n)
        var lp = 0f
        val nz = 0.2f + 0.5f * v(i, 3)
        for (k in 0 until n) {
            val t = k.toFloat() / SR
            var s = 0f
            for (c in 0 until 6) {
                ph[c] += (METAL[c] * base / SR).toDouble()
                if (ph[c] >= 1.0) ph[c] -= 1.0
                s += if (ph[c] < 0.5) 1f else -1f
            }
            s = s / 6f + noise(r) * nz
            lp += 0.7f * (s - lp)
            out[k] = (s - lp) * exp(-t / (dur * 0.3f))
        }
        fade(out, 100)
        return out
    }

    private fun cymbal(i: Int, r: Random): FloatArray {
        val kind = i % 3
        val dur = when (kind) {
            0 -> 1.6f + 1.2f * v(i, 1)
            1 -> 0.9f + 0.6f * v(i, 1)
            else -> 0.3f + 0.25f * v(i, 1)
        }
        val n = (dur * SR).toInt()
        val base = 0.9f + 0.6f * v(i, 2)
        val ph = DoubleArray(6)
        val out = FloatArray(n)
        var lp = 0f
        var bell = 0.0
        for (k in 0 until n) {
            val t = k.toFloat() / SR
            var s = 0f
            for (c in 0 until 6) {
                ph[c] += (METAL[c] * base * 1.3f / SR).toDouble()
                if (ph[c] >= 1.0) ph[c] -= 1.0
                s += if (ph[c] < 0.5) 1f else -1f
            }
            s = s / 6f + noise(r) * 0.5f
            lp += 0.55f * (s - lp)
            var y = (s - lp) * exp(-t / (dur * 0.28f))
            if (kind == 1) {
                bell += 2.0 * PI * 3200.0 * base / SR
                y += 0.25f * sin(bell).toFloat() * exp(-t / 0.08f)
            }
            val atk = if (t < 0.002f) t / 0.002f else 1f
            out[k] = y * atk
        }
        fade(out, 300)
        return out
    }
}
