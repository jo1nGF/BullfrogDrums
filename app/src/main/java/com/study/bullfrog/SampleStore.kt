package com.study.bullfrog

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** WAV reading/writing. Everything is converted to mono 48 kHz float. */
object WavIO {
    fun read(bytes: ByteArray): FloatArray? {
        try {
            if (bytes.size < 44) return null
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
            var pos = 12
            var fmt = 1
            var ch = 1
            var rate = SR
            var bits = 16
            var dataOff = -1
            var dataLen = 0
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4)
                var len = bb.getInt(pos + 4)
                if (len < 0 || pos + 8 + len > bytes.size) len = bytes.size - pos - 8
                if (id == "fmt ") {
                    fmt = bb.getShort(pos + 8).toInt() and 0xFFFF
                    ch = bb.getShort(pos + 10).toInt()
                    rate = bb.getInt(pos + 12)
                    bits = bb.getShort(pos + 22).toInt()
                    if (fmt == 0xFFFE && len >= 26) fmt = bb.getShort(pos + 32).toInt() and 0xFFFF
                } else if (id == "data") {
                    dataOff = pos + 8
                    dataLen = len
                    break
                }
                pos += 8 + len + (len and 1)
            }
            if (dataOff < 0 || ch < 1 || bits < 8) return null
            val bytesPer = bits / 8
            val frames = dataLen / (bytesPer * ch)
            val mono = FloatArray(frames)
            for (f in 0 until frames) {
                var acc = 0f
                for (c in 0 until ch) {
                    val o = dataOff + (f * ch + c) * bytesPer
                    val x: Float = when {
                        fmt == 3 && bits == 32 -> bb.getFloat(o)
                        bits == 8 -> ((bytes[o].toInt() and 0xFF) - 128) / 128f
                        bits == 16 -> bb.getShort(o) / 32768f
                        bits == 24 -> {
                            var iv = (bytes[o].toInt() and 0xFF) or
                                ((bytes[o + 1].toInt() and 0xFF) shl 8) or
                                (bytes[o + 2].toInt() shl 16)
                            iv / 8388608f
                        }
                        bits == 32 -> bb.getInt(o) / 2147483648f
                        else -> 0f
                    }
                    acc += x
                }
                mono[f] = acc / ch
            }
            var res = mono
            if (rate != SR && rate > 0) {
                val n = (frames.toLong() * SR / rate).toInt()
                val rs = FloatArray(n)
                val ratio = rate.toDouble() / SR
                for (k in 0 until n) {
                    val p = k * ratio
                    val i0 = p.toInt()
                    val fr = (p - i0).toFloat()
                    val a = mono[minOf(i0, frames - 1)]
                    val b = mono[minOf(i0 + 1, frames - 1)]
                    rs[k] = a + (b - a) * fr
                }
                res = rs
            }
            if (res.size > MAX_SAMPLE_LEN) res = res.copyOf(MAX_SAMPLE_LEN)
            return res
        } catch (e: Exception) {
            return null
        }
    }

    fun write16(data: FloatArray): ByteArray {
        val n = data.size
        val bb = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + n * 2); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1)
        bb.putInt(SR); bb.putInt(SR * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(n * 2)
        for (x in data) {
            val c = if (x > 1f) 1f else if (x < -1f) -1f else x
            bb.putShort((c * 32767f).toInt().toShort())
        }
        return bb.array()
    }
}

/**
 * Sample memory: 16 folders (A..O + R) x 64 samples. Folders A-G start with 32 procedural
 * "factory" samples. User samples are saved as SAMPLES/<letter>/<n>.wav in the app's private dir,
 * mirroring the hardware's USB drive structure.
 */
class SampleStore(private val root: File) {
    private val cache = HashMap<Int, FloatArray>()
    private val user = HashMap<Int, FloatArray>()
    private val removed = HashSet<Int>()

    private fun key(f: Int, i: Int) = f * MAXSAMP + i
    private fun fileFor(f: Int, i: Int) = File(File(root, "SAMPLES/" + folderLetter(f)), "" + (i + 1) + ".wav")

    fun loadUser() {
        user.clear()
        for (f in 0 until NFOLDERS) {
            val dir = File(root, "SAMPLES/" + folderLetter(f))
            val files = dir.listFiles() ?: continue
            for (fl in files) {
                val n = fl.nameWithoutExtension.toIntOrNull() ?: continue
                if (n < 1 || n > MAXSAMP) continue
                val d = WavIO.read(fl.readBytes()) ?: continue
                user[key(f, n - 1)] = d
            }
        }
    }

    @Synchronized
    fun exists(f: Int, i: Int): Boolean {
        val k = key(f, i)
        if (user.containsKey(k)) return true
        return f < 7 && i < 32 && !removed.contains(k)
    }

    /** Returns the sample, generating factory content on demand. Call from the UI thread only. */
    @Synchronized
    fun get(f: Int, i: Int): FloatArray? {
        if (f < 0 || i < 0 || i >= MAXSAMP) return null
        val k = key(f, i)
        user[k]?.let { return it }
        if (f < 7 && i < 32 && !removed.contains(k)) {
            var c = cache[k]
            if (c == null) { c = SampleSynth.generate(f, i); cache[k] = c }
            return c
        }
        return null
    }

    fun getCode(code: Int): FloatArray? = if (code < 0) null else get(code / MAXSAMP, code % MAXSAMP)

    /** Audio-thread safe: never generates. */
    @Synchronized
    fun peek(code: Int): FloatArray? {
        if (code < 0) return null
        val k = code
        return user[k] ?: cache[k]
    }

    @Synchronized
    fun firstFree(f: Int): Int {
        for (i in 0 until MAXSAMP) if (!exists(f, i)) return i
        return -1
    }

    @Synchronized
    fun putUser(f: Int, i: Int, data: FloatArray): Boolean {
        val d = if (data.size > MAX_SAMPLE_LEN) data.copyOf(MAX_SAMPLE_LEN) else data
        user[key(f, i)] = d
        removed.remove(key(f, i))
        return try {
            val fl = fileFor(f, i)
            fl.parentFile?.mkdirs()
            fl.writeBytes(WavIO.write16(d))
            true
        } catch (e: Exception) { false }
    }

    @Synchronized
    fun delete(f: Int, i: Int) {
        val k = key(f, i)
        user.remove(k)
        cache.remove(k)
        if (f < 7 && i < 32) removed.add(k)
        try { fileFor(f, i).delete() } catch (e: Exception) { }
    }

    fun ensureKit(kit: Kit) {
        for (v in 0 until NV) getCode(kit.sample[v])
    }
}
