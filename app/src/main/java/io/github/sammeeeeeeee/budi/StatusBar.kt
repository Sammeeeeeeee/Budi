package io.github.sammeeeeeeee.budi

import android.os.IBinder
import org.lsposed.hiddenapibypass.HiddenApiBypass

/** The status bar slot Budi 0.2 and earlier drew in. Run from the shell, which may change it. */
internal object StatusBar {
    private const val SLOT = "budi"

    /** Removes an icon an earlier Budi left behind. */
    fun clear() {
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions("L")
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "statusbar") as IBinder
            val service = Class.forName("com.android.internal.statusbar.IStatusBarService\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)!!
            service.javaClass.getMethod("removeIcon", String::class.java).invoke(service, SLOT)
        }
    }
}
