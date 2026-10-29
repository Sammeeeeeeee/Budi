package io.github.sammeeeeeeee.budi

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.POST_NOTIFICATIONS
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
import io.github.sammeeeeeeee.budi.BudsService.Link
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private val refresh = { render() }
    private val onShizuku = Shizuku.OnBinderReceivedListener { render() }
    private val onShizukuPermission = Shizuku.OnRequestPermissionResultListener { _, _ -> render() }

    private val active
        get() = BudsService.status.link.let { it == Link.CONNECTING || it == Link.CONNECTED }

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
        BudsService.listeners += refresh
        render()
        startIfConnected()
    }

    override fun onPause() {
        BudsService.listeners -= refresh
        super.onPause()
    }

    /** Covers buds that were already connected before Budi was set up. */
    private fun startIfConnected() {
        if (active || !granted(BLUETOOTH_CONNECT) || Buds.samsungManager(this) != null) return
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        adapter.getProfileProxy(applicationContext, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                proxy.connectedDevices.firstOrNull(Buds::isBuds)?.let { BudsService.start(applicationContext, it) }
                adapter.closeProfileProxy(profile, proxy)
            }

            override fun onServiceDisconnected(profile: Int) = Unit
        }, BluetoothProfile.A2DP)
    }

    private fun render() {
        val status = BudsService.status
        val mode = status.mode
        val manager = Buds.samsungManager(this)

        findViewById<ImageView>(R.id.mode_icon).setImageResource(mode?.icon ?: R.drawable.ic_noise_unknown)
        findViewById<TextView>(R.id.mode_label).setText(
            when {
                mode != null -> mode.label
                manager != null -> R.string.status_blocked
                status.link == Link.CONNECTING -> R.string.status_connecting
                status.link == Link.CONNECTED -> R.string.status_connected
                status.link == Link.FAILED -> R.string.status_failed
                else -> R.string.status_idle
            }
        )
        findViewById<TextView>(R.id.mode_detail).text = when {
            active -> status.device
            manager != null -> null
            status.link == Link.FAILED -> getText(R.string.status_failed_detail)
            else -> getText(R.string.status_idle_detail)
        }

        permissionRow(R.id.row_bluetooth, R.string.row_bluetooth, R.string.row_bluetooth_summary, BLUETOOTH_CONNECT)
        permissionRow(R.id.row_notifications, R.string.row_notifications, R.string.row_notifications_summary, POST_NOTIFICATIONS)

        if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
            row(R.id.row_battery, R.string.row_battery, R.string.unrestricted)
        } else {
            row(R.id.row_battery, R.string.row_battery, R.string.row_battery_summary, R.string.allow) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        }

        val shizukuApp = packageManager.getLaunchIntentForPackage(SHIZUKU)
        when {
            StatusIcon.ready -> {
                if (!active) StatusIcon.hide() // clears an icon left behind if Budi was killed
                row(R.id.row_shizuku, R.string.row_shizuku, R.string.row_shizuku_ready)
            }
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

        findViewById<View>(R.id.manager_card).visibility = if (manager == null) View.GONE else View.VISIBLE
        if (manager != null) {
            val label = packageManager.getApplicationLabel(packageManager.getApplicationInfo(manager, 0))
            row(R.id.row_manager, getString(R.string.row_manager, label), getText(R.string.row_manager_summary), R.string.open) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$manager")))
            }
        }
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PERMISSION_GRANTED

    private fun permissionRow(id: Int, title: Int, summary: Int, permission: String) {
        if (granted(permission)) {
            row(id, title, R.string.allowed)
        } else {
            row(id, title, summary, R.string.allow) { requestPermissions(arrayOf(permission), 0) }
        }
    }

    private fun row(id: Int, title: Int, summary: Int, action: Int? = null, onClick: () -> Unit = {}) =
        row(id, getText(title), getText(summary), action, onClick)

    /** Fills a settings row. The button only shows when there's something to do. */
    private fun row(id: Int, title: CharSequence, summary: CharSequence, action: Int? = null, onClick: () -> Unit = {}) {
        val row = findViewById<View>(id)
        row.findViewById<TextView>(R.id.title).text = title
        row.findViewById<TextView>(R.id.summary).text = summary
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
