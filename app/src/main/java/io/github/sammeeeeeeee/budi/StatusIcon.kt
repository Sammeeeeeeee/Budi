package io.github.sammeeeeeeee.budi

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * A system icon beside Wi‑Fi and battery, set through IStatusBarService.setIcon. That needs
 * android.permission.STATUS_BAR, which the shell user holds, so the calls go through Shizuku.
 */
object StatusIcon {
    private const val SLOT = "budi"

    val ready: Boolean
        get() = try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: RuntimeException) {
            false
        }

    private val statusBar: Any by lazy {
        HiddenApiBypass.addHiddenApiExemptions("L")
        val binder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("statusbar"))
        Class.forName("com.android.internal.statusbar.IStatusBarService\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!
    }

    fun show(context: Context, mode: NoiseMode) =
        call("setIcon", SLOT, context.packageName, mode.icon, 0, context.getString(mode.label))

    fun hide() = call("removeIcon", SLOT)

    private fun call(method: String, vararg args: Any): Boolean {
        if (!ready) return false
        return try {
            statusBar.javaClass.methods
                .first { it.name == method && it.parameterCount == args.size }
                .invoke(statusBar, *args)
            true
        } catch (e: Exception) {
            Log.w("Budi", "$method failed", e)
            false
        }
    }
}
