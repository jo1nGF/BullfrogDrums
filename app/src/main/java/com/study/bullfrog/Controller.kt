package com.study.bullfrog

import kotlin.math.abs

/** Services the Activity provides to the machine (permissions, file pickers). */
interface Host {
    fun requestMic(): Boolean
    fun importWavs(folder: Int)
    fun exportBackup()
    fun importBackup()
    fun toast(s: String)
}

/** Button ids (front panel). Step keys are handled separately (0..15). */
object B {
    const val SHIFT = 0
    const val TRACK = 1
    const val CLEAR = 2
    const val LAST = 3
    const val SHUF = 4
    const val MUTE = 5
    const val PATT = 6
    const val KIT = 7
    const val SAMPLE = 8
    const val START = 9
    const val END = 10
    const val REV = 11
    const val LOOP = 12
    const val ACCENT = 13
    const val RATCH = 14
    const val ODDS = 15
    const val BAR1 = 16
    const val BAR2 = 17
    const val BAR3 = 18
    const val BAR4 = 19
    const val PLAY = 20
    const val STOP = 21
    const val REC = 22
    const val COUNT = 24
}

/** Operating "pages" (the lit mode buttons). */
object Pg {
    const val NONE = 0
    const val PATT = 1
    const val KIT = 2
    const val SAMPLE = 3
    const val START = 4
    const val END = 5
    const val ACCENT = 6
    const val RATCH = 7
    const val ODDS = 8
    const val UTIME = 9
    const val ROLL = 10
    const val MUTE = 11
    const val SOLO = 12
    const val SCALE = 13
    const val DIRECT = 14
    const val CONFIG = 15
}

private fun pad2(n: Int): String = (if (n < 0) 0 else if (n > 99) 99 else n).toString().padStart(2, '0')
private fun pct(v: Float): String = (v * 100f + 0.5f).toInt().toString().padStart(3)

private class StepClip(
    val trig: Int, val ratchet: Int, val odds: Int, val utime: Float,
    val locks: FloatArray, val note: Int, val gate: Int, val slide: Boolean
)

/**
 * The front panel logic: every button combination described in the manual
 * (hold TRACK + step key, SHIFT + LAST STEP = SCALE, ...) is implemented here.
 */
