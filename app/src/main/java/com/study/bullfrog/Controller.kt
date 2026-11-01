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
        if (held[B.SHUF]) return if (i <= (pat.shuffle[t] * 14f + 0.5f).toInt()) 2 else 0
        if (held[B.LAST]) {
            val l = pat.length[t] - curBar * 16
            return if (i == l - 1) 2 else if (i < l) 1 else 0
        }
        if (held[B.TRACK]) return if (i == t) 2 else if (i < NT) 1 else 0
        if (held[B.SAMPLE] || page == Pg.SAMPLE) return if (i == folder) 2 else 1
        when (page) {
            Pg.PATT -> {
                val s = bank * 16 + i
                return if (s == curPat) 2 else if (mem.patterns[s] != null) 1 else 0
            }
            Pg.KIT -> {
                val s = bank * 16 + i
                return if (s == curKit) 2 else if (mem.kits[s] != null) 1 else 0
            }
            Pg.MUTE -> return if (i < NT && e.muted[i]) 2 else if (i < NT && e.flash[i] > 0.3f) 1 else 0
            Pg.SOLO -> return if (i < NT && e.solo[i]) 2 else 0
            Pg.ROLL -> return if (i < NV && heldStep[i]) 2 else 0
            Pg.CONFIG -> return if (i == cfgIdx) 2 else 1
        }
        val idx = curBar * 16 + i
        val v = pat.trig[t][idx]
        var level = if (v == 2) 2 else if (v == 1) 1 else 0
        if (t == CVT && v != 0) level = if (slideMode && pat.slide[idx]) 1 else 2
        if (e.playing && e.playhead[t] == idx) return if (level > 0) 3 else 4
        return level
    }

    private fun barLed(b: Int): Float {
        if (page == Pg.PATT || page == Pg.KIT) return if (b == bank) 1f else 0f
        if (st.v[S.BAR_FOLLOW] == 1 && e.playing) {
            val ph = e.playhead[curTrack]
            if (ph >= 0 && ph / 16 == b) return 1f
            return if (b == curBar) 0.35f else 0f
        }
        return if (b == curBar) 1f else 0f
    }

    fun btnLed(id: Int): Float {
        val k = e.kit
        val t = curTrack
        return when (id) {
            B.TRACK -> if (tapMode) 1f else 0f
            B.LAST -> if (page == Pg.SCALE) 1f else 0f
            B.SHUF -> if (page == Pg.DIRECT) 1f else 0f
            B.MUTE -> if (page == Pg.MUTE) 0.5f else if (page == Pg.SOLO) 1f else 0f
            B.PATT -> if (page == Pg.PATT) 1f else 0f
            B.KIT -> if (page == Pg.KIT) 1f else 0f
            B.SAMPLE -> if (page == Pg.SAMPLE || held[B.SAMPLE] || samplingState != 0) 1f else 0f
            B.START -> if (page == Pg.START) 1f else 0f
            B.END -> if (page == Pg.END) 1f else 0f
            B.REV -> if (t < NV && k.reverse[t]) 1f else 0f
            B.LOOP -> if (t < NV && k.loop[t]) 1f else 0f
            B.ACCENT -> if (page == Pg.ACCENT || slideMode) 1f else if (page == Pg.UTIME) 0.5f else 0f
            B.RATCH -> if (page == Pg.RATCH) 1f else if (page == Pg.ROLL) 0.5f else 0f
            B.ODDS -> if (page == Pg.ODDS) 1f else 0f
            B.BAR1, B.BAR2, B.BAR3, B.BAR4 -> barLed(id - B.BAR1)
            B.SHIFT -> if (ctrlAll) 1f else 0f
            B.PLAY -> if (e.playing) 1f else 0f
            B.STOP -> if (!e.playing) 1f else 0f
            B.REC -> if (tapRec || held[B.REC]) 1f else 0f
            else -> 0f
        }
    }

    fun levelLed(v: Int): Float {
        if (samplingState != 0) return if (v < recorder.level * 7f) 1f else 0f
        return minOf(1f, e.peakLed[v] * 3f)
    }

    /** Called by the view on every frame. */
    fun tick() {
        if (samplingState == 2 || samplingState == 3) {
            val rs = recorder.state
            if (rs == 3) samplingState = 3
            if (rs == 4) {
                rec = recorder.result
                trimS = 0f
                trimE = 1f
                samplingState = 4
                page = Pg.NONE
            }
        }
    }

    // ------------------------------------------------------------------ buttons

    fun buttonDown(id: Int) { synchronized(e.lock) { btnDown(id) } }
    fun buttonUp(id: Int) { synchronized(e.lock) { btnUp(id) } }
    fun stepDown(i: Int) { synchronized(e.lock) { stepDn(i) } }
    fun stepUp(i: Int) { synchronized(e.lock) { stepUpImpl(i) } }
    fun encoderTurn(d: Int) { synchronized(e.lock) { encTurn(d) } }
    fun encoderPress() { synchronized(e.lock) { encPress() } }
    fun encoderLong(ms: Long) { synchronized(e.lock) { if (page == Pg.CONFIG && cfgIdx == CFG_RESET && ms >= 5000L) factoryReset() else encPress() } }
    fun paramMove(p: Int, v: Float) { synchronized(e.lock) { paramMoveImpl(p, v) } }
    fun levelMove(vc: Int, v: Float) { synchronized(e.lock) { levelMoveImpl(vc, v) } }
    fun volumeMove(v: Float) { synchronized(e.lock) { e.volume = v } }

    private fun setPage(p: Int) {
        val np = if (page == p) Pg.NONE else p
        if (page == Pg.ROLL && np != Pg.ROLL) { for (v in 0 until NV) e.setRoll(v, false); endCtrlAll() }
        page = np
        cfgEdit = false
        masterEdit = false
    }

    private fun endCtrlAll() {
        val s = ctrlSnap
        if (ctrlAll && s != null) {
            for (v in 0 until NV) for (p in 0 until NP) e.kit.knob[v][p] = s.knob[v][p]
        }
        ctrlAll = false
        ctrlSnap = null
    }

    private fun btnDown(id: Int) {
        held[id] = true
        used[id] = false
        pressAt[id] = System.currentTimeMillis()
        when (id) {
            B.PLAY -> onPlay()
            B.STOP -> onStop()
            B.REC -> onRecDown()
            B.SHIFT -> { }
            B.TRACK -> {
                if (held[B.SHIFT]) {
                    tapMode = !tapMode
                    tapRec = false
                    used[B.SHIFT] = true
                    msg(if (tapMode) "TAP" else "STP")
                } else if (held[B.START]) { toggleScan(0); used[B.START] = true }
                else if (held[B.END]) { toggleScan(1); used[B.END] = true }
                else if (held[B.LOOP]) { toggleScan(2); used[B.LOOP] = true }
            }
            B.CLEAR -> if (held[B.SHIFT]) {
                e.pattern.clearLocks(curTrack)
                used[B.SHIFT] = true
                used[B.CLEAR] = true
                msg("CLR")
            }
            B.LAST -> if (held[B.SHIFT]) { setPage(Pg.SCALE); used[B.SHIFT] = true; used[B.LAST] = true }
            B.SHUF -> if (held[B.SHIFT]) { setPage(Pg.DIRECT); used[B.SHIFT] = true; used[B.SHUF] = true }
            B.MUTE -> { setPage(if (held[B.SHIFT]) Pg.SOLO else Pg.MUTE); if (held[B.SHIFT]) used[B.SHIFT] = true }
            B.PATT, B.KIT -> {
                if (held[B.SHIFT]) {
                    used[B.SHIFT] = true
                    if (id == B.PATT) recallPattern(curPat, false) else recallKit(curKit)
                    msg("RLD")
                } else setPage(if (id == B.PATT) Pg.PATT else Pg.KIT)
            }
            B.SAMPLE -> { }
            B.START, B.END, B.LOOP -> { }
            B.REV -> if (curTrack < NV) { e.kit.reverse[curTrack] = !e.kit.reverse[curTrack]; msg(if (e.kit.reverse[curTrack]) "REV" else "FWD") }
            B.ACCENT -> {
                if (held[B.SHIFT]) { setPage(Pg.UTIME); used[B.SHIFT] = true }
                else if (curTrack == CVT) { slideMode = !slideMode; msg(if (slideMode) "SLD" else "NOT") }
                else setPage(Pg.ACCENT)
            }
            B.RATCH -> { if (held[B.SHIFT]) { setPage(Pg.ROLL); used[B.SHIFT] = true } else setPage(Pg.RATCH) }
            B.ODDS -> setPage(Pg.ODDS)
            B.BAR1, B.BAR2, B.BAR3, B.BAR4 -> barPress(id - B.BAR1)
        }
    }

    private fun btnUp(id: Int) {
        held[id] = false
        val dur = System.currentTimeMillis() - pressAt[id]
        when (id) {
            B.REC -> {
                if (!used[B.REC]) recTap()
                java.util.Arrays.fill(e.autoOn, false)
                e.autoTrack = -1
            }
            B.SHIFT -> {
                if (!used[B.SHIFT] && page == Pg.ROLL) {
                    if (!ctrlAll) {
                        ctrlSnap = e.kit.copy()
                        ctrlAll = true
                        msg("ALL")
                    } else endCtrlAll()
                }
            }
            B.START -> if (!used[B.START]) { if (samplingState == 0 || samplingState == 4) setPage(Pg.START) }
            B.END -> if (!used[B.END]) { if (samplingState == 0 || samplingState == 4) setPage(Pg.END) }
            B.LOOP -> if (!used[B.LOOP] && curTrack < NV) { e.kit.loop[curTrack] = !e.kit.loop[curTrack]; msg(if (e.kit.loop[curTrack]) "LP1" else "LP0") }
            B.SAMPLE -> {
                if (!used[B.SAMPLE] && samplingState == 0 && dur < 450L) {
                    if (page == Pg.SAMPLE) auditionCurrent() else setPage(Pg.SAMPLE)
                }
            }
        }
    }

    private fun onPlay() {
        if (samplingState == 4) { playRec(); return }
        if (samplingState != 0) return
        e.togglePlay()
    }

    private fun onStop() {
        if (samplingState != 0) { exitSampling(); return }
        val was = e.playing || e.paused
        e.stop()
        tapRec = false
        confirmDel = false
        if (!was) {
            for (v in 0 until NV) e.setRoll(v, false)
            endCtrlAll()
            page = Pg.NONE
            cfgEdit = false
        }
    }

    private fun toggleScan(w: Int) {
        if (curTrack >= NV) return
        e.scan[w][curTrack] = !e.scan[w][curTrack]
        msg((if (w == 0) "S" else if (w == 1) "E" else "L") + (if (e.scan[w][curTrack]) "-1" else "-0"))
    }

    private fun barPress(b: Int) {
        if (samplingState != 0) {
            if (b == 3) { smpVal[2] = 1 - smpVal[2]; msg(smpValueText(2)) }
            return
        }
        if (page == Pg.PATT || page == Pg.KIT) bank = b else curBar = b
    }

    private fun selectTrack(i: Int) {
        curTrack = i
        cvGateEdit = false
        slideMode = false
        onContextChange()
        msg(if (i == CVT) "CV" else "TR" + (i + 1))
    }

    // ------------------------------------------------------------------ REC key

    private fun onRecDown() {
        if (samplingState == 1) { armSampling(); used[B.REC] = true; return }
        if (samplingState == 2 || samplingState == 3) { recorder.finishEarly(); used[B.REC] = true; return }
        if (held[B.SAMPLE] && samplingState == 0) { enterSampling(); used[B.SAMPLE] = true; used[B.REC] = true; return }
        val hs = firstHeldStep()
        if (hs >= 0 && page == Pg.NONE && !tapMode) {
            if (held[B.SHIFT]) { pasteStep(hs); used[B.SHIFT] = true } else copyStep(hs)
            stepEdited[hs] = true
            used[B.REC] = true
        }
    }

    private fun recTap() {
        when {
            page == Pg.PATT -> savePattern(curPat)
            page == Pg.KIT -> saveKit(curKit)
            tapMode -> { tapRec = !tapRec; msg(if (tapRec) "REC" else "STP") }
        }
    }

    private fun copyStep(i: Int) {
        val idx = curBar * 16 + i
        val t = curTrack
        val p = e.pattern
        val lk = FloatArray(NLOCK) { p.locks[t][it][idx] }
        clip = StepClip(p.trig[t][idx], p.ratchet[t][idx], p.odds[t][idx], p.utime[t][idx], lk, p.note[idx], p.gate[idx], p.slide[idx])
        msg("CPY")
    }

    private fun pasteStep(i: Int) {
        val c = clip ?: return
        val idx = curBar * 16 + i
        val t = curTrack
        val p = e.pattern
        p.trig[t][idx] = c.trig
        p.ratchet[t][idx] = c.ratchet
        p.odds[t][idx] = c.odds
        p.utime[t][idx] = c.utime
        for (q in 0 until NLOCK) p.locks[t][q][idx] = c.locks[q]
        if (t == CVT) { p.note[idx] = c.note; p.gate[idx] = c.gate; p.slide[idx] = c.slide }
        msg("PST")
    }

    // ------------------------------------------------------------------ step keys

    private fun stepDn(i: Int) {
        heldStep[i] = true
        stepEdited[i] = false
        stepPending[i] = false
        if (held[B.SHIFT] && i == 15 && samplingState == 0) {
            setPage(Pg.CONFIG)
            cfgIdx = 0
            used[B.SHIFT] = true
            return
        }
        if (samplingState != 0) {
            if (samplingState == 4 && i == 0) playRec()
            return
        }
        when {
            held[B.TRACK] -> { if (i < NT) selectTrack(i); used[B.TRACK] = true }
            held[B.LAST] -> { setLength(curBar * 16 + i + 1); used[B.LAST] = true }
            held[B.SHUF] -> {
                e.pattern.shuffle[curTrack] = minOf(1f, i / 14f)
                used[B.SHUF] = true
            }
            held[B.CLEAR] -> { clearCmd(i); used[B.CLEAR] = true }
            held[B.SAMPLE] || page == Pg.SAMPLE -> { selectFolder(i); if (held[B.SAMPLE]) used[B.SAMPLE] = true }
            page == Pg.PATT -> patKey(i)
            page == Pg.KIT -> kitKey(i)
            page == Pg.CONFIG -> { cfgIdx = i; cfgEdit = false }
            page == Pg.MUTE -> { if (i < NT) e.muted[i] = !e.muted[i] }
            page == Pg.SOLO -> { if (i < NT) e.solo[i] = !e.solo[i] }
            page == Pg.ROLL -> { if (i < NV) e.setRoll(i, true) }
            tapMode && page == Pg.NONE -> tapHit(i)
            else -> stepPending[i] = true
        }
    }

    private fun stepUpImpl(i: Int) {
        heldStep[i] = false
        if (page == Pg.ROLL && i < NV) e.setRoll(i, false)
        if (stepPending[i]) {
            stepPending[i] = false
            if (!stepEdited[i]) onStepTap(i)
        }
        stepEdited[i] = false
    }

    private fun tapHit(i: Int) {
        if (i >= NV) return
        e.triggerManual(i)
        if (tapRec) e.recordTap(i)
    }

    private fun onStepTap(i: Int) {
        val idx = curBar * 16 + i
        val pat = e.pattern
        val t = curTrack
        when (page) {
            Pg.ACCENT -> {
                val c = pat.trig[t][idx]
                if (c == 1) pat.trig[t][idx] = 2 else if (c == 2) pat.trig[t][idx] = 1
            }
            Pg.RATCH, Pg.ODDS, Pg.UTIME, Pg.START, Pg.END -> { }
            else -> {
                if (slideMode && t == CVT) pat.slide[idx] = !pat.slide[idx] else toggleStep(t, idx)
            }
        }
    }

    private fun toggleStep(t: Int, idx: Int) {
        val pat = e.pattern
        val cur = pat.trig[t][idx]
        if (t == CVT) {
            pat.trig[t][idx] = if (cur == 0) 1 else 0
        } else if (st.v[S.ACCENT] == 0) {
            pat.trig[t][idx] = if (cur == 0) 2 else 0
        } else {
            pat.trig[t][idx] = when (cur) { 0 -> 2; 2 -> 1; else -> 0 }
        }
    }

    private fun setLength(n: Int) {
        val l = n.coerceIn(1, MAXS)
        e.pattern.length[curTrack] = l
        msg("L" + pad2(l))
    }

    private fun clearCmd(i: Int) {
        when (page) {
            Pg.PATT -> mem.patterns[bank * 16 + i] = null
            Pg.KIT -> mem.kits[bank * 16 + i] = null
            else -> if (i < NT) e.pattern.clearTrack(i)
        }
        msg("CLR")
    }

    // ------------------------------------------------------------------ patterns & kits

    private fun patKey(i: Int) {
        val slot = bank * 16 + i
        if (held[B.REC]) { savePattern(slot); used[B.REC] = true } else recallPattern(slot, true)
    }

    private fun kitKey(i: Int) {
        val slot = bank * 16 + i
        if (held[B.REC]) { saveKit(slot); used[B.REC] = true } else recallKit(slot)
    }

    private fun savePattern(slot: Int) {
        val p = e.pattern.copy()
        p.bpm = e.bpm
        p.linkedKit = if (st.v[S.KIT_LINK] == 1) curKit else -1
        mem.patterns[slot] = p
        curPat = slot
        persist()
        msg("SAV")
    }

    private fun saveKit(slot: Int) {
        mem.kits[slot] = e.kit.copy()
        curKit = slot
        persist()
        msg("SAV")
    }

    private fun recallPattern(slot: Int, withKit: Boolean) {
        val src = mem.patterns[slot]
        val p: Pattern
        if (src == null) { p = Pattern(); p.bpm = e.bpm } else p = src.copy()
        var k: Kit? = null
        if (withKit && st.v[S.KIT_LINK] == 1 && p.linkedKit >= 0) {
            val kk = mem.kits[p.linkedKit]
            if (kk != null) {
                val c = kk.copy()
                store.ensureKit(c)
                k = c
                curKit = p.linkedKit
            }
        }
        curPat = slot
        e.queuePattern(p, k)
        onContextChange()
        msg("P" + pad2(slot + 1))
    }

    private fun recallKit(slot: Int) {
        val src = mem.kits[slot]
        val k = if (src == null) Kit() else src.copy()
        store.ensureKit(k)
        e.setKit(k)
        curKit = slot
        onContextChange()
        msg("K" + pad2(slot + 1))
    }

    // ------------------------------------------------------------------ samples

    private fun selectFolder(i: Int) {
        folder = i
        if (curTrack >= NV) return
        var first = 0
        for (k in 0 until MAXSAMP) if (store.exists(i, k)) { first = k; break }
        assignSample(i * MAXSAMP + first)
    }

    private fun assignSample(code: Int) {
        if (curTrack >= NV) return
        e.kit.sample[curTrack] = code
        val d = store.getCode(code)
        if (d != null) e.audition(d, 0f, 1f)
    }

    private fun sampleTurn(d: Int) {
        if (curTrack >= NV) return
        val cur = e.kit.sample[curTrack]
        var i = if (cur >= 0 && cur / MAXSAMP == folder) cur % MAXSAMP else 0
        var steps = abs(d)
        val dir = if (d > 0) 1 else -1
        var guard = 0
        while (steps > 0 && guard < 200) {
            i = (i + dir + MAXSAMP) % MAXSAMP
            guard++
            if (store.exists(folder, i)) steps--
        }
        if (store.exists(folder, i)) assignSample(folder * MAXSAMP + i)
    }

    private fun auditionCurrent() {
        if (curTrack >= NV) return
        val d = store.getCode(e.kit.sample[curTrack])
        if (d != null) e.audition(d, e.kit.start[curTrack], e.kit.end[curTrack])
    }

    // ------------------------------------------------------------------ sampling (live recording)

    private fun enterSampling() {
        if (!host.requestMic()) { msg("MIC"); return }
        e.stop()
        if (!recorder.open()) { msg("ERR"); return }
        samplingState = 1
        smpOpt = 0
        smpEdit = false
        rec = null
        page = Pg.NONE
    }

    private fun exitSampling() {
        recorder.close()
        samplingState = 0
        rec = null
        page = Pg.NONE
    }

    private fun armSampling() {
        val gain = 0.5f + 0.25f * smpVal[1]
        val thr = smpVal[3] * 0.06f
        recorder.arm(thr, smpVal[5] * 0.2f, gain)
        samplingState = 2
    }

    private fun playRec() {
        val r = rec ?: return
        e.audition(r, trimS, trimE)
    }

    private fun saveRecording() {
        val r = rec ?: return
        val s = (trimS * r.size).toInt()
        val en = (trimE * r.size).toInt()
        if (en - s < 64) { msg("ERR"); return }
        val idx = store.firstFree(15)
        if (idx < 0) { msg("FUL"); return }
        store.putUser(15, idx, r.copyOfRange(s, en))
        val code = 15 * MAXSAMP + idx
        folder = 15
        if (smpVal[4] == 1 && curTrack < NV) e.kit.sample[curTrack] = code
        msg(sampleLabel(code))
    }

    private fun samplingTurn(d: Int): Boolean {
        if (samplingState == 4) {
            if (page == Pg.START) trimS = (trimS + d * 0.005f).coerceIn(0f, trimE - 0.01f)
            else if (page == Pg.END) trimE = (trimE + d * 0.005f).coerceIn(trimS + 0.01f, 1f)
            return true
        }
        if (samplingState == 1) {
            if (smpEdit) {
                when (smpOpt) {
                    0 -> smpVal[0] = (smpVal[0] + d).coerceIn(0, 1)
                    1 -> smpVal[1] = (smpVal[1] + d).coerceIn(0, 20)
                    2 -> smpVal[2] = (smpVal[2] + d).coerceIn(0, 1)
                    3 -> smpVal[3] = (smpVal[3] + d).coerceIn(0, 9)
                    4 -> smpVal[4] = (smpVal[4] + d).coerceIn(0, 1)
                    else -> smpVal[5] = (smpVal[5] + d).coerceIn(1, 25)
                }
            } else smpOpt = ((smpOpt + d) % 6 + 6) % 6
        }
        return true
    }

    // ------------------------------------------------------------------ encoder

    private fun encTurn(d: Int) {
        if (d == 0) return
        if (confirmDel) { delSel = 1 - delSel; return }
        if (page == Pg.CONFIG) { cfgTurn(d); return }
        if (samplingState != 0) { samplingTurn(d); return }
        if (firstHeldStep() >= 0 && stepEditTurn(d)) return
        if (held[B.LAST]) {
            val pat = e.pattern
            if (masterEdit) pat.master = (pat.master + d).coerceIn(0, MAXS)
            else pat.length[curTrack] = (pat.length[curTrack] + d).coerceIn(1, MAXS)
            used[B.LAST] = true
            return
        }
        if (held[B.SAMPLE] || page == Pg.SAMPLE) {
            sampleTurn(d)
            if (held[B.SAMPLE]) used[B.SAMPLE] = true
            return
        }
        val t = curTrack
        when (page) {
            Pg.START -> { if (t < NV) e.kit.start[t] = (e.kit.start[t] + d * 0.01f).coerceIn(0f, 1f); return }
            Pg.END -> { if (t < NV) e.kit.end[t] = (e.kit.end[t] + d * 0.01f).coerceIn(0f, 1f); return }
            Pg.SCALE -> { e.pattern.scale[t] = (e.pattern.scale[t] + d).coerceIn(0, SCALES.size - 1); return }
            Pg.DIRECT -> { e.pattern.dir[t] = (e.pattern.dir[t] + d).coerceIn(0, DIR_NAMES.size - 1); return }
            Pg.ROLL -> { e.rollDiv = (e.rollDiv + d).coerceIn(0, ROLL_DIVS.size - 1); msg("R" + ROLL_DIVS[e.rollDiv]); return }
        }
        e.bpm = (e.bpm + d * (if (held[B.SHIFT]) 10 else 1)).coerceIn(20, 300)
        if (held[B.SHIFT]) used[B.SHIFT] = true
    }

    private fun stepEditTurn(d: Int): Boolean {
        val pat = e.pattern
        val t = curTrack
        var any = false
        for (i in 0 until 16) {
            if (!heldStep[i]) continue
            val idx = curBar * 16 + i
            var h = false
            if (page == Pg.RATCH) {
                pat.ratchet[t][idx] = (pat.ratchet[t][idx] + d).coerceIn(1, 8)
                h = true
            } else if (page == Pg.ODDS) {
                var pos = ODDS_AXIS.indexOf(pat.odds[t][idx])
                if (pos < 0) pos = 10
                pos = (pos + d).coerceIn(0, ODDS_AXIS.size - 1)
                pat.odds[t][idx] = ODDS_AXIS[pos]
                h = true
            } else if (page == Pg.UTIME) {
                pat.utime[t][idx] = (pat.utime[t][idx] + d * 0.02f).coerceIn(-0.5f, 0.5f)
                h = true
            } else if ((page == Pg.START || page == Pg.END) && t < NV) {
                val p = if (page == Pg.START) Prm.START else Prm.END
                pat.locks[t][p][idx] = (lockOrBase(t, p, idx) + d * 0.01f).coerceIn(0f, 1f)
                h = true
            } else if (t == CVT) {
                if (cvGateEdit) {
                    pat.gate[idx] = (pat.gate[idx] + d).coerceIn(1, 10)
                } else {
                    val step = if (held[B.SHIFT]) 12 * d else d
                    pat.note[idx] = (pat.note[idx] + step).coerceIn(0, 119)
                    e.previewNote(pat.note[idx])
                    if (held[B.SHIFT]) used[B.SHIFT] = true
                }
                h = true
            }
            if (h) { stepEdited[i] = true; any = true }
        }
        return any
    }

    private fun encPress() {
        if (confirmDel) {
            if (delSel == 1 && curTrack < NV) {
                val c = e.kit.sample[curTrack]
                if (c >= 0 && c / MAXSAMP == 15) { store.delete(15, c % MAXSAMP); e.kit.sample[curTrack] = -1; msg("DEL") }
            }
            confirmDel = false
            return
        }
        if (page == Pg.CONFIG) { cfgPress(); return }
        if (samplingState != 0) {
            if (samplingState == 1) smpEdit = !smpEdit
            else if (samplingState == 4) saveRecording()
            return
        }
        if (held[B.LAST]) { masterEdit = !masterEdit; used[B.LAST] = true; msg(if (masterEdit) "M" + pad2(e.pattern.master) else "L" + pad2(e.pattern.length[curTrack])); return }
        if ((held[B.SAMPLE] || page == Pg.SAMPLE) && folder == 15 && held[B.CLEAR]) {
            confirmDel = true
            delSel = 0
            used[B.CLEAR] = true
            return
        }
        if (curTrack == CVT) {
            cvGateEdit = !cvGateEdit
            msg(if (cvGateEdit) "GAT" else "NOT")
        }
    }

    private fun cfgTurn(d: Int) {
        if (cfgEdit) {
            val item = CFG[cfgIdx]
            cfgOpt = (cfgOpt + d).coerceIn(0, item.opts.size - 1)
        } else {
            cfgIdx = ((cfgIdx + d) % CFG.size + CFG.size) % CFG.size
        }
    }

    private fun cfgPress() {
        val item = CFG[cfgIdx]
        if (!cfgEdit) {
            cfgOpt = if (item.key >= 0) st.v[item.key] else 0
            cfgEdit = true
            return
        }
        cfgEdit = false
        if (item.key >= 0) {
            st.v[item.key] = cfgOpt
            onContextChange()
            persist()
            msg("OK")
        } else if (cfgIdx == CFG_USB) {
            when (cfgOpt) {
                0 -> host.importWavs(folder)
                1 -> host.exportBackup()
                else -> host.importBackup()
            }
        } else msg("N-A")
    }

    // ------------------------------------------------------------------ knobs

    private fun paramMoveImpl(p: Int, nv: Float) {
        val old = physP[p]
        physP[p] = nv
        val t = curTrack
        if (t >= NV) return
        if (held[B.CLEAR]) {
            for (s in 0 until MAXS) e.pattern.locks[t][p][s] = Float.NaN
            used[B.CLEAR] = true
            msg("CLR")
            return
        }
        val hs = firstHeldStep()
        if (hs >= 0 && !tapMode) {
            for (i in 0 until 16) if (heldStep[i]) {
                e.pattern.locks[t][p][curBar * 16 + i] = nv
                stepEdited[i] = true
            }
            msg(pct(nv))
            return
        }
        val stored = e.kit.knob[t][p]
        if (!caughtP[p]) {
            val lo = minOf(old, nv) - 0.012f
            val hi = maxOf(old, nv) + 0.012f
            if (st.v[S.POT_CATCH] == 0 || (stored >= lo && stored <= hi)) caughtP[p] = true else return
        }
        if (held[B.SHIFT] || ctrlAll) {
            for (v in 0 until NV) e.kit.knob[v][p] = nv
            if (held[B.SHIFT]) used[B.SHIFT] = true
        } else e.kit.knob[t][p] = nv
        if (held[B.REC] && e.playing) {
            e.autoTrack = t
            e.autoOn[p] = true
            e.autoVal[p] = nv
            used[B.REC] = true
        }
        msg(pct(nv), 600L)
    }

    private fun levelMoveImpl(v: Int, nv: Float) {
        val old = physL[v]
        physL[v] = nv
        val stored = e.kit.level[v]
        if (!caughtL[v]) {
            val lo = minOf(old, nv) - 0.012f
            val hi = maxOf(old, nv) + 0.012f
            if (st.v[S.POT_CATCH] == 0 || (stored >= lo && stored <= hi)) caughtL[v] = true else return
        }
        e.kit.level[v] = nv
    }
}
