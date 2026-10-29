package io.github.sammeeeeeeee.budi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.ImageView
import rikka.shizuku.Shizuku

/**
 * Budi's icon in the status bar. One UI 7 and later show only Wi‑Fi, mobile data and battery on the
 * right of the closed status bar, so Budi draws its own: a window the size of a status bar icon,
 * just right of the camera, under the status bar, which shows it through. It hides with the status
 * bar. The [Follower] tells it the mode, and whether the app on screen asked for dark status bar
 * icons. Main thread only.
 */
@SuppressLint("StaticFieldLeak") // the application context and the icon's own window, let go in hide()
object StatusIcon {
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var windows: WindowManager? = null
    private var view: ImageView? = null
    private var mode: NoiseMode? = null
    private var dark = false
    private var light = Color.WHITE
    private var darkTint = Color.argb(0x99, 0, 0, 0)

    private val follower = object : IStatusIcon.Stub() {
        override fun show(mode: Int, dark: Boolean) {
            main.post {
                if (app == null) return@post // hidden since
                StatusIcon.mode = NoiseMode.of(mode) ?: return@post
                StatusIcon.dark = dark
                draw()
            }
        }
    }

    private val onShizuku = object : Shizuku.OnBinderReceivedListener {
        override fun onBinderReceived() {
            Shizuku.removeBinderReceivedListener(this)
            app?.let { Follow.watch(it, follower) }
        }
    }

    /** Shows [mode], and has the follower keep it up to date. */
    fun show(context: Context, mode: NoiseMode) {
        val app = context.applicationContext.also { this.app = it }
        this.mode = mode
        if (!draw()) return
        if (Follow.ready) Follow.watch(app, follower) else Shizuku.addBinderReceivedListener(onShizuku, main)
    }

    fun hide() {
        view?.let { runCatching { windows?.removeView(it) } }
        view = null
        windows = null
        mode = null
        app = null
    }

    private fun draw(): Boolean {
        val app = app ?: return false
        val mode = mode ?: return false
        val view = view ?: attach(app) ?: return false
        view.setImageResource(mode.icon)
        view.contentDescription = app.getString(mode.label)
        view.imageTintList = ColorStateList.valueOf(if (dark) darkTint else light)
        return true
    }

    private fun attach(app: Context): ImageView? {
        if (!Settings.canDrawOverlays(app)) return null
        val display = app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val window = app.createWindowContext(display, LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        val windows = window.getSystemService(WindowManager::class.java)
        readSystemUi(app)
        val size = systemIconSize(window)
        val params = LayoutParams(
            size, size, LayoutParams.TYPE_APPLICATION_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCHABLE or
                LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            title = "Budi"
            place(windows, size)
        }
        val view = ImageView(window).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnApplyWindowInsetsListener { v, insets ->
                v.visibility = if (insets.isVisible(WindowInsets.Type.statusBars())) View.VISIBLE else View.INVISIBLE
                insets
            }
        }
        try {
            windows.addView(view, params)
        } catch (e: RuntimeException) {
            return null
        }
        window.registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                if (this@StatusIcon.view === view) runCatching { windows.updateViewLayout(view, params.apply { place(windows, size) }) }
            }

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = Unit
        })
        this.windows = windows
        this.view = view
        return view
    }

    /** Just right of the camera, level with the status bar's icons. */
    private fun LayoutParams.place(windows: WindowManager, size: Int) {
        val metrics = windows.currentWindowMetrics
        val insets = metrics.windowInsets
        val bar = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
        val camera = insets.displayCutout?.boundingRectTop?.takeUnless { it.isEmpty }
        x = (camera?.right ?: (metrics.bounds.width() / 2)) + size / 2
        y = if (bar > 0) (bar + paddingTop - size) / 2 else (camera?.centerY() ?: size) - size / 2
    }

    private var paddingTop = 0

    /** SystemUI's own icon colours and top padding, so Budi's icon matches. */
    private fun readSystemUi(app: Context) {
        val res = try {
            app.packageManager.getResourcesForApplication(SYSTEM_UI)
        } catch (e: PackageManager.NameNotFoundException) {
            return
        }
        @Suppress("DiscouragedApi") // SystemUI's resources are only reachable by name
        fun id(name: String, type: String) = res.getIdentifier(name, type, SYSTEM_UI)
        id("light_mode_icon_color_single_tone", "color").takeIf { it != 0 }?.let { light = res.getColor(it, null) }
        id("dark_mode_icon_color_single_tone", "color").takeIf { it != 0 }?.let { darkTint = res.getColor(it, null) }
        id("status_bar_padding_top", "dimen").takeIf { it != 0 }?.let { paddingTop = res.getDimensionPixelSize(it) }
    }

    /** The height Android draws status bar icons at. */
    @Suppress("DiscouragedApi")
    private fun systemIconSize(context: Context): Int {
        val id = Resources.getSystem().getIdentifier("status_bar_system_icon_size", "dimen", "android")
        return if (id != 0) Resources.getSystem().getDimensionPixelSize(id)
        else TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 15f, context.resources.displayMetrics).toInt()
    }

    private const val SYSTEM_UI = "com.android.systemui"
}

/** Wakes Budi with the mode when its icon isn't up. Only the shell, which the follower runs as, may send. */
class ModeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val mode = NoiseMode.of(intent.getIntExtra(EXTRA_MODE, -1)) ?: return
        // A word from a follower that's on its way out mustn't bring the icon back.
        if (Buds.connected(context) == false) StatusIcon.hide() else StatusIcon.show(context, mode)
    }

    companion object {
        const val EXTRA_MODE = "mode"
    }
}
