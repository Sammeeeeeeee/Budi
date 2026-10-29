package io.github.sammeeeeeeee.budi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.IOException

/**
 * Holds one RFCOMM link to the buds while they're connected. Idle cost is a thread blocked on
 * read(); the buds push a frame only when the noise mode (or other state) changes.
 */
class BudsService : Service() {

    enum class Link { IDLE, CONNECTING, CONNECTED, BLOCKED, FAILED }

    data class Status(
        val link: Link = Link.IDLE,
        val device: String? = null,
        val mode: NoiseMode? = null,
        val systemIcon: Boolean = false,
    )

    companion object {
        private const val TAG = "Budi"
        private const val EXTRA_DEVICE = "device"
        private const val NOTIFICATION_ID = 1
        const val CHANNEL_STATUS = "status"
        const val CHANNEL_SERVICE = "service"
        private val RETRY_DELAYS = longArrayOf(2_000, 5_000, 10_000, 20_000, 40_000)

        var status = Status()
            private set

        /** Main thread only. */
        val listeners = mutableSetOf<() -> Unit>()

        fun start(context: Context, device: BluetoothDevice) {
            try {
                context.startForegroundService(
                    Intent(context, BudsService::class.java).putExtra(EXTRA_DEVICE, device)
                )
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Not allowed to start", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BudsService::class.java))
        }

        private fun publish(new: Status) {
            status = new
            listeners.forEach { it() }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val retry = Runnable { connect() }
    private val onShizuku = Shizuku.OnBinderReceivedListener { render() }
    private val onShizukuPermission = Shizuku.OnRequestPermissionResultListener { _, _ -> render() }
    private lateinit var notifications: NotificationManager
    private var device: BluetoothDevice? = null
    private var session: Session? = null
    private var attempt = 0
    private var destroyed = false

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_STATUS, getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW)
                    .apply { description = getString(R.string.channel_status_description) },
                NotificationChannel(CHANNEL_SERVICE, getString(R.string.channel_service), NotificationManager.IMPORTANCE_LOW)
                    .apply { description = getString(R.string.channel_service_description) },
            )
        )
        Shizuku.addBinderReceivedListenerSticky(onShizuku, main)
        Shizuku.addRequestPermissionResultListener(onShizukuPermission, main)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val target = intent?.getParcelableExtra(EXTRA_DEVICE, BluetoothDevice::class.java)
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Can't start in the foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (target == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (target != device) {
            device = target
            attempt = 0
            connect()
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacks(retry)
        session?.close()
        session = null
        Shizuku.removeBinderReceivedListener(onShizuku)
        Shizuku.removeRequestPermissionResultListener(onShizukuPermission)
        if (status.systemIcon) StatusIcon.hide()
        val link = if (status.link == Link.BLOCKED || status.link == Link.FAILED) status.link else Link.IDLE
        publish(status.copy(link = link, mode = null, systemIcon = false))
        super.onDestroy()
    }

    private fun connect() {
        main.removeCallbacks(retry)
        session?.close()
        val device = device ?: return
        val name = try {
            device.name
        } catch (e: SecurityException) {
            null
        }
        if (Buds.samsungManager(this) != null) {
            publish(Status(Link.BLOCKED, name))
            stopSelf()
            return
        }
        publish(status.copy(link = Link.CONNECTING, device = name))
        session = Session(device).also { it.start() }
    }

    private fun onConnected() {
        attempt = 0
        publish(status.copy(link = Link.CONNECTED))
    }

    private fun onMode(mode: NoiseMode) {
        if (mode == status.mode) return
        publish(status.copy(mode = mode))
        render()
    }

    private fun onLost() {
        session = null
        publish(status.copy(mode = null))
        render()
        val delay = RETRY_DELAYS.getOrNull(attempt++)
        if (delay == null) {
            publish(status.copy(link = Link.FAILED))
            stopSelf()
        } else {
            publish(status.copy(link = Link.CONNECTING))
            main.postDelayed(retry, delay)
        }
    }

    /** Puts the mode in the status bar via Shizuku, falling back to the notification icon. */
    private fun render() {
        if (destroyed) return
        val mode = status.mode
        val shown = mode != null && StatusIcon.show(this, mode)
        if (!shown && status.systemIcon) StatusIcon.hide()
        if (shown != status.systemIcon) publish(status.copy(systemIcon = shown))
        notifications.notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val mode = status.mode
        return Notification.Builder(this, if (status.systemIcon) CHANNEL_SERVICE else CHANNEL_STATUS)
            .setSmallIcon(mode?.icon ?: R.drawable.ic_noise_unknown)
            .setContentTitle(getString(mode?.label ?: R.string.status_connecting))
            .setContentText(status.device)
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
    }

    /** One RFCOMM session. Once closed by the service, its callbacks are dropped. */
    private inner class Session(private val device: BluetoothDevice) : Thread("budi-spp") {
        @Volatile private var socket: BluetoothSocket? = null
        @Volatile private var closed = false

        fun close() {
            closed = true
            closeSocket()
        }

        override fun run() {
            try {
                val s = device.createRfcommSocketToServiceRecord(Buds.SPP_UUID)
                socket = s
                if (closed) return
                s.connect()
                post { onConnected() }
                val reader = Buds.FrameReader { id, payload ->
                    Buds.noiseMode(id, payload)?.let { post { onMode(it) } }
                }
                val buffer = ByteArray(512)
                val input = s.inputStream
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    reader.feed(buffer, n)
                }
            } catch (e: IOException) {
                Log.d(TAG, "Link: ${e.message}")
            } catch (e: SecurityException) {
                Log.w(TAG, "Link", e)
            } finally {
                closeSocket()
            }
            post { onLost() }
        }

        private fun closeSocket() {
            try {
                socket?.close()
            } catch (e: IOException) {
                // already closed
            }
        }

        private fun post(action: () -> Unit) {
            main.post { if (!closed && session === this) action() }
        }
    }
}
