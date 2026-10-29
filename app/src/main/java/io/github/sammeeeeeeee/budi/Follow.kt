package io.github.sammeeeeeeee.budi

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Starts, stops and asks the [Follower], through Shizuku. Main thread only.
 *
 * Shizuku calls [connection] from inside a loop over its connections, and again whenever anyone
 * binds or peeks. So Budi keeps one connection for good, never unbinds it, and remembers the
 * follower it was last handed; whoever is waiting gets that.
 */
object Follow {
    private const val TAG = "Budi"

    private var follower: IFollower? = null
    private val waiting = mutableListOf<(IFollower?) -> Unit>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            IFollower.Stub.asInterface(binder).let {
                follower = it
                answer(it)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            follower = null
        }
    }

    /** Follows the plugins' logs and shows the mode in the status bar, starting the follower if needed. */
    fun start(context: Context, plugins: List<String>, done: () -> Unit) {
        val (iconPackage, icons) = Icons.resolve(context)
        val labels = NoiseMode.entries.map { context.getString(it.label) }.toTypedArray()
        val then: (IFollower?) -> Unit = { follower ->
            call { follower?.follow(plugins.toTypedArray(), iconPackage, icons, labels) }
            done()
        }
        waiting += then
        if (shizuku { Shizuku.bindUserService(args(context), connection) } == null && waiting.remove(then)) then(null)
    }

    /** Stops following and removes the icon, including one left behind by a follower that died. */
    fun stop(context: Context) {
        follower = null
        answer(null)
        shizuku { Shizuku.unbindUserService(args(context), null, true) }
        if (StatusBar.shizukuReady) StatusBar.viaShizuku().hide()
    }

    /** The mode the follower last saw: null if it isn't running, -1 if it hasn't seen one yet. */
    fun mode(context: Context, result: (Int?) -> Unit) = ask(context, result) { it.mode() }

    /** What the follower has seen lately, or null if it isn't running. */
    fun trace(context: Context, result: (String?) -> Unit) = ask(context, result) { it.trace() }

    private fun <T> ask(context: Context, result: (T?) -> Unit, question: (IFollower) -> T) {
        follower?.takeIf { it.asBinder().isBinderAlive }?.let { return result(call { question(it) }) }
        follower = null
        val then: (IFollower?) -> Unit = { follower -> result(follower?.let { call { question(it) } }) }
        waiting += then
        // If it's running, Shizuku hands it over shortly; if not, nothing comes.
        val running = shizuku { Shizuku.peekUserService(args(context), connection) >= 0 } == true
        if (!running && waiting.remove(then)) then(null)
    }

    private fun answer(follower: IFollower?) {
        val callbacks = waiting.toList()
        waiting.clear()
        callbacks.forEach { it(follower) }
    }

    private fun args(context: Context) = Shizuku.UserServiceArgs(ComponentName(context, Follower::class.java))
        .daemon(true)
        .processNameSuffix("follower")
        .version(context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt())

    private inline fun <T> call(block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        Log.w(TAG, "Follower call failed", e)
        null
    }

    private inline fun <T> shizuku(block: () -> T): T? = try {
        block()
    } catch (e: RuntimeException) {
        Log.w(TAG, "Shizuku unavailable", e)
        null
    }
}