class Controller(
    private val host: Host,
    val e: Engine,
    val store: SampleStore,
    val st: Settings,
    private val storage: Storage
) {
    val mem = Memory()
    var curPat = 0
    var curKit = 0
    var curTrack = 0
    var curBar = 0
    var tapMode = false
    var tapRec = false
    var page = Pg.NONE
    val held = BooleanArray(B.COUNT)
    private val used = BooleanArray(B.COUNT)
    private val pressAt = LongArray(B.COUNT)
    val heldStep = BooleanArray(16)
    private val stepEdited = BooleanArray(16)
    private val stepPending = BooleanArray(16)
    var bank = 0
    var folder = 0
    var cvGateEdit = false
    var slideMode = false
    var ctrlAll = false
    private var ctrlSnap: Kit? = null
    private var masterEdit = false
    private var clip: StepClip? = null

    // knobs: physical positions and pot-catch state
    val physP = FloatArray(NP)
    val caughtP = BooleanArray(NP) { true }
    val physL = FloatArray(NV)
    val caughtL = BooleanArray(NV) { true }

    // display
    private var msgText: String? = null
    private var msgUntil = 0L

    // configuration menu
    var cfgIdx = 0
    private var cfgEdit = false
    private var cfgOpt = 0
    private var confirmDel = false
    private var delSel = 0

    // sampling
    var samplingState = 0   // 0 off, 1 options, 2 armed, 3 recording, 4 review
    val recorder = Recorder()
    private var smpOpt = 0
    private var smpEdit = false
    private val smpVal = intArrayOf(0, 2, 0, 1, 1, 25)   // SRC, LVL, MON, THR, ASN, TIM
    private var rec: FloatArray? = null
    private var trimS = 0f
    private var trimE = 1f
    private val smpNames = arrayOf("SRC", "LVL", "MON", "THR", "ASN", "TIM")

    // ------------------------------------------------------------------ lifecycle

    fun loadState() {
        synchronized(e.lock) {
            val snap = storage.load(st)
            for (i in 0 until 64) { mem.patterns[i] = null; mem.kits[i] = null }
            if (snap != null) {
                for (i in 0 until 64) { mem.patterns[i] = snap.mem.patterns[i]; mem.kits[i] = snap.mem.kits[i] }
                curPat = snap.curPat
                curKit = snap.curKit
                if (st.v[S.LAST_LOAD] == 1) {
                    e.pattern = snap.pattern
                    e.kit = snap.kit
                } else {
                    e.pattern = Pattern()
                    e.kit = Kit()
                }
                e.bpm = snap.bpm
                e.volume = snap.volume
            } else {
                resetFactory()
            }
            store.ensureKit(e.kit)
            syncKnobs()
        }
    }

    private fun resetFactory() {
        st.defaults()
        for (i in 0 until 64) { mem.patterns[i] = null; mem.kits[i] = null }
        Factory.build(mem)
        curPat = 0
        curKit = 0
        e.pattern = mem.patterns[0]!!.copy()
        e.kit = mem.kits[0]!!.copy()
        e.bpm = e.pattern.bpm
        e.volume = 0.7f
        for (t in 0 until NT) { e.muted[t] = false; e.solo[t] = false }
    }

    private fun factoryReset() {
        e.stop()
        storage.delete()
        resetFactory()
        store.ensureKit(e.kit)
        page = Pg.NONE
        cfgEdit = false
        curTrack = 0
        syncKnobs()
        persist()
        msg("RST")
    }

    fun persist() {
        storage.save(mem, e.pattern, e.kit, curPat, curKit, e.bpm, e.volume, st)
    }

    fun onPause() {
        synchronized(e.lock) {
            exitSampling()
            e.stop()
            persist()
        }
    }

    private fun syncKnobs() {
        for (p in 0 until NP) { physP[p] = storedParam(p); caughtP[p] = true }
        for (v in 0 until NV) { physL[v] = e.kit.level[v]; caughtL[v] = true }
    }

    private fun onContextChange() {
        for (p in 0 until NP) {
            caughtP[p] = if (curTrack < NV) abs(physP[p] - e.kit.knob[curTrack][p]) < 0.015f else true
        }
        for (v in 0 until NV) caughtL[v] = abs(physL[v] - e.kit.level[v]) < 0.015f
    }

    fun storedParam(p: Int): Float = if (curTrack < NV) e.kit.knob[curTrack][p] else physP[p]

    // ------------------------------------------------------------------ display

    private fun msg(s: String, ms: Long = 1100L) {
        msgText = s
        msgUntil = System.currentTimeMillis() + ms
    }

    private fun firstHeldStep(): Int {
        for (i in 0 until 16) if (heldStep[i]) return i
        return -1
    }

    private fun lockOrBase(t: Int, p: Int, idx: Int): Float {
        val l = e.pattern.locks[t][p][idx]
        if (!l.isNaN()) return l
        return if (p == Prm.START) e.kit.start[t] else e.kit.end[t]
    }

    fun display(): String {
        if (confirmDel) return if (delSel == 0) "N" else "Y"
        val m = msgText
        if (m != null && System.currentTimeMillis() < msgUntil) return m
        val pat = e.pattern
        val t = curTrack
        if (page == Pg.CONFIG) {
            val item = CFG[cfgIdx]
            return if (cfgEdit) item.opts[cfgOpt.coerceIn(0, item.opts.size - 1)] else item.label
        }
        if (samplingState != 0) return samplingDisplay()
        val hs = firstHeldStep()
        if (hs >= 0) {
            val idx = curBar * 16 + hs
            when {
                page == Pg.RATCH -> return "R " + pat.ratchet[t][idx]
                page == Pg.ODDS -> return oddsLabel(pat.odds[t][idx])
                page == Pg.UTIME -> {
                    val u = (pat.utime[t][idx] * 100f).toInt()
                    return if (u < 0) "-" + pad2(-u) else " " + pad2(u)
                }
                (page == Pg.START || page == Pg.END) && t < NV ->
                    return pct(lockOrBase(t, if (page == Pg.START) Prm.START else Prm.END, idx))
                t == CVT ->
                    return if (cvGateEdit) (pat.gate[idx] * 10).toString().padStart(3) else noteLabel(pat.note[idx])
            }
        }
        if (held[B.LAST]) return if (masterEdit) "M" + pad2(pat.master) else "L" + pad2(pat.length[t])
        if (held[B.SHUF]) return "S" + pad2((pat.shuffle[t] * 100f).toInt())
        if (held[B.SAMPLE] || page == Pg.SAMPLE) return if (t < NV) sampleLabel(e.kit.sample[t]) else "---"
        when (page) {
            Pg.PATT -> return "P" + pad2(curPat + 1)
            Pg.KIT -> return "K" + pad2(curKit + 1)
            Pg.START -> return if (t < NV) pct(e.kit.start[t]) else "---"
            Pg.END -> return if (t < NV) pct(e.kit.end[t]) else "---"
            Pg.SCALE -> return SCALE_NAMES[pat.scale[t]]
            Pg.DIRECT -> return DIR_NAMES[pat.dir[t]]
            Pg.ROLL -> return "R" + ROLL_DIVS[e.rollDiv]
            Pg.MUTE -> return "MUT"
            Pg.SOLO -> return "SOL"
            Pg.ACCENT -> return "ACC"
            Pg.RATCH -> return "RAT"
            Pg.ODDS -> return "ODD"
            Pg.UTIME -> return "UTI"
        }
        return e.bpm.toString().padStart(3)
    }

    private fun samplingDisplay(): String {
        return when (samplingState) {
            1 -> if (smpEdit) smpValueText(smpOpt) else smpNames[smpOpt]
            2 -> "ARM"
            3 -> "REC"
            else -> when (page) {
                Pg.START -> pct(trimS)
                Pg.END -> pct(trimE)
                else -> "EDT"
            }
        }
    }

    private fun smpValueText(o: Int): String = when (o) {
        0 -> if (smpVal[0] == 0) "MIC" else "LIN"
        1 -> "L" + pad2(smpVal[1])
        2 -> if (smpVal[2] == 1) "ON" else "OFF"
        3 -> "T" + smpVal[3]
        4 -> if (smpVal[4] == 1) "ON" else "OFF"
        else -> (smpVal[5] * 2).toString().padStart(3)
    }

    // ------------------------------------------------------------------ LEDs

    fun stepLed(i: Int): Int {
        val t = curTrack
        val pat = e.pattern
        if (samplingState != 0) {
            if (samplingState == 1) return 0
            if (samplingState == 4) return if (i == 0) 1 else 0
            return if (i < recorder.progress * 16f) 2 else 0
        }
