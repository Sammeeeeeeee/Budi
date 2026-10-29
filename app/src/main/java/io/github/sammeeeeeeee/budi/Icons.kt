package io.github.sammeeeeeeee.budi

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable

/**
 * Samsung's own noise-control glyphs, as One UI's buds controls draw them, loaded from Samsung's
 * buds plugin on the phone. Budi's lookalikes stand in when they're missing.
 */
object Icons {
    private const val SAMSUNG = "com.samsung.accessory.budsunitemgr"

    /** In [NoiseMode] order. */
    private val NAMES = listOf("ic_buds3_anc_off", "ic_buds3_anc_on", "ic_buds3_ambient_sound", "ic_buds3_adaptive")

    /** The package holding the icons, and their ids in [NoiseMode] order. */
    @SuppressLint("DiscouragedApi") // the ids differ between plugin versions; only the names are stable
    fun resolve(context: Context): Pair<String, IntArray> {
        try {
            val res = context.packageManager.getResourcesForApplication(SAMSUNG)
            val ids = NAMES.map { res.getIdentifier(it, "drawable", SAMSUNG) }.toIntArray()
            if (ids.all { it != 0 && drawsWithoutTheme(res, it) }) return SAMSUNG to ids
        } catch (e: PackageManager.NameNotFoundException) {
            // plugin not installed
        }
        return context.packageName to NoiseMode.entries.map { it.icon }.toIntArray()
    }

    fun drawable(context: Context, mode: NoiseMode): Drawable? {
        val (pkg, ids) = resolve(context)
        return context.packageManager.getResourcesForApplication(pkg).getDrawable(ids[mode.ordinal], null)
    }

    /** SystemUI draws the icon with its own theme, so Samsung's must not depend on the plugin's. */
    private fun drawsWithoutTheme(res: Resources, id: Int): Boolean = try {
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ALPHA_8)
        res.getDrawable(id, null).run {
            setBounds(0, 0, 24, 24)
            draw(Canvas(bitmap))
        }
        val pixels = ByteArray(24 * 24).also { bitmap.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(it)) }
        bitmap.recycle()
        pixels.any { it != 0.toByte() }
    } catch (e: Exception) {
        false
    }
}
