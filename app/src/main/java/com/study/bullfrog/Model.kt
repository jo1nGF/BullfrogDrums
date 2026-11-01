package com.study.bullfrog

const val SR = 48000            // sample rate (Hz)
const val NV = 7                // sample voices (tracks 1-7)
const val NT = 8                // tracks: 7 voices + CV/Gate
const val CVT = 7               // index of the CV/Gate track (track 8)
const val MAXS = 64             // max steps per track (4 bars x 16)
const val NFOLDERS = 16         // sample folders (A..O + R)
const val MAXSAMP = 64          // samples per folder
const val MAX_SAMPLE_LEN = 262144   // 512 kB / 16 bit = ~5.46 s @ 48 kHz
const val NP = 7                // sound parameters per voice
const val NLOCK = 9             // lockable params: 7 + START + END

object Prm {
    const val PITCH = 0
    const val DECAY = 1
    const val LOOP = 2
    const val CUTOFF = 3
    const val RES = 4
    const val DRIVE = 5
    const val PAN = 6
    const val START = 7
    const val END = 8
    val NAMES = arrayOf("PITCH", "DECAY", "LOOP POINT", "CUTOFF", "RESONANCE", "DRIVE", "PAN")
}

// Time divisions (SHIFT + LAST STEP / SCALE)
val SCALES = doubleArrayOf(0.125, 0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 4.0)
val SCALE_NAMES = arrayOf("1-8", "1-4", "1-2", "3-4", "1", "3-2", "2", "4")
// Play directions (SHIFT + SHUFFLE / DIRECT)
val DIR_NAMES = arrayOf("FWD", "BWD", "PP", "PP2", "RND")
// Roll rates: triggers per step
val ROLL_DIVS = intArrayOf(1, 2, 3, 4, 6, 8)

// ODDS: code 0 = always; 1..10 = cyclic fractions n/d; 11..15 = random percentages
val ODDS_N = intArrayOf(1, 7, 3, 2, 1, 1, 1, 1, 1, 1, 1)
val ODDS_D = intArrayOf(1, 8, 4, 3, 2, 3, 4, 5, 6, 7, 8)
val ODDS_PCT = floatArrayOf(0.90f, 0.75f, 0.50f, 0.25f, 0.10f)
// Encoder axis: counter-clockwise = fractions, clockwise = percentages
val ODDS_AXIS = intArrayOf(10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0, 11, 12, 13, 14, 15)
fun oddsLabel(code: Int): String = when {
    code == 0 -> "---"
    code in 1..10 -> "" + ODDS_N[code] + "-" + ODDS_D[code]
    else -> "" + (ODDS_PCT[code - 11] * 100f).toInt() + "P"
}

const val NOTE_DEFAULT = 0   // C0 = 0 V (see manual: default note on CV/Gate steps)
val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
fun noteLabel(n: Int): String {
    val nm = NOTE_NAMES[((n % 12) + 12) % 12]
    val oct = n / 12
    return if (nm.length == 1) nm + " " + oct else nm + oct
}

fun folderLetter(f: Int): Char = if (f == 15) 'R' else ('A' + f)
fun sampleLabel(code: Int): String {
    if (code < 0) return "---"
    val f = code / MAXSAMP
    val i = code % MAXSAMP + 1
    return "" + folderLetter(f) + (if (i < 10) "0" else "") + i
}

/** A kit: samples, levels and the position of the 7 sound controls of each voice. */
class Kit {
    val sample = IntArray(NV) { -1 }               // folder * 64 + index
    val level = FloatArray(NV) { 0.6f }
    val knob = Array(NV) { floatArrayOf(0.5f, 1f, 0f, 0.5f, 0f, 0f, 0.5f) }
    val start = FloatArray(NV)
    val end = FloatArray(NV) { 1f }
    val reverse = BooleanArray(NV)
    val loop = BooleanArray(NV)

    fun copy(): Kit {
        val k = Kit()
        for (v in 0 until NV) {
            k.sample[v] = sample[v]
            k.level[v] = level[v]
            for (p in 0 until NP) k.knob[v][p] = knob[v][p]
            k.start[v] = start[v]
            k.end[v] = end[v]
            k.reverse[v] = reverse[v]
            k.loop[v] = loop[v]
        }
        return k
    }
}

/** A pattern: triggers, per-step features, parameter locks / automations, CV/Gate track. */
class Pattern {
    val trig = Array(NT) { IntArray(MAXS) }           // 0 off, 1 on, 2 accent
    val ratchet = Array(NT) { IntArray(MAXS) { 1 } }
    val odds = Array(NT) { IntArray(MAXS) }
    val utime = Array(NT) { FloatArray(MAXS) }        // -0.5 .. +0.5 of a step
    val locks = Array(NT) { Array(NLOCK) { FloatArray(MAXS) { Float.NaN } } }
    val note = IntArray(MAXS) { NOTE_DEFAULT }        // CV/Gate notes (0 = C0 = 0 V)
    val gate = IntArray(MAXS) { 1 }                   // 1..10 -> 10%..100%
    val slide = BooleanArray(MAXS)
    val length = IntArray(NT) { 16 }
    val scale = IntArray(NT) { 4 }
    val dir = IntArray(NT)
    val shuffle = FloatArray(NT)
    var master = 0                                    // 0 = off
    var bpm = 120
    var linkedKit = -1                                // bank*16 + slot, -1 = none

