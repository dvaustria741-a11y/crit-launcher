package xyz.critlauncher.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Hosts the launcher page in a WebView; the Python backend runs in-process via Chaquopy. */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private val pool = Executors.newCachedThreadPool()

    /** Methods Python may call (never exposed to the page). */
    inner class PyHost {
        fun evalJs(code: String) {
            webView.post { webView.evaluateJavascript(code, null) }
        }

        fun openUrl(url: String) {
            runOnUiThread {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    Log.w(TAG, "openUrl failed", e)
                }
            }
        }

        fun finishApp() {
            runOnUiThread { finish() }
        }

        /** Opens the game screen (own process) for the launch described in [specPath] (JSON). */
        fun startGame(specPath: String, logPath: String) {
            runOnUiThread {
                startActivity(
                    Intent(this@MainActivity, GameActivity::class.java)
                        .putExtra(GameActivity.EXTRA_SPEC, specPath)
                        .putExtra(GameActivity.EXTRA_LOG, logPath)
                )
            }
        }

        fun stopGame() {
            sendBroadcast(Intent(GameActivity.ACTION_STOP).setPackage(packageName))
        }
    }

    /** The only object the page can reach: it forwards one call to Python. */
    inner class PageBridge {
        @JavascriptInterface
        fun call(id: Int, method: String, argsJson: String) {
            pool.execute {
                val result = try {
                    Python.getInstance().getModule("crit_launcher.android.entry")
                        .callAttr("dispatch", method, argsJson).toString()
                } catch (e: Throwable) {
                    Log.e(TAG, "dispatch $method failed", e)
                    JSONObject().put("success", false).put("error", e.message ?: "bridge error").toString()
                }
                webView.post {
                    webView.evaluateJavascript("window.__critResolve($id, ${JSONObject.quote(result)});", null)
                }
            }
        }
    }

    /** The game runs in another process; it tells us when the JVM ended. */
    private val gameExitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val code = intent.getIntExtra(GameActivity.EXTRA_CODE, 1)
            pool.execute {
                try {
                    Python.getInstance().getModule("crit_launcher.android.entry").callAttr("game_exited", code)
                } catch (e: Throwable) {
                    Log.w(TAG, "game_exited failed", e)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val exitFilter = IntentFilter(GameActivity.ACTION_EXIT)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(gameExitReceiver, exitFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(gameExitReceiver, exitFilter)
        }
        webView = WebView(this).apply {
            setBackgroundColor(0xFF0B0C0E.toInt())
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = true
                mediaPlaybackRequiresUserGesture = false
                useWideViewPort = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                    val url = request.url
                    if (url.scheme == "file") return false
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    return true
                }
            }
            addJavascriptInterface(PageBridge(), "CritBridge")
        }
        setContentView(webView)
        pool.execute {
            try {
                val resDir = File(filesDir, "crit_res")
                syncResources(resDir)
                if (!Python.isStarted()) Python.start(AndroidPlatform(this))
                val page = Python.getInstance().getModule("crit_launcher.android.entry")
                    .callAttr("start", PyHost(), File(filesDir, "crit").absolutePath, resDir.absolutePath)
                    .toString()
                webView.post { webView.loadUrl("file://$page") }
            } catch (e: Throwable) {
                Log.e(TAG, "backend start failed", e)
                val msg = JSONObject.quote(e.toString())
                webView.post {
                    webView.loadDataWithBaseURL(
                        null,
                        "<body style='background:#0b0c0e;color:#e8eaf0;font:14px sans-serif;padding:24px'>" +
                            "<h3>Crit Launcher failed to start</h3><pre id=m></pre>" +
                            "<script>document.getElementById('m').textContent=$msg</script></body>",
                        "text/html", "utf-8", null
                    )
                }
            }
        }
    }

    /** Copy bundled index/artwork out of the APK, refreshed whenever the app is updated. */
    private fun syncResources(dest: File) {
        val stamp = File(dest, ".version")
        val current = packageManager.getPackageInfo(packageName, 0).lastUpdateTime.toString()
        if (stamp.exists() && stamp.readText() == current) return
        dest.deleteRecursively()
        copyAssetDir("crit_res", dest)
        stamp.writeText(current)
    }

    private fun copyAssetDir(assetPath: String, dest: File) {
        val children = assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            assets.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
            return
        }
        dest.mkdirs()
        for (name in children) copyAssetDir("$assetPath/$name", File(dest, name))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else moveTaskToBack(true)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(gameExitReceiver)
        } catch (_: Throwable) {
        }
        try {
            if (Python.isStarted()) {
                Python.getInstance().getModule("crit_launcher.android.entry").callAttr("shutdown")
            }
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CritLauncher"
    }
}
