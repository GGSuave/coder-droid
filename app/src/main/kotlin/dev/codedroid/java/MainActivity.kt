package dev.codedroid.java

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.webkit.WebViewAssetLoader
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var env: Env
    private lateinit var bridge: IdeBridge
    private lateinit var stack: FrameLayout
    private lateinit var web: WebView
    private lateinit var overlay: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private val ui = Handler(Looper.getMainLooper())

    private var pageLoaded = false
    private var softKeyboard = false
    private var hwOverrideForTest: Boolean? = null // so e usado em builds debug
    private val renderGoneAt = ArrayDeque<Long>()
    @Volatile private var booting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        env = Env(this)
        bridge = IdeBridge(this, env)
        buildUi()
        boot()
    }

    // ------------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        stack = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#1E1E1E")) }

        web = createWebView()
        stack.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        statusText = TextView(this).apply {
            setTextColor(Color.parseColor("#CCCCCC"))
            textSize = 14f
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        val title = TextView(this).apply {
            text = "CodeDroid Java"
            setTextColor(Color.WHITE)
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(24))
        }
        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(dp(32), 0, dp(32), 0)
            addView(title)
            addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(statusText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        stack.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(stack)

        // Teclado virtual: com adjustResize a area visivel encolhe quando ele abre.
        val decor = window.decorView
        decor.viewTreeObserver.addOnGlobalLayoutListener {
            val r = Rect()
            decor.getWindowVisibleDisplayFrame(r)
            val visible = decor.rootView.height - r.bottom > dp(100)
            if (visible != softKeyboard) {
                softKeyboard = visible
                pushKeyboardState()
            }
        }
    }

    private fun createWebView(): WebView {
        val loaderBuilder = WebViewAssetLoader.Builder()
        if (isDebuggable()) {
            // Builds debug: arquivos em files/webdev/ substituem os do APK (iterar na interface sem recompilar).
            val dev = java.io.File(filesDir, "webdev").apply { mkdirs() }
            val assets = WebViewAssetLoader.AssetsPathHandler(this)
            loaderBuilder.addPathHandler("/assets/web/", DevFirstHandler(dev, assets))
        }
        val loader = loaderBuilder
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        val v = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setSupportZoom(false)
                textZoom = 100
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    if (url.startsWith(IdeBridge.ORIGIN)) return false
                    if (request.isForMainFrame) openExternal(request.url)
                    return true
                }

                override fun onPageFinished(view: WebView, url: String) {
                    pageLoaded = true
                    this@MainActivity.overlay.visibility = View.GONE
                    pushKeyboardState()
                    askBatteryExemption()
                }

                /** O Android mata o renderizador da WebView sob pouca memoria; sem isto o app inteiro cai. */
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    android.util.Log.w("codedroid-java", "renderizador morreu (crash=${detail.didCrash()}); recriando")
                    recreateWebView()
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    if (isDebuggable() && m.message().startsWith("CD:")) {
                        android.util.Log.i("codedroid-web", m.message())
                    }
                    return super.onConsoleMessage(m)
                }
            }
        }
        bridge.install(v)
        return v
    }

    private fun recreateWebView() {
        val now = System.currentTimeMillis()
        renderGoneAt.addLast(now)
        while (renderGoneAt.isNotEmpty() && now - renderGoneAt.first() > 30_000) renderGoneAt.removeFirst()
        if (renderGoneAt.size > 4) {
            statusText.text = "O WebView do sistema falhou repetidamente.\nAtualize o \"Android System WebView\" na Play Store."
            progress.visibility = View.GONE
            overlay.visibility = View.VISIBLE
            return
        }
        val old = web
        (old.parent as? ViewGroup)?.removeView(old)
        old.destroy()
        web = createWebView()
        stack.addView(web, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        web.loadUrl(START_URL)
        web.requestFocus()
    }

    private fun openExternal(uri: Uri) {
        val view = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            startActivity(Intent(view).setPackage("com.android.chrome"))
        } catch (_: android.content.ActivityNotFoundException) {
            runCatching { startActivity(view) }
        }
    }

    // ------------------------------------------------------------------- boot

    private fun setStatus(msg: String, pct: Int? = null) = ui.post {
        statusText.text = msg
        if (pct == null) {
            progress.isIndeterminate = true
        } else {
            progress.isIndeterminate = false
            progress.progress = pct
        }
    }

    private fun boot() {
        if (booting) return
        booting = true
        IdeService.start(applicationContext)
        thread(name = "codedroid-boot") {
            try {
                if (Installer.needsWork(env)) {
                    Installer.ensureAll(applicationContext, env) { m, p -> setStatus(m, p) }
                }
                ui.post { web.loadUrl(START_URL); web.requestFocus() }
            } catch (e: Throwable) {
                android.util.Log.e("codedroid-java", "falha na instalacao", e)
                ui.post {
                    progress.visibility = View.GONE
                    statusText.text = "Erro: ${e.message ?: e.javaClass.simpleName}"
                }
            } finally {
                booting = false
            }
        }
    }

    // ------------------------------------------------------------- teclado

    private fun isDebuggable() =
        (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Teclado fisico = dispositivo alfabetico, nao virtual (Bluetooth/USB/capa-teclado). */
    private fun hasHardwareKeyboard(): Boolean =
        (if (isDebuggable()) hwOverrideForTest else null) ?:
        android.view.InputDevice.getDeviceIds().any { id ->
            val d = android.view.InputDevice.getDevice(id) ?: return@any false
            !d.isVirtual &&
                d.keyboardType == android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
                (d.sources and android.view.InputDevice.SOURCE_KEYBOARD) == android.view.InputDevice.SOURCE_KEYBOARD
        }

    private fun pushKeyboardState() {
        if (!pageLoaded) return
        web.evaluateJavascript("window.ide&&window.ide.setKeyboard($softKeyboard,${hasHardwareKeyboard()})", null)
    }

    private val inputListener = object : android.hardware.input.InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) = pushKeyboardState()
        override fun onInputDeviceRemoved(id: Int) = pushKeyboardState()
        override fun onInputDeviceChanged(id: Int) = pushKeyboardState()
    }

    override fun onResume() {
        super.onResume()
        (getSystemService(INPUT_SERVICE) as android.hardware.input.InputManager)
            .registerInputDeviceListener(inputListener, ui)
        pushKeyboardState()
    }

    override fun onPause() {
        (getSystemService(INPUT_SERVICE) as android.hardware.input.InputManager)
            .unregisterInputDeviceListener(inputListener)
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        pushKeyboardState()
    }

    // ------------------------------------------------------------ ciclo de vida

    private fun askBatteryExemption() {
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        if (prefs.getBoolean("asked_battery", false)) return
        prefs.edit().putBoolean("asked_battery", true).apply()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName")),
                )
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Nao fecha o app: o servidor de linguagem e as compilacoes continuam em segundo plano.
        moveTaskToBack(true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!isDebuggable()) return
        // Ganchos de teste (so builds debug): adb shell am start -n ... --es js '<codigo>' / --es hw 0|1
        intent.getStringExtra("hw")?.let { hwOverrideForTest = it == "1"; pushKeyboardState() }
        intent.getStringExtra("js")?.let { web.evaluateJavascript(it, null) }
    }

    override fun onDestroy() {
        bridge.shutdown()
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    /** So em debug: serve files/webdev/<caminho> se existir; senao cai para os assets do APK. */
    private class DevFirstHandler(
        private val dev: java.io.File,
        private val assets: WebViewAssetLoader.PathHandler,
    ) : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            val f = java.io.File(dev, path)
            if (f.isFile && f.canonicalPath.startsWith(dev.canonicalPath + java.io.File.separator)) {
                val ext = f.extension.lowercase()
                val mime = when (ext) {
                    "js", "mjs" -> "text/javascript"
                    "css" -> "text/css"
                    "html" -> "text/html"
                    else -> android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                }
                return WebResourceResponse(mime, "utf-8", f.inputStream())
            }
            return assets.handle("web/$path")
        }
    }

    companion object {
        private const val START_URL = "${IdeBridge.ORIGIN}/assets/web/index.html"
    }
}
