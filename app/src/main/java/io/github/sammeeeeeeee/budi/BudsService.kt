package io.github.sammeeeeeeee.budi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Starts or stops the [Follower] as the buds connect or disconnect, then stops itself. That takes
 * about a second, well under the 10 s Android waits before showing a foreground service's
 * notification, so nothing appears. Being in the foreground gets Budi Shizuku's binder promptly.
 */
class BudsService : Service() {

    companion object {
        private const val ACTION_CONNECTED = "connected"
        private const val ACTION_DISCONNECTED = "disconnected"
        private const val CHANNEL = "service"
        private const val TIMEOUT_MS = 15_000L

        fun connected(context: Context) = launch(context, ACTION_CONNECTED)

        fun disconnected(context: Context) = launch(context, ACTION_DISCONNECTED)

        private fun launch(context: Context, action: String) {
            try {
                context.startForegroundService(Intent(context, BudsService::class.java).setAction(action))
            } catch (e: IllegalStateException) {
                Log.w("Budi", "Not allowed to start", e)
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<String>()
    private val giveUp = Runnable { stopSelf(lastStart) }
    private val onShizuku = object : Shizuku.OnBinderReceivedListener {
        override fun onBinderReceived() {
            Shizuku.removeBinderReceivedListener(this)
            next()
        }
    }
    private var busy = false
    private var lastStart = 0

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_service), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_noise_unknown)
            .setContentTitle(getString(R.string.app_name))
            .build()
        try {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: RuntimeException) {
            Log.w("Budi", "Can't start in the foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        lastStart = startId
        intent?.action?.let(queue::addLast)
        main.removeCallbacks(giveUp)
        main.postDelayed(giveUp, TIMEOUT_MS)
        if (Shizuku.pingBinder()) next() else Shizuku.addBinderReceivedListener(onShizuku, main)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(giveUp)
        Shizuku.removeBinderReceivedListener(onShizuku)
        super.onDestroy()
    }

    /** One action at a time, so a quick connect then disconnect can't leave a follower running. */
    private fun next() {
        if (busy) return
        val action = queue.removeFirstOrNull()
        if (action == null || !StatusBar.shizukuReady) {
            stopSelf(lastStart) // a newer start keeps it running
            return
        }
        busy = true
        val done = {
            busy = false
            next()
        }
        when (action) {
            ACTION_CONNECTED -> Follow.start(this, Buds.samsungPlugins(this), done)
            else -> Follow.stop(this, done)
        }
    }
}
