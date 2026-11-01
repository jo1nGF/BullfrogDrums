package com.study.bullfrog

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Design coordinates = pixels of the reference photo (1792 px wide rendering of the front panel).
private const val DW = 1587f      // device width  (caps included)
private const val DH = 708f       // device height
private const val OX = 103f       // device origin in design space
private const val OY = 682f
private const val LVL_Y = 820f
private const val PAR_Y = 990f
private const val LED_Y = 883f
private const val FY = 1135f
private const val SY = 1257f
private const val TY = 1009f

private val KX = floatArrayOf(672f, 795f, 920f, 1044f, 1167f, 1292f, 1416f)
private val FX = floatArrayOf(
    245f, 309f, 373f, 437f, 500f, 564f, 649f, 713f, 799f, 863f,
    927f, 991f, 1055f, 1140f, 1204f, 1267f, 1352f, 1416f, 1480f, 1544f
)
private val TX = floatArrayOf(242f, 329f, 417f)
private fun sx(i: Int): Float = 243f + 87f * i

private const val ENC_X = 533f
private const val VOL_X = 1565f
private const val FROG_X = 355f
private const val FROG_Y = 825f

private fun k(h: Long): Int = h.toInt()
private fun mix(a: Int, b: Int, t: Float): Int {
    val u = 1f - t
    return Color.rgb(
        (Color.red(a) * u + Color.red(b) * t).toInt(),
        (Color.green(a) * u + Color.green(b) * t).toInt(),
        (Color.blue(a) * u + Color.blue(b) * t).toInt()
    )
}

private val TOP_LABELS = arrayOf(
    "SHIFT", "TRACK", "CLEAR", "LAST STEP", "SHUFFLE", "MUTE", "PATT", "KIT", "SAMPLE", "START",
    "END", "REVERSE", "LOOP", "ACCENT", "RATCHET", "ODDS/PROB", "BAR1", "BAR2", "BAR3", "BAR4"
)

// 7-segment glyphs: a=1 b=2 c=4 d=8 e=16 f=32 g=64
private fun glyph(ch: Char): Int = when (ch.uppercaseChar()) {
    '0', 'O' -> 63
    '1' -> 6
    '2', 'Z' -> 91
    '3' -> 79
    '4' -> 102
    '5', 'S' -> 109
    '6' -> 125
    '7' -> 7
    '8' -> 127
    '9' -> 111
    'A' -> 119
    'B' -> 124
    'C' -> 57
    'D' -> 94
    'E' -> 121
    'F' -> 113
    'G' -> 61
    'H', 'K' -> 118
    'I' -> 48
    'J' -> 30
    'L' -> 56
    'M' -> 55
    'N' -> 84
    'P' -> 115
    'Q' -> 103
    'R' -> 80
    'T' -> 120
    'U', 'V', 'W' -> 62
    'Y' -> 110
    '-' -> 64
    '_' -> 8
    '#' -> 99
    else -> 0
}

