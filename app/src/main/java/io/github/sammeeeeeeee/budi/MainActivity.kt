package io.github.sammeeeeeeee.budi

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.Uri
import android.os.Bundle
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
    private val onShizuku = Shizuku.OnBinderReceivedListener { refresh() }
    private val onShizukuPermission = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Shizuku.addBinderReceivedListenerSticky(onShizuku)
        Shizuku.addRequestPermissionResultListener(onShizukuPermission)
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(onShizuku)
        Shizuku.removeRequestPermissionResultListener(onShizukuPermission)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** Asks the follower what it sees, and starts it if the buds were connected before Budi was set up. */
    private fun refresh() {
        render()
        if (!StatusBar.shizukuReady) return
        Follow.mode(this) { mode ->
            followed = mode
            if (!isDestroyed) render()
            if (mode == null) startIfConnected()
        }
    }

    private fun startIfConnected() {
        if (!granted(BLUETOOTH_CONNECT) || Buds.samsungPlugins(this).isEmpty()) return
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        adapter.getProfileProxy(applicationContext, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (proxy.connectedDevices.any(Buds::isBuds)) BudsService.connected(applicationContext)
                adapter.closeProfileProxy(profile, proxy)
            }

            override fun onServiceDisconnected(profile: Int) = Unit
        }, BluetoothProfile.A2DP)
    }

    private fun render() {
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

        if (granted(BLUETOOTH_CONNECT)) {
            row(R.id.row_bluetooth, R.string.row_bluetooth, R.string.allowed)
        } else {
            row(R.id.row_bluetooth, R.string.row_bluetooth, R.string.row_bluetooth_summary, R.string.allow) {
                requestPermissions(arrayOf(BLUETOOTH_CONNECT), 0)
            }
        }

        val shizukuApp = packageManager.getLaunchIntentForPackage(SHIZUKU)
        when {
            StatusBar.shizukuReady -> row(R.id.row_shizuku, R.string.row_shizuku, R.string.allowed)
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
        const val SHIZUKU = "moe.shizuku.privileged.api"
        const val SHIZUKU_DOWNLOAD = "https://shizuku.rikka.app/download/"
    }
}
