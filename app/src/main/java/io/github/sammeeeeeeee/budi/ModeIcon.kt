package io.github.sammeeeeeeee.budi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The mode as a status bar icon. One UI 7 and later keep the right of the closed status bar for
 * Wi‑Fi, mobile data and battery, so Budi's icon is a notification icon, which One UI shows by the
 * clock. It's silent and stays put until the buds disconnect.
 */
object ModeIcon {
    private const val CHANNEL = "mode"
    private const val ID = 2

    fun show(context: Context, mode: NoiseMode) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_mode), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
        )
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        manager.notify(
            ID,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(mode.icon)
                .setContentTitle(context.getString(mode.label))
                .setContentIntent(open)
                .setCategory(Notification.CATEGORY_STATUS)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setLocalOnly(true)
                .build()
        )
    }

    fun hide(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(ID)
}

/** Hears each new mode from the [Follower]. Only the shell, which Shizuku runs it as, may send. */
class ModeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val mode = NoiseMode.of(intent.getIntExtra(EXTRA_MODE, -1)) ?: return
        // A word from a follower that's on its way out mustn't bring the icon back.
        if (Buds.connected(context) == false) ModeIcon.hide(context) else ModeIcon.show(context, mode)
    }

    companion object {
        const val EXTRA_MODE = "mode"
    }
}
