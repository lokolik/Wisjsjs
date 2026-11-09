package dev.relay.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import dev.relay.agent.tools.Platform
import android.os.Environment
import java.io.File

class AndroidPlatform(private val context: Context) : Platform {
    override val storageRoot: File
        get() = Environment.getExternalStorageDirectory()

    override fun openUrl(url: String): String {
        val u = if (url.contains("://")) url else "https://$url"
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Открыто: $u"
    }

    /** Accepts a package name or a visible app label ("Telegram"). */
    override fun launchApp(nameOrPackage: String): String {
        val pm = context.packageManager
        val pkg = if (pm.getLaunchIntentForPackage(nameOrPackage) != null) {
            nameOrPackage
        } else {
            launcherApps(context).firstOrNull { it.second.equals(nameOrPackage, ignoreCase = true) }?.first
                ?: launcherApps(context).firstOrNull { it.second.contains(nameOrPackage, ignoreCase = true) }?.first
                ?: throw IllegalArgumentException("Приложение «$nameOrPackage» не найдено")
        }
        val intent = pm.getLaunchIntentForPackage(pkg) ?: throw IllegalArgumentException("У $pkg нет главного экрана")
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Запущено: $pkg"
    }

    override fun deviceInfo(): Map<String, Any?> = linkedMapOf(
        "Устройство" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "Android" to "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
        "Архитектура" to Build.SUPPORTED_ABIS.joinToString(),
    )

    companion object {
        /** (package, label) of launchable apps, sorted by label. */
        fun launcherApps(context: Context): List<Pair<String, String>> {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            return pm.queryIntentActivities(intent, 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .distinctBy { it.first }
                .filter { it.first != context.packageName }
                .sortedBy { it.second.lowercase() }
        }
    }
}
