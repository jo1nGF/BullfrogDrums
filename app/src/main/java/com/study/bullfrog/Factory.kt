package com.study.bullfrog

/**
 * Factory content: 8 patterns + 8 kits in bank 1 (slots 1-8). The patterns are the genre
 * recipes from the "creative manual" (hip-hop, house, techno, rock, punk, drum & bass,
 * electro, reggaeton). Slots 9-16 stay empty, as in the quickstart.
 *
 * Track layout used by the factory kits:
 *  1 kick (A)  2 snare (B)  3 clap (D)  4 closed hat (F)  5 open hat (F)  6 ride/crash (G)  7 perc/ghost (C/E)
 */
object Factory {
    private fun code(folder: Int, idx: Int) = folder * MAXSAMP + idx

    private fun on(p: Pattern, t: Int, vararg steps: Int) { for (s in steps) p.trig[t][s - 1] = 1 }
    private fun acc(p: Pattern, t: Int, vararg steps: Int) { for (s in steps) p.trig[t][s - 1] = 2 }
    private fun bass(p: Pattern, steps: IntArray, notes: IntArray, gate: Int) {
        for (k in steps.indices) {
            p.trig[CVT][steps[k] - 1] = 1
            p.note[steps[k] - 1] = notes[k]
            p.gate[steps[k] - 1] = gate
        }
    }

    fun kit(i: Int): Kit {
        val k = Kit()
        k.sample[0] = code(0, (i * 4 + 1) % 32)
        k.sample[1] = code(1, (i * 5 + 2) % 32)
        k.sample[2] = code(3, (i * 3 + 1) % 16)
        k.sample[3] = code(5, ((i * 3) % 16) * 2)
        k.sample[4] = code(5, ((i * 3) % 16) * 2 + 1)
        k.sample[5] = code(6, (i * 3 + 1) % 32)
        k.sample[6] = code(2, 16 + (i % 16))
        val lv = floatArrayOf(0.75f, 0.62f, 0.6f, 0.45f, 0.47f, 0.4f, 0.5f)
        for (v in 0 until NV) k.level[v] = lv[v]
        return k
    }

    private fun hiphop(): Pattern {
        val p = Pattern(); p.bpm = 90
        acc(p, 0, 1, 7, 11, 16); acc(p, 1, 5, 13)
        acc(p, 3, 1, 5, 9, 13); on(p, 3, 3, 7, 10, 11, 15)
        for (t in 0..3) p.shuffle[t] = 0.25f
        return p
    }

    private fun house(): Pattern {
        val p = Pattern(); p.bpm = 124
        acc(p, 0, 1, 5, 9, 13); acc(p, 2, 5, 13); acc(p, 4, 3, 7, 11, 15)
        for (s in 1..16 step 2) if (p.trig[3][s - 1] == 0 && s % 4 != 3) on(p, 3, s)
        p.shuffle[4] = 0.15f; p.shuffle[2] = 0.15f
        bass(p, intArrayOf(1, 4, 7, 10, 13, 16), intArrayOf(24, 24, 27, 24, 31, 29), 4)
        return p
    }

    private fun techno(): Pattern {
        val p = Pattern(); p.bpm = 135
        acc(p, 0, 1, 5, 9, 13); acc(p, 1, 5, 13); acc(p, 2, 5, 13)
        for (s in 1..16) on(p, 3, s)
        for (s in 1..15 step 2) on(p, 5, s)
        acc(p, 5, 3, 7, 11, 15)
        return p
    }

    private fun rock(): Pattern {
        val p = Pattern(); p.bpm = 120
        acc(p, 0, 1, 9, 13); acc(p, 1, 5, 13)
        for (s in intArrayOf(1, 3, 5, 7, 9, 11, 13)) on(p, 3, s)
        acc(p, 4, 15)
        return p
    }

    private fun punk(): Pattern {
        val p = Pattern(); p.bpm = 170
        acc(p, 0, 1, 5, 9, 12, 16); acc(p, 1, 3, 7, 11, 15)
        for (s in 1..15 step 2) acc(p, 3, s)
        return p
    }

    private fun dnb(): Pattern {
        val p = Pattern(); p.bpm = 170
        acc(p, 0, 1, 11); acc(p, 1, 5, 13); on(p, 6, 8, 10, 16)
        for (s in 1..15 step 2) on(p, 3, s)
        for (t in 0..6) p.shuffle[t] = 0.1f
        return p
    }

    private fun electro(): Pattern {
        val p = Pattern(); p.bpm = 120
        acc(p, 0, 1, 2, 7, 10, 11); acc(p, 1, 5, 13)
        for (s in 1..16) on(p, 3, s)
        bass(p, intArrayOf(1, 3, 6, 9, 12, 15), intArrayOf(24, 24, 36, 27, 24, 31), 3)
        return p
    }

    private fun reggaeton(): Pattern {
        val p = Pattern(); p.bpm = 95
        acc(p, 0, 1, 5, 9, 13); acc(p, 1, 4, 7, 12, 15); on(p, 3, 1, 5, 9, 13)
        on(p, 6, 1, 9)
        return p
    }

    fun build(mem: Memory) {
        for (i in 0 until 8) mem.kits[i] = kit(i)
        mem.kits[7]!!.sample[6] = code(4, 4)      // reggaeton: "signature" sound from bank E
        mem.kits[5]!!.sample[6] = mem.kits[5]!!.sample[1]   // DnB: ghost snare = snare sample
        mem.kits[5]!!.level[6] = 0.3f
        mem.kits[5]!!.knob[6][Prm.DECAY] = 0.3f
        val list = arrayOf(hiphop(), house(), techno(), rock(), punk(), dnb(), electro(), reggaeton())
        for (i in 0 until 8) {
            list[i].linkedKit = i
            mem.patterns[i] = list[i]
        }
    }
}
