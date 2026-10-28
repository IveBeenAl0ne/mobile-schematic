package xyz.ibatv.schemviewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
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
     * Textured isometric render of layers 0..top. Official vanilla textures are taken
     * from the Mojang client archive; the palette colours are only a fallback.
     */
    suspend fun render(s: Schematic, px: Int, top: Int = s.sy - 1, hi: Int = -1): Bitmap {
        val topTextures = HashMap<Int, Bitmap>()
        val sideTextures = HashMap<Int, Bitmap>()
        for (id in 1 until s.palette.size) {
            currentCoroutineContext().ensureActive()
            val block = s.palette[id]
            if (block.isAir) continue
            MinecraftTextures.bitmap(block.name, "top")?.let { topTextures[id] = it }
            MinecraftTextures.bitmap(block.name, "side")?.let { sideTextures[id] = it }
        }

        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val k = px * 0.94f / maxOf((s.sx + s.sz).toFloat(), (s.sx + s.sz) / 2f + s.sy)
        val ox = px / 2f - (s.sx - s.sz) / 2f * k
        val oy = px / 2f - ((s.sx + s.sz) / 2f - s.sy) / 2f * k
        fun fx(x: Int, z: Int) = ox + (x - z) * k
        fun fy(x: Int, y: Int, z: Int) = oy + ((x + z) / 2f - y) * k

        val path = Path()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL_AND_STROKE; strokeWidth = 0.7f; isFilterBitmap = false }
        val src = Rect()
        fun quad(color: Int, texture: Bitmap?, shade: Float, a: Float, b: Float, c: Float, d: Float, e: Float, f: Float, g: Float, h: Float) {
            path.rewind()
            path.moveTo(a, b); path.lineTo(c, d); path.lineTo(e, f); path.lineTo(g, h); path.close()
            val left = minOf(a, c, e, g)
            val right = maxOf(a, c, e, g)
            val topY = minOf(b, d, f, h)
            val bottomY = maxOf(b, d, f, h)
            cv.save()
            cv.clipPath(path)
            if (texture != null) {
                src.set(0, 0, texture.width, texture.height)
                cv.drawBitmap(texture, src, RectF(left, topY, right, bottomY), paint)
                if (shade < 1f) {
                    paint.color = Color.argb(((1f - shade) * 255).toInt(), 0, 0, 0)
                    cv.drawRect(left, topY, right, bottomY, paint)
                    paint.color = Color.WHITE
                }
            } else {
                paint.color = if (shade == 1f) color else dark(color, shade)
                cv.drawRect(left, topY, right, bottomY, paint)
            }
            paint.color = Color.BLACK
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.7f
            cv.drawPath(path, paint)
            paint.style = Paint.Style.FILL_AND_STROKE
            cv.restore()
        }

        for (y in 0..top.coerceAtMost(s.sy - 1)) {
            currentCoroutineContext().ensureActive()
            val fade = hi >= 0 && y < hi
            for (x in 0 until s.sx) for (z in 0 until s.sz) {
                val id = s.at(x, y, z)
                if (id == 0) continue
                var color = s.colors[id]
                if (fade) color = mix(color, FADE, 0.7f)
                val topTex = topTextures[id]
                val sideTex = sideTextures[id]
                if (y == top || s.at(x, y + 1, z) == 0) quad(
                    color, topTex, 1f, fx(x, z), fy(x, y + 1, z), fx(x + 1, z), fy(x + 1, y + 1, z),
                    fx(x + 1, z + 1), fy(x + 1, y + 1, z + 1), fx(x, z + 1), fy(x, y + 1, z + 1),
                )
                if (x + 1 == s.sx || s.at(x + 1, y, z) == 0) quad(
                    color, sideTex, 0.60f, fx(x + 1, z), fy(x + 1, y, z), fx(x + 1, z + 1), fy(x + 1, y, z + 1),
                    fx(x + 1, z + 1), fy(x + 1, y + 1, z + 1), fx(x + 1, z), fy(x + 1, y + 1, z),
                )
                if (z + 1 == s.sz || s.at(x, y, z + 1) == 0) quad(
                    color, sideTex, 0.78f, fx(x, z + 1), fy(x, y, z + 1), fx(x + 1, z + 1), fy(x + 1, y, z + 1),
                    fx(x + 1, z + 1), fy(x + 1, y + 1, z + 1), fx(x, z + 1), fy(x, y + 1, z + 1),
                )
            }
        }
        return bmp
    }
}