class PanelView(ctx: Context, private val ctl: Controller) : View(ctx) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()
    private var bmp: Bitmap? = null
    private var sc = 1f
    private var offX = 0f
    private var offY = 0f
    private var encAngle = 0f
    private var latch = false
    private val latched = HashSet<Int>()

    private class Ptr(val kind: Int, val id: Int, val t0: Long, var lx: Float, var ly: Float) {
        var moved = 0f
        var acc = 0f
    }

    private val ptrs = HashMap<Int, Ptr>()

    private companion object {
        const val K_NONE = 0
        const val K_BTN = 1
        const val K_STEP = 2
        const val K_PARAM = 3
        const val K_LEVEL = 4
        const val K_VOL = 5
        const val K_ENC = 6
        const val K_FROG = 7
    }

    // ------------------------------------------------------------------ drawing helpers

    private fun fill(color: Int) {
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = color
    }

    private fun rr(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, color: Int) {
        fill(color)
        rect.set(l, t, r, b)
        c.drawRoundRect(rect, rad, rad, paint)
    }

    private fun rrStroke(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, color: Int, w: Float) {
        paint.style = Paint.Style.STROKE
        paint.shader = null
        paint.strokeWidth = w
        paint.color = color
        rect.set(l, t, r, b)
        c.drawRoundRect(rect, rad, rad, paint)
        paint.style = Paint.Style.FILL
    }

    private fun grad(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, top: Int, bottom: Int) {
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, t, 0f, b, top, bottom, Shader.TileMode.CLAMP)
        rect.set(l, t, r, b)
        c.drawRoundRect(rect, rad, rad, paint)
        paint.shader = null
    }

    private fun circ(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        fill(color)
        c.drawCircle(x, y, r, paint)
    }

    private fun txt(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean) {
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = color
        paint.textSize = size
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
        c.drawText(s, x, y, paint)
    }

    // ------------------------------------------------------------------ static layer

    private fun drawCap(c: Canvas, l: Float, t: Float, r: Float, b: Float, seed: Long) {
        rr(c, l, t, r, b, 9f, k(0xFF161616))
        val rnd = Random(seed)
        for (n in 0 until 1900) {
            val x = l + rnd.nextFloat() * (r - l)
            val y = t + rnd.nextFloat() * (b - t)
            val g = 45 + rnd.nextInt(80)
            circ(c, x, y, 0.7f + rnd.nextFloat() * 1.3f, Color.argb(150, g, g, g))
        }
    }

    private fun drawDots(c: Canvas, cx: Float, cy: Float) {
        for (n in 0..10) {
            val a = (-135.0 + 27.0 * n) * PI / 180.0
            val x = cx + 51f * sin(a).toFloat()
            val y = cy - 51f * cos(a).toFloat()
            circ(c, x, y, if (n == 5) 3.1f else 2.4f, k(0xFF2B2B2B))
        }
    }

    private fun drawKnobBody(c: Canvas, cx: Float, cy: Float, capColor: Int) {
        circ(c, cx + 2f, cy + 5f, 47f, k(0x33000000))
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx, cy, 44f,
            intArrayOf(k(0xFFB4B4B4), k(0xFF9A9A9A), k(0xFF707070)),
            floatArrayOf(0.7f, 0.9f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, 43f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.3f
        paint.color = k(0x66404040)
        for (n in 0 until 54) {
            val a = n * 2.0 * PI / 54.0
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            c.drawLine(cx + 38f * ca, cy + 38f * sa, cx + 43f * ca, cy + 43f * sa, paint)
        }
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx - 10f, cy - 12f, 46f, mix(capColor, Color.WHITE, 0.22f), capColor, Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, 35f, paint)
        paint.shader = null
    }

    private fun drawFrog(c: Canvas) {
        val fc = k(0xFFC2C2C2)
        val face = k(0xFFD9D9D9)
        circ(c, 313f, 748f, 29f, fc)
        circ(c, 398f, 748f, 29f, fc)
        circ(c, 313f, 751f, 14f, face)
        circ(c, 398f, 751f, 14f, face)
        fill(fc)
        rect.set(266f, 758f, 444f, 905f)
        c.drawOval(rect, paint)
        circ(c, 280f, 860f, 17f, fc)
        circ(c, 432f, 860f, 17f, fc)
        rect.set(262f, 890f, 330f, 920f); c.drawOval(rect, paint)
        rect.set(380f, 890f, 448f, 920f); c.drawOval(rect, paint)
        for (n in 0 until 3) {
            circ(c, 262f + n * 9f, 914f, 6f, fc)
            circ(c, 448f - n * 9f, 914f, 6f, fc)
        }
        // speaker grille: eight rounded bars following an ellipse envelope + two side nubs
        for (n in 0 until 8) {
            val x = 303f + n * 16.2f
            val u = (x - 358f) / 64f
            var hh = 47f * sqrt(maxOf(0.05f, 1f - u * u))
            if (hh < 12f) hh = 12f
            rr(c, x - 4.6f, FROG_Y - hh, x + 4.6f, FROG_Y + hh, 4.6f, k(0xFF151515))
        }
        rr(c, 283f, 811f, 292f, 839f, 4.5f, k(0xFF151515))
        rr(c, 425f, 811f, 434f, 839f, 4.5f, k(0xFF151515))
    }

    private fun drawStatic(c: Canvas) {
        c.drawColor(k(0xFF1B1C1E))
        // device body
        rr(c, 120f, 712f, 1676f, 1404f, 26f, k(0x55000000))
        grad(c, 150f, 695f, 1642f, 1375f, 12f, k(0xFFE2E2E2), k(0xFFD2D2D2))
        rrStroke(c, 151f, 696f, 1641f, 1374f, 12f, k(0x55FFFFFF), 2f)
        drawCap(c, 103f, 682f, 176f, 1390f, 11L)
        drawCap(c, 1617f, 682f, 1690f, 1390f, 12L)
        // jacks and power switch on the top edge
        val jx = floatArrayOf(1155f, 1275f, 1365f)
        for (x in jx) {
            rr(c, x - 25f, 680f, x + 25f, 697f, 4f, k(0xFF8E8E8E))
            rr(c, x - 17f, 683f, x + 17f, 696f, 3f, k(0xFFCFCFCF))
        }
        rr(c, 1544f, 678f, 1596f, 696f, 3f, k(0xFF0E0E0E))
        circ(c, 1568f, 686f, 2.6f, k(0xFFE0E0E0))

        // sections
        rr(c, 609f, 748f, 1467f, 897f, 14f, k(0xFFC9C9C9))
        rr(c, 609f, 913f, 1467f, 1061f, 14f, k(0xFFC9C9C9))
        rr(c, 183f, 1080f, 1610f, 1316f, 14f, k(0xFFC4C4C4))
        rrStroke(c, 609f, 748f, 1467f, 897f, 14f, k(0x22000000), 1.5f)
        rrStroke(c, 609f, 913f, 1467f, 1061f, 14f, k(0x22000000), 1.5f)

        drawFrog(c)

        // display bezel
        rr(c, 478f, 800f, 592f, 852f, 6f, k(0xFF171414))
        rrStroke(c, 478f, 800f, 592f, 852f, 6f, k(0x44FFFFFF), 1.5f)

        // logo
        circ(c, 1548f, 806f, 72f, k(0xFFC8C8C8))
        circ(c, 1562f, 806f, 64f, k(0xFF1D1D1D))
        txt(c, "bull", 1560f, 806f, 46f, Color.WHITE, true)
        txt(c, "frog", 1560f, 848f, 48f, Color.WHITE, true)
        txt(c, "DRUMS", 1550f, 893f, 31f, k(0xFFBDBDBD), true)

        // knob labels
        val ink = k(0xFF2A2A2A)
        for (i in 0 until 7) {
            txt(c, "LEVEL" + (i + 1), KX[i], 768f, 13.5f, ink, false)
            txt(c, Prm.NAMES[i], KX[i], 936f, 13.5f, ink, false)
        }
        txt(c, "VOLUME", VOL_X, 936f, 13.5f, ink, false)
        txt(c, "DATA", ENC_X, 938f, 13.5f, ink, false)
        txt(c, "PLAY/PAUSE", TX[0], 955f, 13.5f, ink, false)
        txt(c, "STOP", TX[1], 955f, 13.5f, ink, false)
        txt(c, "REC", TX[2], 955f, 13.5f, ink, false)

        // knobs
        val black = k(0xFF1C1C1C)
        val caps = intArrayOf(k(0xFF3576A0), k(0xFFB93B3B), k(0xFFCFB346), k(0xFF317948), k(0xFF347B4A), k(0xFFB93B3B), k(0xFFB93B3B))
        for (i in 0 until 7) {
            drawDots(c, KX[i], LVL_Y)
            drawKnobBody(c, KX[i], LVL_Y, black)
            drawDots(c, KX[i], PAR_Y)
            drawKnobBody(c, KX[i], PAR_Y, caps[i])
            circ(c, KX[i], LED_Y, 12f, k(0xFF1A1214))
            rrStroke(c, KX[i] - 12f, LED_Y - 12f, KX[i] + 12f, LED_Y + 12f, 12f, k(0x66FFFFFF), 1.6f)
        }
        drawDots(c, VOL_X, PAR_Y)
        drawKnobBody(c, VOL_X, PAR_Y, black)
        drawKnobBody(c, ENC_X, PAR_Y, black)

        // function panel: label strip, labels, secondary pills and brackets
        rr(c, 207f, 1095f, 1585f, 1115f, 10f, k(0xFFDADADA))
        for (id in 0 until 20) txt(c, TOP_LABELS[id], FX[id], 1110f, 13f, ink, false)
        for (id in 0 until 20) {
            rr(c, FX[id] - 17f, FY - 17f, FX[id] + 17f, FY + 17f, 4f, k(0x33000000))
        }
        val br = k(0xFFE9E9E9)
        val pill = k(0xFFDEDEDE)
        val sec = arrayOf(
            floatArrayOf(309f, 309f, 52f), floatArrayOf(437f, 437f, 54f), floatArrayOf(500f, 500f, 54f),
            floatArrayOf(564f, 564f, 52f), floatArrayOf(681f, 681f, 112f), floatArrayOf(1140f, 1140f, 52f),
            floatArrayOf(1204f, 1204f, 52f), floatArrayOf(1448f, 1448f, 86f)
        )
        val names = arrayOf("TAP", "SCALE", "DIRECT", "SOLO", "RELOAD", "uTIME", "ROLL", "BANKS")
        val spans = arrayOf(
            floatArrayOf(287f, 331f), floatArrayOf(415f, 459f), floatArrayOf(478f, 522f), floatArrayOf(542f, 586f),
            floatArrayOf(627f, 735f), floatArrayOf(1118f, 1162f), floatArrayOf(1182f, 1226f), floatArrayOf(1330f, 1566f)
        )
        for (n in 0 until 8) {
            rrStroke(c, spans[n][0], 1124f, spans[n][1], 1168f, 8f, br, 1.8f)
            val w = sec[n][2]
            rr(c, sec[n][0] - w / 2f, 1156f, sec[n][0] + w / 2f, 1176f, 10f, pill)
            txt(c, names[n], sec[n][0], 1171f, 12f, ink, false)
        }

        // step key groups
        for (g in 0 until 4) {
            val l = sx(g * 4) - 44f
            val r = sx(g * 4 + 3) + 44f
            rrStroke(c, l, 1196f, r, 1304f, 10f, k(0xFFE9E9E9), 1.8f)
            rr(c, l + 1f, 1195f, r - 1f, 1212f, 8f, k(0xFFDADADA))
            for (n in 0 until 4) txt(c, "" + (g * 4 + n + 1), sx(g * 4 + n), 1208f, 12f, ink, false)
        }
    }

    // ------------------------------------------------------------------ dynamic layer

    private fun drawSeg(c: Canvas, ch: Int, cx: Float, cy: Float, on: Int, off: Int) {
        val w = 19f
        val h = 36f
        val t = 4.8f
        c.save()
        c.translate(cx, cy)
        c.skew(-0.17f, 0f)
        for (s in 0 until 7) {
            val lit = (ch and (1 shl s)) != 0
            path.reset()
            when (s) {
                0 -> hSeg(-w / 2f, w / 2f, -h / 2f, t)
                6 -> hSeg(-w / 2f, w / 2f, 0f, t)
                3 -> hSeg(-w / 2f, w / 2f, h / 2f, t)
                5 -> vSeg(-w / 2f, -h / 2f, 0f, t)
                1 -> vSeg(w / 2f, -h / 2f, 0f, t)
                4 -> vSeg(-w / 2f, 0f, h / 2f, t)
                else -> vSeg(w / 2f, 0f, h / 2f, t)
            }
            if (lit) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3.5f
                paint.shader = null
                paint.color = k(0x40FF3A1A)
                c.drawPath(path, paint)
            }
            fill(if (lit) on else off)
            c.drawPath(path, paint)
        }
        c.restore()
    }

    private fun hSeg(x0: Float, x1: Float, y: Float, t: Float) {
        path.moveTo(x0 + t * 0.6f, y)
        path.lineTo(x0 + t * 1.1f, y - t / 2f)
        path.lineTo(x1 - t * 1.1f, y - t / 2f)
        path.lineTo(x1 - t * 0.6f, y)
        path.lineTo(x1 - t * 1.1f, y + t / 2f)
        path.lineTo(x0 + t * 1.1f, y + t / 2f)
        path.close()
    }

    private fun vSeg(x: Float, y0: Float, y1: Float, t: Float) {
        path.moveTo(x, y0 + t * 0.6f)
        path.lineTo(x + t / 2f, y0 + t * 1.1f)
        path.lineTo(x + t / 2f, y1 - t * 1.1f)
        path.lineTo(x, y1 - t * 0.6f)
        path.lineTo(x - t / 2f, y1 - t * 1.1f)
        path.lineTo(x - t / 2f, y0 + t * 1.1f)
        path.close()
    }

    private fun drawDisplay(c: Canvas) {
        var s = ctl.display()
        if (s.length > 3) s = s.substring(0, 3)
        while (s.length < 3) s += " "
        val on = k(0xFFFF3B1F)
        val off = k(0xFF3A100C)
        for (n in 0 until 3) drawSeg(c, glyph(s[n]), 509f + n * 27f, 826f, on, off)
    }

    private fun knobIndicator(c: Canvas, cx: Float, cy: Float, v: Float) {
        val a = (-135.0 + 270.0 * v) * PI / 180.0
        val sa = sin(a).toFloat()
        val ca = cos(a).toFloat()
        paint.style = Paint.Style.STROKE
        paint.shader = null
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 3.8f
        paint.color = k(0xFFF2F2F2)
        c.drawLine(cx + 5f * sa, cy - 5f * ca, cx + 30f * sa, cy - 30f * ca, paint)
        paint.strokeCap = Paint.Cap.BUTT
        paint.style = Paint.Style.FILL
    }

    private fun catchMarker(c: Canvas, cx: Float, cy: Float, v: Float) {
        val a = (-135.0 + 270.0 * v) * PI / 180.0
        val x = cx + 61f * sin(a).toFloat()
        val y = cy - 61f * cos(a).toFloat()
        paint.style = Paint.Style.STROKE
        paint.shader = null
        paint.strokeWidth = 2.4f
        paint.color = k(0xFFFF6A3D)
        c.drawCircle(x, y, 5f, paint)
        paint.style = Paint.Style.FILL
    }

    private fun keyCap(c: Canvas, x: Float, y: Float, hw: Float, hh: Float, dark: Boolean, pressed: Boolean, led: Int) {
        val off = if (pressed) 2.5f else 0f
        val body = if (dark) k(0xFF8E8E8E) else k(0xFFD6D6D6)
        val hi = if (dark) k(0xFFA6A6A6) else k(0xFFEFEFEF)
        val lo = if (dark) k(0xFF747474) else k(0xFFBBBBBB)
        rr(c, x - hw - 2f, y - hh - 2f + off, x + hw + 2f, y + hh + 2f + off, 8f, k(0xFF111111))
        rr(c, x - hw, y - hh + off, x + hw, y + hh + off, 6f, if (pressed) mix(body, Color.BLACK, 0.12f) else body)
        rr(c, x - hw + 4f, y - hh + 3f + off, x + hw - 4f, y - hh + 11f + off, 4f, hi)
        rr(c, x - hw + 3f, y + hh - 9f + off, x + hw - 3f, y + hh - 2f + off, 4f, lo)
        rr(c, x - hw + 9f, y - hh + 14f + off, x + hw - 9f, y + hh - 12f + off, 5f, mix(body, hi, 0.45f))
        val sc0 = if (dark) k(0xFF5E5E5E) else k(0xFF8A8A8A)
        val col = when (led) {
            1 -> k(0xFFB5502C)
            2 -> k(0xFFFF6A3D)
            3 -> k(0xFFFFF0C2)
            4 -> k(0xFFB4B4B4)
            else -> sc0
        }
        val sx0 = x - 6f
        val sy0 = y - hh + 4f + off
        if (led >= 2) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 5f
            paint.shader = null
            paint.color = k(0x44FF6A3D)
            rect.set(sx0 - 1f, sy0 - 1f, sx0 + 13f, sy0 + 21f)
            c.drawRoundRect(rect, 3f, 3f, paint)
        }
        rr(c, sx0, sy0, sx0 + 12f, sy0 + 19f, 2.5f, col)
    }

    private fun drawDynamic(c: Canvas) {
        val pot = ctl.st.v[S.POT_CATCH] == 1
        // knob indicators + catch markers + level LEDs
        for (i in 0 until 7) {
            knobIndicator(c, KX[i], LVL_Y, ctl.physL[i])
            knobIndicator(c, KX[i], PAR_Y, ctl.physP[i])
            if (pot && !ctl.caughtL[i]) catchMarker(c, KX[i], LVL_Y, ctl.e.kit.level[i])
            if (pot && !ctl.caughtP[i]) catchMarker(c, KX[i], PAR_Y, ctl.storedParam(i))
            val lit = ctl.levelLed(i)
            if (lit > 0.04f) {
                val a = (60 + 195 * lit).toInt().coerceIn(0, 255)
                circ(c, KX[i], LED_Y, 15f, Color.argb(a / 4, 255, 40, 40))
                circ(c, KX[i], LED_Y, 10f, Color.argb(a, 255, 50, 45))
                circ(c, KX[i], LVL_Y + 38f, 8f, Color.argb(a / 3, 255, 60, 50))
            }
        }
        knobIndicator(c, VOL_X, PAR_Y, ctl.e.volume)
        val ea = encAngle.toDouble()
        circ(c, ENC_X + 24f * sin(ea).toFloat(), PAR_Y - 24f * cos(ea).toFloat(), 3.4f, k(0xFF5C5C5C))

        // function buttons
        val pad = k(0xFF707070)
        val padHi = k(0xFF838383)
        val ledc = k(0xFFFF6B6B)
        for (id in 0 until 20) {
            val x = FX[id]
            val lit = ctl.btnLed(id)
            val off = if (ctl.held[id]) 1.6f else 0f
            val base = if (lit > 0f) mix(pad, ledc, 0.25f + 0.75f * lit) else pad
            val inner = if (lit > 0f) mix(padHi, ledc, 0.25f + 0.75f * lit) else padHi
            if (lit > 0.4f) circ(c, x, FY, 24f, Color.argb(55, 255, 90, 90))
            rr(c, x - 15f, FY - 15f + off, x + 15f, FY + 15f + off, 3.5f, base)
            rr(c, x - 11f, FY - 11f + off, x + 11f, FY + 11f + off, 2.5f, inner)
        }

        // transport keys
        for (n in 0 until 3) {
            val id = 20 + n
            val lit = ctl.btnLed(id) > 0.5f
            keyCap(c, TX[n], TY, 34f, 34f, false, ctl.held[id], if (lit) 2 else 0)
        }

        // step keys
        for (i in 0 until 16) {
            val dark = (i / 4) % 2 == 0
            keyCap(c, sx(i), SY, 34f, 33f, dark, heldStepVisual(i), ctl.stepLed(i))
        }

        drawDisplay(c)

        // latch LED (tap the frog to toggle): single-finger mode for the hold-combinations
        circ(c, FROG_X, 891f, 6f, if (latch) k(0xFFFF6A3D) else k(0xFF8A8A8A))
    }

    private fun heldStepVisual(i: Int): Boolean = ctl.heldStep[i]

    // ------------------------------------------------------------------ view plumbing

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w <= 0 || h <= 0) return
        sc = min(w / (DW + 36f), h / (DH + 36f))
        offX = (w - DW * sc) / 2f
        offY = (h - DH * sc) / 2f
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.translate(offX, offY)
        c.scale(sc, sc)
        c.translate(-OX, -OY)
        drawStatic(c)
        bmp = b
    }

    override fun onDraw(canvas: Canvas) {
        ctl.tick()
        val b = bmp
        if (b != null) canvas.drawBitmap(b, 0f, 0f, null)
        canvas.save()
        canvas.translate(offX, offY)
        canvas.scale(sc, sc)
        canvas.translate(-OX, -OY)
        drawDynamic(canvas)
        canvas.restore()
        postInvalidateDelayed(33L)
    }

    // ------------------------------------------------------------------ touch

    private fun hit(dx: Float, dy: Float): Int {
        for (i in 0 until 16) if (abs(dx - sx(i)) <= 42f && abs(dy - SY) <= 40f) return K_STEP * 100 + i
        for (n in 0 until 3) if (abs(dx - TX[n]) <= 39f && abs(dy - TY) <= 39f) return K_BTN * 100 + 20 + n
        for (id in 0 until 20) if (abs(dx - FX[id]) <= 31f && abs(dy - FY) <= 31f) return K_BTN * 100 + id
        for (i in 0 until 7) {
            if (hypot(dx - KX[i], dy - LVL_Y) <= 50f) return K_LEVEL * 100 + i
            if (hypot(dx - KX[i], dy - PAR_Y) <= 50f) return K_PARAM * 100 + i
        }
        if (hypot(dx - VOL_X, dy - PAR_Y) <= 50f) return K_VOL * 100
        if (hypot(dx - ENC_X, dy - PAR_Y) <= 46f) return K_ENC * 100
        if (hypot(dx - FROG_X, dy - 825f) <= 90f) return K_FROG * 100
        return -1
    }

    private fun sendDown(kind: Int, id: Int) {
        if (kind == K_STEP) ctl.stepDown(id) else ctl.buttonDown(id)
    }

    private fun sendUp(kind: Int, id: Int) {
        if (kind == K_STEP) ctl.stepUp(id) else ctl.buttonUp(id)
    }

    private fun releaseLatched() {
        for (key in latched) sendUp(key / 100, key % 100)
        latched.clear()
    }

    private fun down(pid: Int, x: Float, y: Float) {
        val dx = (x - offX) / sc + OX
        val dy = (y - offY) / sc + OY
        val h = hit(dx, dy)
        if (h < 0) return
        val kind = h / 100
        val id = h % 100
        val now = System.currentTimeMillis()
        if (kind == K_BTN || kind == K_STEP) {
            val key = kind * 100 + id
            if (latch && latched.contains(key)) {
                latched.remove(key)
                sendUp(kind, id)
                ptrs[pid] = Ptr(K_NONE, 0, now, x, y)
                return
            }
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            sendDown(kind, id)
        }
        ptrs[pid] = Ptr(kind, id, now, x, y)
    }

    private fun move(pid: Int, x: Float, y: Float) {
        val p = ptrs[pid] ?: return
        val ddx = (x - p.lx) / sc
        val ddy = (y - p.ly) / sc
        p.lx = x
        p.ly = y
        p.moved += abs(ddx) + abs(ddy)
        when (p.kind) {
            K_PARAM -> ctl.paramMove(p.id, (ctl.physP[p.id] - ddy / 170f).coerceIn(0f, 1f))
            K_LEVEL -> ctl.levelMove(p.id, (ctl.physL[p.id] - ddy / 170f).coerceIn(0f, 1f))
            K_VOL -> ctl.volumeMove((ctl.e.volume - ddy / 170f).coerceIn(0f, 1f))
            K_ENC -> {
                p.acc += -ddy + ddx
                while (p.acc >= 16f) { p.acc -= 16f; ctl.encoderTurn(1); encAngle += 0.25f }
                while (p.acc <= -16f) { p.acc += 16f; ctl.encoderTurn(-1); encAngle -= 0.25f }
            }
        }
    }

    private fun up(pid: Int) {
        val p = ptrs.remove(pid) ?: return
        val now = System.currentTimeMillis()
        val dur = now - p.t0
        when (p.kind) {
            K_BTN, K_STEP -> {
                val key = p.kind * 100 + p.id
                val latchable = !(p.kind == K_BTN && (p.id == B.PLAY || p.id == B.STOP))
                if (latch && latchable && dur > 350L && p.moved < 30f) latched.add(key)
                else sendUp(p.kind, p.id)
            }
            K_ENC -> if (p.moved < 14f) ctl.encoderLong(dur)
            K_FROG -> if (p.moved < 20f && dur < 500L) {
                latch = !latch
                if (!latch) releaseLatched()
            }
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                down(ev.getPointerId(i), ev.getX(i), ev.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until ev.pointerCount) move(ev.getPointerId(i), ev.getX(i), ev.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                up(ev.getPointerId(ev.actionIndex))
            }
            MotionEvent.ACTION_CANCEL -> {
                val ids = ArrayList<Int>(ptrs.keys)
                for (id in ids) up(id)
            }
        }
        return true
    }
}
