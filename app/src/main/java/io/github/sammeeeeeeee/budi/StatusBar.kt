package io.github.sammeeeeeeee.budi

import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * A system icon beside Wi‑Fi and battery, set through IStatusBarService. That needs
 * android.permission.STATUS_BAR, which the shell user holds: the [Follower] calls it directly from
 * its shell process, and Budi's app process goes through Shizuku.
 */
class StatusBar(binder: IBinder) {
    private val service: Any = run {
        HiddenApiBypass.addHiddenApiExemptions("L")
        Class.forName("com.android.internal.statusbar.IStatusBarService\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!
    }

    fun show(pkg: String, icon: Int, description: String) = call("setIcon", SLOT, pkg, icon, 0, description)

    fun hide() = call("removeIcon", SLOT)

    private fun call(method: String, vararg args: Any): Boolean = try {
        service.javaClass.methods
            .first { it.name == method && it.parameterCount == args.size }
            .invoke(service, *args)
        true
    } catch (e: Exception) {
        Log.w("Budi", "$method failed", e)
        false
    }

    companion object {
        private const val SLOT = "budi"

        /** Shizuku is running and has granted Budi access. */
        val shizukuReady: Boolean
            get() = try {
                Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (e: RuntimeException) {
                false
            }

        /** From Budi's app process. Only while [shizukuReady]. */
        fun viaShizuku() = StatusBar(ShizukuBinderWrapper(SystemServiceHelper.getSystemService("statusbar")))

        /** From a process already running as the shell user. */
        fun direct() = StatusBar(
            Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "statusbar") as IBinder
        )
    }
}
