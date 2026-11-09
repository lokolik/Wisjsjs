package dev.relay.agent

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var controller: RelayController
    private lateinit var ui: WebView
    private lateinit var siteBar: LinearLayout
    private lateinit var siteTitle: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)

        val root = FrameLayout(this).apply { setBackgroundColor(BG) }
        // Site WebViews live under the UI and stay laid out so their scripts see real geometry.
        val siteColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        siteBar = buildSiteBar()
        siteBar.visibility = View.GONE
        val siteFrame = FrameLayout(this)
        siteColumn.addView(siteBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        siteColumn.addView(siteFrame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        ui = WebView(this).apply { setBackgroundColor(BG) }
        root.addView(siteColumn, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(ui, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
        applyInsets(root)

        controller = RelayController(this, ui, siteFrame)
        with(ui.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            // file:///android_asset stays readable without general file access.
            allowFileAccess = false
        }
        ui.addJavascriptInterface(UiBridge(), "RelayHost")
        ui.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.scheme == "file") return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                return true
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // Returning false would kill the app; rebuilding the activity brings the UI back.
                recreate()
                return true
            }
        }
        ui.loadUrl("file:///android_asset/ui/index.html")
        controller.start()
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    /** Files and text shared to Relay from other apps become composer attachments. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE) {
            controller.onShareIntent(intent)
            setIntent(Intent(this, MainActivity::class.java))
        }
    }

    private val results = HashMap<Int, (Int, Intent?) -> Unit>()
    private var nextRequest = 100

    fun startForResult(intent: Intent, cb: (Int, Intent?) -> Unit) {
        val code = nextRequest++
        results[code] = cb
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, code)
        } catch (e: Exception) {
            results.remove(code)
            cb(RESULT_CANCELED, null)
        }
    }

    @Deprecated("Activity Result API needs androidx.activity; the platform callback is enough here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        results.remove(requestCode)?.invoke(resultCode, data)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::controller.isInitialized) controller.onFocus(hasFocus)
    }

    private inner class UiBridge {
        @JavascriptInterface
        fun post(json: String) = controller.onUiMessage(json)
    }

    private fun applyInsets(root: View) {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
                WindowInsets.CONSUMED
            }
        } else {
            root.fitsSystemWindows = true
        }
    }

    private fun buildSiteBar(): LinearLayout {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(10), px(8), px(6), px(8))
            setBackgroundColor(Color.parseColor("#12141C"))
        }
        val back = TextView(this).apply {
            text = "‹  Relay"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(px(14), px(8), px(16), px(8))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#7C5CFF"), Color.parseColor("#22D3EE")),
            ).apply { cornerRadius = px(20).toFloat() }
            setOnClickListener { controller.closeSite() }
        }
        siteTitle = TextView(this).apply {
            setTextColor(Color.parseColor("#C9CCD8"))
            textSize = 13f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(px(12), 0, px(8), 0)
        }
        val reload = TextView(this).apply {
            text = "⟳"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(px(12), px(2), px(12), px(2))
            setOnClickListener { controller.reloadSite() }
        }
        bar.addView(back, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        bar.addView(siteTitle, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(reload, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        return bar
    }

    fun setSiteVisible(visible: Boolean, title: String, highlight: Boolean) {
        siteBar.visibility = if (visible) View.VISIBLE else View.GONE
        siteTitle.text = title
        siteTitle.setTextColor(Color.parseColor(if (highlight) "#C4B5FD" else "#C9CCD8"))
        ui.visibility = if (visible) View.INVISIBLE else View.VISIBLE
        if (!visible) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(ui.windowToken, 0)
        }
    }

    @Deprecated("Still delivered while enableOnBackInvokedCallback is not set")
    override fun onBackPressed() {
        if (!controller.onBack()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::controller.isInitialized) controller.refreshDevice()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        controller.refreshDevice()
    }

    override fun onDestroy() {
        controller.destroy()
        ui.destroy()
        super.onDestroy()
    }

    companion object {
        val BG = Color.parseColor("#0A0B10")
    }
}