    fun copy(): Pattern {
        val p = Pattern()
        for (t in 0 until NT) {
            System.arraycopy(trig[t], 0, p.trig[t], 0, MAXS)
            System.arraycopy(ratchet[t], 0, p.ratchet[t], 0, MAXS)
            System.arraycopy(odds[t], 0, p.odds[t], 0, MAXS)
            System.arraycopy(utime[t], 0, p.utime[t], 0, MAXS)
            for (q in 0 until NLOCK) System.arraycopy(locks[t][q], 0, p.locks[t][q], 0, MAXS)
            p.length[t] = length[t]
            p.scale[t] = scale[t]
            p.dir[t] = dir[t]
            p.shuffle[t] = shuffle[t]
        }
        System.arraycopy(note, 0, p.note, 0, MAXS)
        System.arraycopy(gate, 0, p.gate, 0, MAXS)
        System.arraycopy(slide, 0, p.slide, 0, MAXS)
        p.master = master
        p.bpm = bpm
        p.linkedKit = linkedKit
        return p
    }

    fun clearTrack(t: Int) {
        for (s in 0 until MAXS) {
            trig[t][s] = 0
            ratchet[t][s] = 1
            odds[t][s] = 0
            utime[t][s] = 0f
        }
        if (t == CVT) for (s in 0 until MAXS) { note[s] = NOTE_DEFAULT; gate[s] = 1; slide[s] = false }
    }

    fun clearLocks(t: Int) {
        for (q in 0 until NLOCK) for (s in 0 until MAXS) locks[t][q][s] = Float.NaN
    }
}

/** Slots: 4 banks x 16 patterns and kits. Index = bank * 16 + slot. */
class Memory {
    val patterns = arrayOfNulls<Pattern>(64)
    val kits = arrayOfNulls<Kit>(64)
}

object S {
    const val CLK = 0
    const val MIDI_PORT = 1
    const val MIDI_OUT = 2
    const val MIDI_IN = 3
    const val MIDI_CLK = 4
    const val CLK_IN = 5
    const val CLK_OUT = 6
    const val ACCENT = 7
    const val LAST_LOAD = 8
    const val KIT_LINK = 9
    const val INV_ODDS = 10
    const val BAR_FOLLOW = 11
    const val BPM_LOAD = 12
    const val CV_MUTE = 13
    const val POT_CATCH = 14
    const val PLK = 15
    const val PITCH_Q = 16
    const val COUNT = 24
}

/** Configuration settings (SHIFT + step key 16). */
class Settings {
    val v = IntArray(S.COUNT)
    init { defaults() }
    fun defaults() {
        for (i in v.indices) v[i] = 0
        v[S.CLK_IN] = 3; v[S.CLK_OUT] = 3
        v[S.ACCENT] = 1          // option index 1 -> "2" (two-step response)
        v[S.LAST_LOAD] = 1
        v[S.KIT_LINK] = 1
        v[S.BAR_FOLLOW] = 1
        v[S.BPM_LOAD] = 1
        v[S.POT_CATCH] = 1
        v[S.PITCH_Q] = 1
    }
}

class CfgItem(val label: String, val key: Int, val opts: Array<String>)

private fun onOff() = arrayOf("OFF", "ON")
private fun nums() = (1..16).map { it.toString() }

val CFG: Array<CfgItem> = arrayOf(
    CfgItem("CLK", S.CLK, arrayOf("INT", "MID", "EXT")),
    CfgItem("MIP", S.MIDI_PORT, arrayOf("DIN", "USB")),
    CfgItem("MOT", S.MIDI_OUT, (listOf("OFF", "THR") + nums() + listOf("MLT")).toTypedArray()),
    CfgItem("MIN", S.MIDI_IN, (listOf("OFF", "OMN") + nums() + listOf("MLT")).toTypedArray()),
    CfgItem("MCO", S.MIDI_CLK, arrayOf("OFF", "ON", "CON")),
    CfgItem("CKI", S.CLK_IN, arrayOf("1", "2", "4", "24")),
    CfgItem("CKO", S.CLK_OUT, arrayOf("1", "2", "4", "24")),
    CfgItem("ACC", S.ACCENT, arrayOf("1", "2")),
    CfgItem("LPL", S.LAST_LOAD, onOff()),
    CfgItem("KLK", S.KIT_LINK, onOff()),
    CfgItem("INV", S.INV_ODDS, onOff()),
    CfgItem("BFL", S.BAR_FOLLOW, onOff()),
    CfgItem("BPL", S.BPM_LOAD, onOff()),
    CfgItem("CVM", S.CV_MUTE, arrayOf("STY", "FRZ", "DRP")),
    CfgItem("PCT", S.POT_CATCH, onOff()),
    CfgItem("PLK", S.PLK, arrayOf("OFF", "ALL")),
    CfgItem("PIQ", S.PITCH_Q, onOff()),
    CfgItem("CAL", -1, arrayOf("N-A")),
    CfgItem("USB", -1, arrayOf("IMP", "BAK", "RST")),
    CfgItem("DFU", -1, arrayOf("N-A")),
    CfgItem("RST", -1, arrayOf("HLD")),
    CfgItem("VER", -1, arrayOf("V10"))
)
const val CFG_USB = 18
const val CFG_RESET = 20
