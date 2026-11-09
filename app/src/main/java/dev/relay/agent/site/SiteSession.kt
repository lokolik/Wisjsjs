package dev.relay.agent.site

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.relay.agent.agent.ChatTarget
import dev.relay.agent.agent.TargetReply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * One AI website in its own WebView, logged in with the user's normal account.
 * Prompts are typed into the site's own chat box by relay-core.js and replies are read from the page.
 */
@SuppressLint("SetJavaScriptEnabled")
class SiteSession(
    private val activity: Activity,
    adapter: JSONObject,
    private val coreJs: String,
    private val scope: CoroutineScope,
    private val onEvent: (SiteSession, JSONObject) -> Unit,
) : ChatTarget {
    var adapter: JSONObject = adapter
        private set
    val id: String get() = adapter.optString("id")
    val webView: WebView = WebView(activity)

    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val partials = ConcurrentHashMap<String, (String) -> Unit>()
    private val prompts = ConcurrentHashMap<String, String>()
    private val main = Handler(Looper.getMainLooper())
    private val defaultUa: String

    /** Set once the renderer died; the WebView must not be used after that. */
    @Volatile
    private var crashed = false

    var currentUrl: String = adapter.optString("url")
        private set

    init {
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, true)
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        defaultUa = webView.settings.userAgentString
        applyUserAgent()
        webView.setBackgroundColor(Color.WHITE)
        webView.addJavascriptInterface(Bridge(), "RelayNative")
        webView.webViewClient = Client()
        webView.webChromeClient = WebChromeClient()
        webView.loadUrl(adapter.optString("url"))
    }

    private fun applyUserAgent() {
        webView.settings.userAgentString = if (adapter.optBoolean("desktop", false)) DESKTOP_UA else defaultUa
    }

    fun updateAdapter(a: JSONObject) {
        val uaChanged = a.optBoolean("desktop", false) != adapter.optBoolean("desktop", false)
        val urlChanged = a.optString("url") != adapter.optString("url")
        adapter = a
        when {
            urlChanged -> {
                applyUserAgent()
                webView.loadUrl(a.optString("url"))
            }
            uaChanged -> {
                applyUserAgent()
                webView.reload()
            }
            else -> scope.launch { runCatching { ensureCore() } }
        }
    }

    suspend fun eval(js: String): String = withContext(Dispatchers.Main) {
        if (crashed) return@withContext "null"
        suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript(js) { if (cont.isActive) cont.resume(it ?: "null") }
        }
    }

    private fun configJson(): String = JSONObject()
        .put("selectors", adapter.optJSONObject("selectors") ?: JSONObject())
        .put("submit", adapter.optString("submit", "auto"))
        .put("stableMs", adapter.optInt("stableMs", 2200))
        .put("timeoutMs", TIMEOUT_MS)
        .toString()

    /** Idempotent: the core script keeps its state if the same version is already loaded. */
    suspend fun ensureCore() {
        eval("$coreJs\n;window.__relay.configure(${configJson()});true")
    }

    private suspend fun evalJson(expr: String): Any? {
        val raw = eval("JSON.stringify($expr)")
        val str = try {
            JSONTokener(raw).nextValue() as? String
        } catch (e: Exception) {
            null
        } ?: return null
        return try {
            JSONTokener(str).nextValue()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun status(): JSONObject {
        ensureCore()
        return evalJson("window.__relay.status()") as? JSONObject ?: JSONObject()
    }

    suspend fun listChats(): JSONArray {
        ensureCore()
        return evalJson("window.__relay.listChats()") as? JSONArray ?: JSONArray()
    }

    suspend fun diagnose(): JSONObject {
        ensureCore()
        return evalJson("window.__relay.diagnose()") as? JSONObject ?: JSONObject()
    }

    fun navigate(url: String) {
        webView.loadUrl(url)
    }

    override suspend fun send(text: String, onPartial: (String) -> Unit): TargetReply {
        ensureCore()
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<JSONObject>()
        pending[id] = done
        partials[id] = onPartial
        prompts[id] = text
        try {
            eval("window.__relay.send(${JSONObject.quote(text)}, ${JSONObject.quote(id)})")
            val ev = withTimeout(TIMEOUT_MS + 20_000L) { done.await() }
            if (ev.optString("type") == "error") throw IllegalStateException(ev.optString("message", "Ошибка на сайте"))
            val code = ev.optJSONArray("code")?.let { arr -> List(arr.length()) { arr.optString(it) } } ?: emptyList()
            return TargetReply(ev.optString("text"), code, ev.optBoolean("timeout"))
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                runCatching { eval("window.__relay && window.__relay.cancel(${JSONObject.quote(id)}, true)") }
            }
            throw e
        } finally {
            pending.remove(id)
            partials.remove(id)
            prompts.remove(id)
        }
    }

    suspend fun pick(kind: String) {
        ensureCore()
        eval("window.__relay.pick(${JSONObject.quote(kind)})")
    }

    suspend fun cancelPick() {
        runCatching { eval("window.__relay && window.__relay.unpick()") }
    }

    fun destroy() {
        pending.values.forEach { it.cancel() }
        (webView.parent as? ViewGroup)?.removeView(webView)
        if (!crashed) webView.stopLoading()
        webView.destroy()
    }

    private inner class Bridge {
        @JavascriptInterface
        fun postMessage(json: String) {
            val obj = try {
                JSONObject(json)
            } catch (e: Exception) {
                return
            }
            val id = obj.optString("id")
            when (obj.optString("type")) {
                "partial" -> partials[id]?.let { cb ->
                    val t = obj.optString("text")
                    main.post { cb(t) }
                }
                "response", "error" -> {
                    val waiter = pending[id]
                    if (waiter != null) waiter.complete(obj) else main.post { onEvent(this@SiteSession, obj) }
                }
                else -> main.post { onEvent(this@SiteSession, obj) }
            }
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val scheme = uri.scheme ?: return false
            if (scheme == "http" || scheme == "https") return false
            runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return true
        }

        override fun onPageFinished(view: WebView, url: String) {
            currentUrl = url
            scope.launch {
                runCatching { ensureCore() }
                // A full page load kills in-page watchers; re-attach them to the new document.
                for ((id, prompt) in prompts) {
                    runCatching { eval("window.__relay.watch(${JSONObject.quote(id)}, ${JSONObject.quote(prompt)}, {afterReload:true})") }
                }
                onEvent(this@SiteSession, JSONObject().put("type", "pageLoaded").put("url", url).put("title", view.title ?: ""))
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            if (url != currentUrl) {
                currentUrl = url
                onEvent(this@SiteSession, JSONObject().put("type", "urlChanged").put("url", url))
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // Returning false lets Android kill the whole app; the controller rebuilds this site instead.
            crashed = true
            val error = IllegalStateException("Страница сайта аварийно закрылась — отправьте сообщение ещё раз")
            pending.values.forEach { it.completeExceptionally(error) }
            main.post { onEvent(this@SiteSession, JSONObject().put("type", "crashed")) }
            return true
        }
    }

    companion object {
        const val TIMEOUT_MS = 240_000L
        const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
