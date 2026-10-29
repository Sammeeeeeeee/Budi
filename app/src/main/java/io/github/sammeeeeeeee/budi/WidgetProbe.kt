package io.github.sammeeeeeeee.budi

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Checkable
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Hosts a package's home screen widgets off-screen and records every update: the RemoteViews
 * actions, with resource names, and the state of the inflated views. Shows whether Samsung's
 * buds widget mirrors the noise control mode, which Budi could follow without the RFCOMM link.
 * Main thread only. Binding needs `appwidget grantbind` (run through Shizuku) first.
 */
class WidgetProbe(private val context: Context, private val pkg: String) {
    private val log = StringBuilder()
    private val started = SystemClock.elapsedRealtime()
    private val theirs: Resources = context.packageManager.getResourcesForApplication(pkg)
    private val manager = AppWidgetManager.getInstance(context)
    private val host = object : AppWidgetHost(context, HOST_ID) {
        override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?) =
            object : AppWidgetHostView(context) {
                override fun updateAppWidget(remoteViews: RemoteViews?) {
                    super.updateAppWidget(remoteViews)
                    record(appWidget, remoteViews, this)
                }
            }
    }

    fun start() {
        HiddenApiBypass.addHiddenApiExemptions("L")
        host.startListening()
        for (info in manager.getInstalledProvidersForPackage(pkg, null)) {
            log.append("provider ${info.provider.className} ${info.minWidth}x${info.minHeight}")
                .append(" layout=${name(info.initialLayout)} configure=${info.configure?.className}\n")
            val id = host.allocateAppWidgetId()
            if (manager.bindAppWidgetIdIfAllowed(id, info.provider)) {
                host.createView(context, id, info)
            } else {
                log.append("  not allowed to bind\n")
                host.deleteAppWidgetId(id)
            }
        }
    }

    fun stop(): String {
        host.stopListening()
        host.deleteHost()
        return log.toString()
    }

    private fun record(info: AppWidgetProviderInfo?, views: RemoteViews?, root: View) {
        val seconds = (SystemClock.elapsedRealtime() - started) / 1000f
        log.append("\n[+%.1fs] %s\n".format(seconds, info?.provider?.className))
        if (views == null) {
            log.append("  no views\n")
            return
        }
        try {
            remoteViews(views, "  ", 0)
            log.append("  inflated:\n")
            tree(root, "    ")
        } catch (e: Exception) {
            log.append("  failed: ").append(e).append('\n')
        }
    }

    private fun remoteViews(views: RemoteViews, indent: String, depth: Int) {
        log.append(indent).append("layout ").append(name(field(views, "mLayoutId") as? Int ?: 0)).append('\n')
        (field(views, "mActions") as? List<*>)?.filterNotNull()?.forEach { action ->
            log.append(indent).append("  ").append(action.javaClass.simpleName)
            val nested = mutableListOf<RemoteViews>()
            for (f in fields(action.javaClass)) {
                when (val value = f.get(action)) {
                    is RemoteViews -> nested += value
                    else -> log.append(' ').append(f.name).append('=').append(format(f.name, value))
                }
            }
            log.append('\n')
            if (depth < 3) nested.forEach { remoteViews(it, "$indent    ", depth + 1) }
        }
        val variants = listOfNotNull(field(views, "mLandscape"), field(views, "mPortrait")) +
            ((field(views, "mSizedRemoteViews") as? List<*>) ?: emptyList<Any>())
        if (depth < 3) variants.filterIsInstance<RemoteViews>().forEach { remoteViews(it, "$indent    ", depth + 1) }
    }

    private fun tree(view: View, indent: String) {
        log.append(indent).append(view.javaClass.simpleName)
        if (view.id != View.NO_ID) log.append(" #").append(name(view.id))
        if (view is TextView && !view.text.isNullOrEmpty()) {
            log.append(" \"").append(view.text).append("\" color=#%08X".format(view.currentTextColor))
        }
        view.contentDescription?.let { log.append(" desc=\"").append(it).append('"') }
        if (view.isSelected) log.append(" selected")
        if (view.isActivated) log.append(" activated")
        if ((view as? Checkable)?.isChecked == true) log.append(" checked")
        if (!view.isEnabled) log.append(" disabled")
        if (view.visibility != View.VISIBLE) log.append(if (view.visibility == View.GONE) " gone" else " invisible")
        if (view.alpha < 1f) log.append(" alpha=").append(view.alpha)
        (view.background as? ColorDrawable)?.let { log.append(" bg=#%08X".format(it.color)) }
        (view as? ImageView)?.imageTintList?.let { log.append(" tint=#%08X".format(it.defaultColor)) }
        log.append('\n')
        if (view is ViewGroup) for (i in 0 until view.childCount) tree(view.getChildAt(i), "$indent  ")
    }

    private fun format(field: String, value: Any?): String = when (value) {
        null -> "null"
        is Int -> when {
            field.endsWith("Id") || value ushr 24 == 0x7f || value ushr 24 == 0x01 -> name(value)
            value ushr 24 == 0xFF -> "#%08X".format(value)
            else -> value.toString()
        }
        is Bitmap -> "Bitmap(${value.width}x${value.height})"
        is CharSequence -> "\"$value\""
        else -> value.toString().take(120)
    }

    /** "type/name" for a resource or view ID from Samsung's package or the framework. */
    private fun name(id: Int): String = try {
        theirs.getResourceName(id).substringAfter(':')
    } catch (e: Resources.NotFoundException) {
        "0x%08X".format(id)
    }

    private fun field(target: Any, name: String): Any? =
        generateSequence<Class<*>>(target.javaClass) { it.superclass }
            .firstNotNullOfOrNull { cls -> cls.declaredFields.firstOrNull { it.name == name } }
            ?.apply { isAccessible = true }
            ?.get(target)

    private fun fields(cls: Class<*>): List<Field> =
        generateSequence(cls) { it.superclass }
            .takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .onEach { it.isAccessible = true }
            .toList()

    private companion object {
        const val HOST_ID = 0xB0D1
    }
}
