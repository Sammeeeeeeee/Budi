package io.github.sammeeeeeeee.budi

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    /** What the follower reports: null when it isn't running, -1 before it has seen a mode. */
    private var followed: Int? = null
    private var ready = false
    /** Whether this visit has already started or stopped the follower to match the buds. */
    private var matched = false
    private val main = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, POLL_MS)
        }
    }
    private val onShizukuPermission = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Shizuku.addRequestPermissionResultListener(onShizukuPermission)
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(onShizukuPermission)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        matched = false
        ready = StatusBar.shizukuReady
        renderStatus()
        renderRows()
        main.post(poll)
    }

    override fun onPause() {
        main.removeCallbacks(poll)
        super.onPause()
    }

    /** Asks the follower what it sees, while Budi is on screen. */
    private fun refresh() {
        if (StatusBar.shizukuReady != ready) {
            ready = !ready
            renderRows()
        }
        if (!ready) return show(null)
        Follow.mode(this) { mode ->
            if (isDestroyed) return@mode
            show(mode)
            if (!matched) {
                matched = true
                match(running = mode != null)
            }
        }
    }

    private fun show(mode: Int?) {
        if (mode == followed) return
        followed = mode
        renderStatus()
    }

    /** Starts or stops the follower if Budi missed the buds connecting or disconnecting. */
    private fun match(running: Boolean) {
        if (Buds.samsungPlugins(this).isEmpty()) return
        when (Buds.connected(this)) {
            true -> if (!running) BudsService.connected(this)
            false -> if (running) BudsService.disconnected(this)
            null -> Unit
        }
    }

    private fun renderStatus() {
        val mode = followed?.let(NoiseMode::of)
        val plugin = Buds.samsungPlugins(this).isNotEmpty()

        val icon = findViewById<ImageView>(R.id.mode_icon)
        if (mode != null) icon.setImageDrawable(Icons.drawable(this, mode)) else icon.setImageResource(R.drawable.ic_noise_unknown)
        findViewById<TextView>(R.id.mode_label).setText(
            when {
                mode != null -> mode.label
                followed != null -> R.string.status_waiting
                !plugin -> R.string.status_no_plugin
                else -> R.string.status_idle
            }
        )
        findViewById<TextView>(R.id.mode_detail).setText(
            when {
                mode != null -> R.string.status_following
                followed != null -> R.string.status_waiting_detail
                !plugin -> R.string.status_no_plugin_detail
                else -> R.string.status_idle_detail
            }
        )
    }

    private fun renderRows() {
        if (granted(BLUETOOTH_CONNECT)) {
            row(R.id.row_bluetooth, R.string.row_bluetooth, R.string.allowed)
        } else {
            row(R.id.row_bluetooth, R.string.row_bluetooth, R.string.row_bluetooth_summary, R.string.allow) {
                requestPermissions(arrayOf(BLUETOOTH_CONNECT), 0)
            }
        }

        val shizukuApp = packageManager.getLaunchIntentForPackage(SHIZUKU)
        when {
            ready -> row(R.id.row_shizuku, R.string.row_shizuku, R.string.allowed)
            Shizuku.pingBinder() -> row(R.id.row_shizuku, R.string.row_shizuku, R.string.row_shizuku_permission, R.string.allow) {
                Shizuku.requestPermission(0)
            }
            shizukuApp != null -> row(R.id.row_shizuku, R.string.row_shizuku, R.string.row_shizuku_stopped, R.string.open) {
                startActivity(shizukuApp)
            }
            else -> row(R.id.row_shizuku, R.string.row_shizuku, R.string.row_shizuku_missing, R.string.get) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_DOWNLOAD)))
            }
        }

        if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
            row(R.id.row_battery, R.string.row_battery, R.string.unrestricted)
        } else {
            row(R.id.row_battery, R.string.row_battery, R.string.row_battery_summary, R.string.allow) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        }

        row(R.id.row_report, R.string.row_report, R.string.row_report_summary, R.string.copy) { copyReport() }
    }

    /** Copies what Budi knows and what the follower has seen lately, to share when something's off. */
    private fun copyReport() {
        val app = packageManager.getPackageInfo(packageName, 0)
        val plugins = Buds.samsungPlugins(this).joinToString { "$it ${packageManager.getPackageInfo(it, 0).versionName}" }
        val about = listOf(
            "Budi ${app.versionName} on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            "Plugins: ${plugins.ifEmpty { "none" }}",
            "Icons: ${Icons.resolve(this).first}",
            "Shizuku: ${if (ready) "v${Shizuku.getVersion()}" else "not ready"}",
            "Buds connected: ${Buds.connected(this) ?: "unknown"}",
            "Follower mode: ${followed ?: "not running"}",
        ).joinToString("\n")
        val copy = { trace: String? ->
            getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText(getString(R.string.row_report), "$about\n\n${trace ?: "No follower trace"}")
            )
        }
        if (ready) Follow.trace(this, copy) else copy(null)
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PERMISSION_GRANTED

    /** Fills a settings row. The button only shows when there's something to do. */
    private fun row(id: Int, title: Int, summary: Int, action: Int? = null, onClick: () -> Unit = {}) {
        val row = findViewById<View>(id)
        row.findViewById<TextView>(R.id.title).setText(title)
        row.findViewById<TextView>(R.id.summary).setText(summary)
        row.findViewById<Button>(R.id.action).run {
            visibility = if (action == null) View.GONE else View.VISIBLE
            if (action != null) setText(action)
            setOnClickListener { onClick() }
        }
    }

    private companion object {
        const val POLL_MS = 1_000L
        const val SHIZUKU = "moe.shizuku.privileged.api"
        const val SHIZUKU_DOWNLOAD = "https://shizuku.rikka.app/download/"
    }
}
