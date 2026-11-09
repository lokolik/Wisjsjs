package dev.relay.agent.site

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.relay.agent.agent.Attachment
import dev.relay.agent.agent.ChatTarget
import dev.relay.agent.agent.TargetReply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
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

/** What a website session needs from Relay: file pickers and downloads. */
interface SiteHost {
    val autoDownload: Boolean
    fun chooseFiles(cb: (Array<Uri>?) -> Unit)
    fun download(url: String, userAgent: String, disposition: String?, mime: String?)
    fun saveDataUrl(name: String, dataUrl: String)
}

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
    private val host: SiteHost,
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
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                host.chooseFiles { uris -> callback.onReceiveValue(uris) }
                return true
            }
        }
        webView.setDownloadListener { url, userAgent, disposition, mime, _ ->
            val name = URLUtil.guessFileName(url, disposition, mime)
            when {
                url.startsWith("blob:") -> scope.launch { runCatching { eval(blobJs(url, name)) } }
                url.startsWith("data:") -> host.saveDataUrl(name, url)
                else -> host.download(url, userAgent, disposition, mime)
            }
        }
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
        suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript(js) { if (cont.isActive) cont.resume(it ?: "null") }
        }
    }

    private fun configJson(): String = JSONObject()
        .put("selectors", adapter.optJSONObject("selectors") ?: JSONObject())
        .put("submit", adapter.optString("submit", "auto"))
        .put("stableMs", adapter.optInt("stableMs", 2200))
        .put("timeoutMs", TIMEOUT_MS)
        .put("autoDownload", host.autoDownload)
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

    suspend fun diagnose(selectors: JSONObject?): JSONObject {
        ensureCore()
        return evalJson("window.__relay.diagnose(${selectors?.toString() ?: "null"})") as? JSONObject ?: JSONObject()
    }

    fun navigate(url: String) {
        webView.loadUrl(url)
    }

    /** Hands files to the page as real File objects (file input, paste or drop — whatever the site supports). */
    private suspend fun attach(files: List<Attachment>) {
        var total = 0L
        val arr = JSONArray()
        for (a in files) {
            total += a.size
            if (a.size > MAX_SITE_FILE || total > MAX_SITE_TOTAL) {
                throw IllegalStateException("Файл «${a.name}» слишком большой для отправки на сайт (до ${MAX_SITE_FILE / 1024 / 1024} МБ)")
            }
            val b64 = withContext(Dispatchers.IO) { Base64.encodeToString(a.file.readBytes(), Base64.NO_WRAP) }
            arr.put(JSONObject().put("name", a.name).put("mime", a.mime).put("b64", b64))
        }
        val res = evalJson("window.__relay.attach($arr)") as? JSONObject
        if (res == null || !res.optBoolean("ok")) {
            throw IllegalStateException("Сайт не принял файлы: не найдено поле загрузки. Откройте сайт (🌐) и прикрепите вручную.")
        }
        delay(1200)
    }

    override suspend fun send(text: String, attachments: List<Attachment>, onPartial: (String) -> Unit): TargetReply {
        ensureCore()
        if (attachments.isNotEmpty()) attach(attachments)
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<JSONObject>()
        pending[id] = done
        partials[id] = onPartial
        prompts[id] = text
        try {
            val opts = JSONObject().put("attachments", attachments.size)
            eval("window.__relay.send(${JSONObject.quote(text)}, ${JSONObject.quote(id)}, $opts)")
            val ev = withTimeout(TIMEOUT_MS + 60_000L) { done.await() }
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
        webView.stopLoading()
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
                "blob" -> host.saveDataUrl(obj.optString("name", "download"), obj.optString("data"))
                else -> main.post { onEvent(this@SiteSession, obj) }
            }
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val scheme = uri.scheme ?: return false
            if (scheme == "http" || scheme == "https" || scheme == "blob" || scheme == "data") return false
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
    }

    companion object {
        const val TIMEOUT_MS = 240_000L
        private const val MAX_SITE_FILE = 20L * 1024 * 1024
        private const val MAX_SITE_TOTAL = 40L * 1024 * 1024
        const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /** blob: URLs only exist inside the page, so the page reads them and hands the bytes over. */
        private fun blobJs(url: String, name: String) = """
            (function(){fetch(${JSONObject.quote(url)}).then(function(r){return r.blob()}).then(function(b){
              var fr=new FileReader();fr.onload=function(){RelayNative.postMessage(JSON.stringify({type:'blob',name:${JSONObject.quote(name)},mime:b.type,data:fr.result}))};
              fr.readAsDataURL(b);}).catch(function(e){RelayNative.postMessage(JSON.stringify({type:'log',message:'blob: '+e}))});})();true
        """.trimIndent()
    }
}
