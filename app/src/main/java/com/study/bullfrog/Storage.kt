package com.study.bullfrog

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persists patterns, kits, working state and settings as JSON (also used for backup/restore). */
class Storage(dir: File) {
    private val file = File(dir, "state.json")

    class Snapshot(
        val mem: Memory, val pattern: Pattern, val kit: Kit,
        val curPat: Int, val curKit: Int, val bpm: Int, val volume: Float
    )

    fun readRaw(): ByteArray? = try { if (file.exists()) file.readBytes() else null } catch (e: Exception) { null }
    fun writeRaw(b: ByteArray) { try { file.writeBytes(b) } catch (e: Exception) { } }
    fun delete() { try { file.delete() } catch (e: Exception) { } }

    private fun ia(a: IntArray): JSONArray { val j = JSONArray(); for (x in a) j.put(x); return j }
    private fun fa(a: FloatArray): JSONArray { val j = JSONArray(); for (x in a) j.put(x.toDouble()); return j }
    private fun ia2(a: Array<IntArray>): JSONArray { val j = JSONArray(); for (r in a) for (x in r) j.put(x); return j }
    private fun fa2(a: Array<FloatArray>): JSONArray { val j = JSONArray(); for (r in a) for (x in r) j.put(x.toDouble()); return j }

    private fun readI(j: JSONArray?, dst: IntArray) {
        if (j == null) return
        for (k in dst.indices) if (k < j.length()) dst[k] = j.getInt(k)
    }
    private fun readF(j: JSONArray?, dst: FloatArray) {
        if (j == null) return
        for (k in dst.indices) if (k < j.length()) dst[k] = j.getDouble(k).toFloat()
    }
    private fun readI2(j: JSONArray?, dst: Array<IntArray>) {
        if (j == null) return
        for (r in dst.indices) for (c in dst[r].indices) {
            val k = r * dst[r].size + c
            if (k < j.length()) dst[r][c] = j.getInt(k)
        }
    }
    private fun readF2(j: JSONArray?, dst: Array<FloatArray>) {
        if (j == null) return
        for (r in dst.indices) for (c in dst[r].indices) {
            val k = r * dst[r].size + c
            if (k < j.length()) dst[r][c] = j.getDouble(k).toFloat()
        }
    }

    fun patToJson(p: Pattern): JSONObject {
        val o = JSONObject()
        o.put("trig", ia2(p.trig))
        o.put("ratchet", ia2(p.ratchet))
        o.put("odds", ia2(p.odds))
        o.put("utime", fa2(p.utime))
        val lk = JSONArray()
        for (t in 0 until NT) for (q in 0 until NLOCK) for (s in 0 until MAXS) {
            val v = p.locks[t][q][s]
            if (!v.isNaN()) { lk.put(t * 10000 + q * 100 + s); lk.put(v.toDouble()) }
        }
        o.put("locks", lk)
        o.put("note", ia(p.note))
        o.put("gate", ia(p.gate))
        val sl = IntArray(MAXS) { if (p.slide[it]) 1 else 0 }
        o.put("slide", ia(sl))
        o.put("length", ia(p.length))
        o.put("scale", ia(p.scale))
        o.put("dir", ia(p.dir))
        o.put("shuffle", fa(p.shuffle))
        o.put("master", p.master)
        o.put("bpm", p.bpm)
        o.put("linkedKit", p.linkedKit)
        return o
    }

