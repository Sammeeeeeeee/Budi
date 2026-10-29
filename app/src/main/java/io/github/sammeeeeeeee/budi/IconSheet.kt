package io.github.sammeeeeeeee.budi

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextPaint
import android.text.TextUtils

/**
 * Renders a package's drawables whose names suggest noise control into labelled contact sheets,
 * to pick out Samsung's own One UI icons so Budi can load them from the phone by name.
 */
object IconSheet {
    private const val COLUMNS = 6
    private const val ROWS = 8
    private const val CELL_W = 180
    private const val CELL_H = 170
    private const val ICON = 96
    private const val MAX = COLUMNS * ROWS * 10

    private val WORDS = setOf("anc", "nc", "bud", "buds", "noise", "ambient", "adaptive", "hearing", "transparency")
    private val PREFIXES = listOf("noise", "ambient", "adaptive", "cancel", "transparen", "anc")

    /** Pages of up to 48 icons each, rendered one at a time; none if the package isn't installed. */
    fun render(context: Context, pkg: String): Sequence<Bitmap> {
        val theirs = try {
            context.createPackageContext(pkg, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return emptySequence()
        }
        if (theirs.applicationInfo.theme != 0) theirs.setTheme(theirs.applicationInfo.theme)
        val all = drawables(theirs.resources)
        // Release builds can obfuscate resource names; then fall back to everything.
        val picked = all.filter { (_, name) -> matches(name) }.ifEmpty { all }.take(MAX)
        return picked.chunked(COLUMNS * ROWS).asSequence().map { page -> page(theirs, pkg, page) }
    }

    private fun matches(name: String) = name.lowercase().split('_').any { token ->
        token in WORDS || PREFIXES.any { token.startsWith(it) }
    }

    /** Walks resource IDs (0x7fTTEEEE) rather than parsing the APK. */
    private fun drawables(res: Resources): List<Pair<Int, String>> {
        val type = (1..0xFF).firstOrNull { t ->
            try {
                res.getResourceTypeName(0x7f000000 or (t shl 16)) == "drawable"
            } catch (e: Resources.NotFoundException) {
                false
            }
        } ?: return emptyList()
        val found = mutableListOf<Pair<Int, String>>()
        var misses = 0
        var entry = 0
        while (misses < 64 && entry <= 0xFFFF) {
            val id = 0x7f000000 or (type shl 16) or entry++
            try {
                found += id to res.getResourceEntryName(id)
                misses = 0
            } catch (e: Resources.NotFoundException) {
                misses++
            }
        }
        return found
    }

    private fun page(theirs: Context, pkg: String, icons: List<Pair<Int, String>>): Bitmap {
        val bitmap = Bitmap.createBitmap(COLUMNS * CELL_W, ROWS * CELL_H + 48, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawText(pkg, 12f, 32f, TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 26f })
        val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f }
        val tile = Paint().apply { color = Color.rgb(0x9E, 0x9E, 0x9E) }
        icons.forEachIndexed { i, (id, name) ->
            val x = (i % COLUMNS) * CELL_W
            val y = (i / COLUMNS) * CELL_H + 48
            val left = x + (CELL_W - ICON) / 2
            canvas.drawRect(left.toFloat(), y + 8f, left + ICON.toFloat(), y + 8f + ICON, tile)
            try {
                theirs.getDrawable(id)?.run {
                    setBounds(left, y + 8, left + ICON, y + 8 + ICON)
                    draw(canvas)
                }
            } catch (e: Exception) {
                canvas.drawText("✕", left + ICON / 2f - 6, y + 8f + ICON / 2f, label)
            }
            lines(name, label).forEachIndexed { line, text ->
                canvas.drawText(text, 0, text.length, x + 4f, y + ICON + 32f + line * 20, label)
            }
        }
        return bitmap
    }

    /** Splits a long resource name at an underscore so it fits the cell in two lines. */
    private fun lines(name: String, paint: TextPaint): List<CharSequence> {
        val width = CELL_W - 8f
        if (paint.measureText(name) <= width) return listOf(name)
        val cut = name.indices.lastOrNull { name[it] == '_' && paint.measureText(name, 0, it + 1) <= width }
            ?: return listOf(TextUtils.ellipsize(name, paint, width, TextUtils.TruncateAt.MIDDLE))
        return listOf(name.substring(0, cut + 1), TextUtils.ellipsize(name.substring(cut + 1), paint, width, TextUtils.TruncateAt.MIDDLE))
    }
}
