package io.github.sammeeeeeeee.budi

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku

/** Starts, stops and asks the [Follower], through Shizuku. Main thread only. */
object Follow {
    private const val TAG = "Budi"

    /** Follows the plugins' logs and shows the mode in the status bar. */
    fun start(context: Context, plugins: List<String>, done: () -> Unit) {
        val (iconPackage, icons) = Icons.resolve(context)
        val labels = NoiseMode.entries.map { context.getString(it.label) }.toTypedArray()
        use(context, create = true, missing = done) { follower, release ->
            follower.follow(plugins.toTypedArray(), iconPackage, icons, labels)
            release(false)
            done()
        }
    }

    /** Stops following and removes the icon, including one left behind by a follower that died. */
    fun stop(context: Context, done: () -> Unit) {
        val leftover = {
            if (StatusBar.shizukuReady) runCatching { StatusBar.viaShizuku().hide() }
            done()
        }
        use(context, create = false, missing = leftover) { _, release ->
            release(true)
            done()
        }
    }

    /** The mode the follower last saw: null if it isn't running, -1 if it hasn't seen one yet. */
    fun mode(context: Context, result: (Int?) -> Unit) {
        use(context, create = false, missing = { result(null) }) { follower, release ->
            val mode = follower.mode()
            release(false)
            result(mode)
        }
    }

    private fun use(
        context: Context,
        create: Boolean,
        missing: () -> Unit,
        block: (IFollower, release: (remove: Boolean) -> Unit) -> Unit,
    ) {
        val args = Shizuku.UserServiceArgs(ComponentName(context, Follower::class.java))
            .daemon(true)
            .processNameSuffix("follower")
            .version(context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt())
        val connection = object : ServiceConnection {
            private var used = false

            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (used) return
                used = true
                val release = { remove: Boolean ->
                    if (remove) Shizuku.unbindUserService(args, null, true)
                    Shizuku.unbindUserService(args, this, false)
                }
                try {
                    block(IFollower.Stub.asInterface(binder), release)
                } catch (e: Exception) {
                    Log.w(TAG, "Follower call failed", e)
                    runCatching { release(false) }
                    missing()
                }
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        try {
            if (create) {
                Shizuku.bindUserService(args, connection)
            } else if (Shizuku.peekUserService(args, connection) < 0) {
                Shizuku.unbindUserService(args, connection, false)
                missing()
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Shizuku unavailable", e)
            missing()
        }
    }
}
