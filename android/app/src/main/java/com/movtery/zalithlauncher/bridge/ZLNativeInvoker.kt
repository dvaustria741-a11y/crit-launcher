/*
 * Crit Launcher (Android build) - slimmed re-implementation of ZalithLauncher2's ZLNativeInvoker.
 * The package, class name and the @Keep static methods below must stay as they are: the engine's native
 * libraries (libpojavexec.so, libexithook.so) look them up by name. Part of the GPLv3 Android build,
 * see android/NOTICE-GPL.md.
 */
package com.movtery.zalithlauncher.bridge

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.Keep

@Keep
object ZLNativeInvoker {
    /** The game screen; set by GameActivity. */
    @JvmStatic
    var activity: Activity? = null

    /** Called when the JVM exits (code, wasSignal). Set by GameActivity. */
    @JvmStatic
    var onExit: ((Int, Boolean) -> Unit)? = null

    @Keep
    @JvmStatic
    fun openLink(link: String) {
        val act = activity ?: return
        if (!link.startsWith("http://") && !link.startsWith("https://")) return
        act.runOnUiThread {
            try {
                act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
            } catch (_: Exception) {
            }
        }
    }

    @Keep
    @JvmStatic
    fun querySystemClipboard() {
        val act = activity ?: return
        act.runOnUiThread {
            val text = (act.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                ?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
            if (text == null) ZLBridge.clipboardReceived(null, null)
            else ZLBridge.clipboardReceived(text, "plain")
        }
    }

    @Keep
    @JvmStatic
    fun putClipboardData(data: String, mimeType: String) {
        val act = activity ?: return
        act.runOnUiThread {
            val clip = when (mimeType) {
                "text/plain" -> ClipData.newPlainText("Crit Launcher", data)
                "text/html" -> ClipData.newHtmlText("Crit Launcher", data, data)
                else -> null
            } ?: return@runOnUiThread
            (act.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(clip)
        }
    }

    @Keep
    @JvmStatic
    fun jvmExit(exitCode: Int, isSignal: Boolean) {
        onExit?.invoke(exitCode, isSignal)
    }
}
