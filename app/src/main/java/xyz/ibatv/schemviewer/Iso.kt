package xyz.ibatv.schemviewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object Iso {
    private val FADE = 0xFF9AA0A6.toInt()
    private fun dark(c: Int, f: Float) = Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())
    private fun mix(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
        (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
        (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt(),
    )

    /**
     * Isometric render of layers 0..[top]. The frame always fits the whole build, so it doesn't jump between layers.
     * If [hi] >= 0, layers below it are faded so that layer stands out.
     * Viewer looks from the +x/+z corner: top, +z (left) and +x (right) faces are visible.
     */
    suspend fun render(s: Schematic, px: Int, top: Int = s.sy - 1, hi: Int = -1): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val k = px * 0.94f / maxOf((s.sx + s.sz).toFloat(), (s.sx + s.sz) / 2f + s.sy)
        val ox = px / 2f - (s.sx - s.sz) / 2f * k
        val oy = px / 2f - ((s.sx + s.sz) / 2f - s.sy) / 2f * k
        fun fx(x: Int, z: Int) = ox + (x - z) * k
        fun fy(x: Int, y: Int, z: Int) = oy + ((x + z) / 2f - y) * k

        val path = Path()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL_AND_STROKE; strokeWidth = 0.7f }
        fun quad(c: Int, a: Float, b: Float, c2: Float, d: Float, e: Float, f: Float, g: Float, h: Float) {
            path.rewind(); path.moveTo(a, b); path.lineTo(c2, d); path.lineTo(e, f); path.lineTo(g, h); path.close()
            paint.color = c
            cv.drawPath(path, paint)
        }

        for (y in 0..top) {
            currentCoroutineContext().ensureActive()
            val fade = hi >= 0 && y < hi
            for (x in 0 until s.sx) for (z in 0 until s.sz) {
                val id = s.at(x, y, z)
                if (id == 0) continue
                val c = if (fade) mix(s.colors[id], FADE, 0.7f) else s.colors[id]
                if (y == top || s.at(x, y + 1, z) == 0) quad(
                    c, fx(x, z), fy(x, y + 1, z), fx(x + 1, z), fy(x + 1, y + 1, z),
                    fx(x + 1, z + 1), fy(x + 1, y + 1, z + 1), fx(x, z + 1), fy(x, y + 1, z + 1),
                )
                if (x + 1 == s.sx || s.at(x + 1, y, z) == 0) quad(
                    dark(c, 0.6f), fx(x + 1, z), fy(x + 1, y, z), fx(x + 1, z + 1), fy(x + 1, y, z + 1),
                    fx(x + 1, z + 1), fy(x + 1, y + 1, z + 1), fx(x + 1, z), fy(x + 1, y + 1, z),
                )
                if (z + 1 == s.sz || s.at(x, y, z + 1) == 0) quad(
                    dark(c, 0.8f), fx(x, z + 1), fy(x, y, z + 1), fx(x + 1, z + 1), fy(x + 1, y, z + 1),
                    fx(x + 1, z + 1), fy(x + 1, y + 1, z + 1), fx(x, z + 1), fy(x, y + 1, z + 1),
                )
            }
        }
        return bmp
    }
}