    fun patFromJson(o: JSONObject): Pattern {
        val p = Pattern()
        readI2(o.optJSONArray("trig"), p.trig)
        readI2(o.optJSONArray("ratchet"), p.ratchet)
        readI2(o.optJSONArray("odds"), p.odds)
        readF2(o.optJSONArray("utime"), p.utime)
        val lk = o.optJSONArray("locks")
        if (lk != null) {
            var k = 0
            while (k + 1 < lk.length()) {
                val key = lk.getInt(k)
                val t = key / 10000
                val q = (key / 100) % 100
                val s = key % 100
                if (t < NT && q < NLOCK && s < MAXS) p.locks[t][q][s] = lk.getDouble(k + 1).toFloat()
                k += 2
            }
        }
        readI(o.optJSONArray("note"), p.note)
        readI(o.optJSONArray("gate"), p.gate)
        val sl = IntArray(MAXS)
        readI(o.optJSONArray("slide"), sl)
        for (s in 0 until MAXS) p.slide[s] = sl[s] != 0
        readI(o.optJSONArray("length"), p.length)
        readI(o.optJSONArray("scale"), p.scale)
        readI(o.optJSONArray("dir"), p.dir)
        readF(o.optJSONArray("shuffle"), p.shuffle)
        p.master = o.optInt("master", 0)
        p.bpm = o.optInt("bpm", 120)
        p.linkedKit = o.optInt("linkedKit", -1)
        return p
    }

    fun kitToJson(k: Kit): JSONObject {
        val o = JSONObject()
        o.put("sample", ia(k.sample))
        o.put("level", fa(k.level))
        o.put("knob", fa2(k.knob))
        o.put("start", fa(k.start))
        o.put("end", fa(k.end))
        o.put("reverse", ia(IntArray(NV) { if (k.reverse[it]) 1 else 0 }))
        o.put("loop", ia(IntArray(NV) { if (k.loop[it]) 1 else 0 }))
        return o
    }

    fun kitFromJson(o: JSONObject): Kit {
        val k = Kit()
        readI(o.optJSONArray("sample"), k.sample)
        readF(o.optJSONArray("level"), k.level)
        readF2(o.optJSONArray("knob"), k.knob)
        readF(o.optJSONArray("start"), k.start)
        readF(o.optJSONArray("end"), k.end)
        val r = IntArray(NV)
        val l = IntArray(NV)
        readI(o.optJSONArray("reverse"), r)
        readI(o.optJSONArray("loop"), l)
        for (v in 0 until NV) { k.reverse[v] = r[v] != 0; k.loop[v] = l[v] != 0 }
        return k
    }

    fun save(
        mem: Memory, pattern: Pattern, kit: Kit, curPat: Int, curKit: Int,
        bpm: Int, volume: Float, st: Settings
    ) {
        try {
            val root = JSONObject()
            root.put("version", 1)
            root.put("settings", ia(st.v))
            root.put("bpm", bpm)
            root.put("volume", volume.toDouble())
            root.put("curPat", curPat)
            root.put("curKit", curKit)
            root.put("pattern", patToJson(pattern))
            root.put("kit", kitToJson(kit))
            val pats = JSONArray()
            for (i in 0 until 64) { val p = mem.patterns[i]; if (p == null) pats.put(JSONObject.NULL) else pats.put(patToJson(p)) }
            val kits = JSONArray()
            for (i in 0 until 64) { val k = mem.kits[i]; if (k == null) kits.put(JSONObject.NULL) else kits.put(kitToJson(k)) }
            root.put("pats", pats)
            root.put("kits", kits)
            file.writeText(root.toString())
        } catch (e: Exception) { }
    }

    fun load(st: Settings): Snapshot? {
        return try {
            if (!file.exists()) return null
            val root = JSONObject(file.readText())
            readI(root.optJSONArray("settings"), st.v)
            val mem = Memory()
            val pats = root.optJSONArray("pats")
            val kits = root.optJSONArray("kits")
            if (pats != null) for (i in 0 until minOf(64, pats.length())) if (!pats.isNull(i)) mem.patterns[i] = patFromJson(pats.getJSONObject(i))
            if (kits != null) for (i in 0 until minOf(64, kits.length())) if (!kits.isNull(i)) mem.kits[i] = kitFromJson(kits.getJSONObject(i))
            Snapshot(
                mem,
                patFromJson(root.getJSONObject("pattern")),
                kitFromJson(root.getJSONObject("kit")),
                root.optInt("curPat", 0), root.optInt("curKit", 0),
                root.optInt("bpm", 120), root.optDouble("volume", 0.7).toFloat()
            )
        } catch (e: Exception) { null }
    }
}
