package dev.codedroid

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var env: Env
    private lateinit var web: WebView
    private lateinit var overlay: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var keyBar: HorizontalScrollView
    private val ui = Handler(Looper.getMainLooper())

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    @Volatile private var booting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        env = Env(this)
        buildUi()
        boot()
    }

    // ------------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
        }

        val stack = FrameLayout(this)
        root.addView(stack, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        webStack = stack
        web = createWebView().apply { visibility = View.INVISIBLE }
        stack.addView(web, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

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
            text = "CodeDroid"
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

        keyBar = buildKeyBar().apply { visibility = View.GONE }
        root.addView(keyBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(root)

        // Detecta o teclado virtual: com adjustResize, a area visivel encolhe quando ele abre.
        val decor = window.decorView
        decor.viewTreeObserver.addOnGlobalLayoutListener {
            val r = android.graphics.Rect()
            decor.getWindowVisibleDisplayFrame(r)
            val visible = decor.rootView.height - r.bottom > dp(100)
            if (visible != softKeyboardVisible) {
                softKeyboardVisible = visible
                updateKeyBar()
            }
        }
    }

    // ------------------------------------------------- barra de teclas (so no teclado virtual)

    private var softKeyboardVisible = false
    private var editorLoaded = false

    /** Teclado fisico = dispositivo alfabetico, nao virtual (Bluetooth/USB/capa-teclado). */
    private var hwOverrideForTest: Boolean? = null // so e usado em builds debug

    private fun hasHardwareKeyboard(): Boolean =
        (if (isDebuggable()) hwOverrideForTest else null) ?:
        android.view.InputDevice.getDeviceIds().any { id ->
            val d = android.view.InputDevice.getDevice(id) ?: return@any false
            !d.isVirtual &&
                d.keyboardType == android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
                (d.sources and android.view.InputDevice.SOURCE_KEYBOARD) == android.view.InputDevice.SOURCE_KEYBOARD
        }

    private fun updateKeyBar() {
        val hw = hasHardwareKeyboard()
        val show = editorLoaded && softKeyboardVisible && !hw
        keyBar.visibility = if (show) View.VISIBLE else View.GONE
        if (isDebuggable()) {
            android.util.Log.i("codedroid-web", "CD:keybar show=$show soft=$softKeyboardVisible hw=$hw")
        }
    }

    private val inputListener = object : android.hardware.input.InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) = updateKeyBar()
        override fun onInputDeviceRemoved(id: Int) = updateKeyBar()
        override fun onInputDeviceChanged(id: Int) = updateKeyBar()
    }

    override fun onResume() {
        super.onResume()
        (getSystemService(INPUT_SERVICE) as android.hardware.input.InputManager)
            .registerInputDeviceListener(inputListener, ui)
        updateKeyBar()
    }

    override fun onPause() {
        (getSystemService(INPUT_SERVICE) as android.hardware.input.InputManager)
            .unregisterInputDeviceListener(inputListener)
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        updateKeyBar() // teclado fisico conectado/removido tambem muda a configuracao
    }

    // ------------------------------------------------------------------ WebView

    private lateinit var webStack: FrameLayout
    private var currentUrl: String? = null
    private val renderGoneAt = ArrayDeque<Long>()

    private fun createWebView(): WebView {
        val v = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                allowFileAccess = false
                setSupportZoom(false)
                textZoom = 100
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView, request: android.webkit.WebResourceRequest,
                ): Boolean {
                    // Iframes (previews, Simple Browser) continuam dentro do editor.
                    if (!request.isForMainFrame) return false
                    val target = externalTarget(request.url) ?: return false
                    openInChrome(target)
                    return true
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    if (!url.startsWith(env.url)) return
                    currentUrl = url
                    // Lembra a pasta/workspace aberto para reabrir exatamente nele.
                    if ("folder=" in url || "workspace=" in url) {
                        getSharedPreferences("app", MODE_PRIVATE).edit().putString("last_url", url).apply()
                    }
                }

                /**
                 * O Android mata o processo de renderizacao da WebView quando falta memoria (comum
                 * com o app em segundo plano, por exemplo no Chrome). Sem este tratamento o sistema
                 * derruba o app inteiro, e com ele o servidor e os terminais.
                 */
                override fun onRenderProcessGone(
                    view: WebView, detail: android.webkit.RenderProcessGoneDetail,
                ): Boolean {
                    android.util.Log.w("codedroid-web", "renderizador morreu (crash=${detail.didCrash()}); recriando")
                    recreateWebView()
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    // Somente em builds debug: linhas "CD:" viram logcat (usado nos testes).
                    if (isDebuggable() && m.message().startsWith("CD:")) {
                        android.util.Log.i("codedroid-web", m.message())
                    }
                    return super.onConsoleMessage(m)
                }

                override fun onShowFileChooser(
                    view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams,
                ): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback
                    return try {
                        startActivityForResult(params.createIntent(), REQ_FILE)
                        true
                    } catch (e: Exception) {
                        fileCallback = null
                        false
                    }
                }
            }
        }
        ClipboardBridge.install(this, v, env.url)
        return v
    }

    /** Descarta a WebView morta e abre outra na mesma pagina. O servidor nao e tocado. */
    private fun recreateWebView() {
        val now = System.currentTimeMillis()
        renderGoneAt.addLast(now)
        while (renderGoneAt.isNotEmpty() && now - renderGoneAt.first() > 30_000) renderGoneAt.removeFirst()
        if (renderGoneAt.size > 4) { // evita laco infinito se o WebView estiver quebrado
            showError("O WebView do sistema falhou repetidamente.\nAtualize o \"Android System WebView\" na Play Store.")
            overlay.visibility = View.VISIBLE
            return
        }

        val url = currentUrl ?: initialUrl()
        val old = web
        (old.parent as? ViewGroup)?.removeView(old)
        old.destroy()

        web = createWebView()
        webStack.addView(web, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        web.loadUrl(url)
        web.requestFocus()
    }

    /** Ultima pasta aberta (se houver); senao o workspace padrao. */
    private fun initialUrl(): String {
        val saved = getSharedPreferences("app", MODE_PRIVATE).getString("last_url", null)
        return if (saved != null && saved.startsWith(env.url)) saved
        else "${env.url}/?folder=${Env.WORKSPACE}"
    }

    private fun buildKeyBar(): HorizontalScrollView {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun key(label: String, code: Int, ctrl: Boolean = false, shift: Boolean = false) {
            row.addView(
                Button(this).apply {
                    text = label
                    isAllCaps = false
                    textSize = 13f
                    minWidth = dp(44); minimumWidth = dp(44)
                    minHeight = dp(40); minimumHeight = dp(40)
                    setPadding(dp(8), 0, dp(8), 0)
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#2D2D30"))
                    setOnClickListener { sendKey(code, ctrl, shift) }
                },
                LinearLayout.LayoutParams(WRAP_CONTENT, dp(40)).apply { setMargins(dp(1), dp(1), dp(1), dp(1)) },
            )
        }
        // Ctrl+Insert / Shift+Insert: copiar/colar que o VS Code entende no editor e no terminal.
        key("Copiar", KeyEvent.KEYCODE_INSERT, ctrl = true)
        key("Colar", KeyEvent.KEYCODE_INSERT, shift = true)
        key("Esc", KeyEvent.KEYCODE_ESCAPE)
        key("Tab", KeyEvent.KEYCODE_TAB)
        key("^C", KeyEvent.KEYCODE_C, ctrl = true)
        key("^D", KeyEvent.KEYCODE_D, ctrl = true)
        key("^L", KeyEvent.KEYCODE_L, ctrl = true)
        key("←", KeyEvent.KEYCODE_DPAD_LEFT)
        key("↑", KeyEvent.KEYCODE_DPAD_UP)
        key("↓", KeyEvent.KEYCODE_DPAD_DOWN)
        key("→", KeyEvent.KEYCODE_DPAD_RIGHT)
        key("Home", KeyEvent.KEYCODE_MOVE_HOME)
        key("End", KeyEvent.KEYCODE_MOVE_END)
        key("PgUp", KeyEvent.KEYCODE_PAGE_UP)
        key("PgDn", KeyEvent.KEYCODE_PAGE_DOWN)
        return HorizontalScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#252526"))
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun sendKey(code: Int, ctrl: Boolean, shift: Boolean = false) {
        var meta = 0
        if (ctrl) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (shift) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val now = android.os.SystemClock.uptimeMillis()
        web.requestFocus()
        web.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
        web.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
    }

    private fun setStatus(msg: String, pct: Int? = null) = ui.post {
        statusText.text = msg
        if (pct == null) {
            progress.isIndeterminate = true
        } else {
            progress.isIndeterminate = false
            progress.progress = pct
        }
    }

    private fun showError(msg: String) = ui.post {
        progress.visibility = View.GONE
        statusText.text = msg
    }

    // ------------------------------------------------------------------ links

    /**
     * Decide se uma navegacao deve sair do app. Devolve a URL final (ou null = fica no editor).
     *
     * O VS Code reescreve links de localhost:PORTA para <editor>/proxy/PORTA/... . Como o proot usa
     * a rede do aparelho, o Chrome alcanca o servidor direto em http://localhost:PORTA/...
     */
    private fun externalTarget(uri: Uri): Uri? {
        val isEditor = uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == Env.PORT
        if (!isEditor) {
            // about:, blob:, data:, javascript: sao internos da pagina e ficam onde estao
            return if (uri.scheme in setOf("http", "https", "mailto", "tel")) uri else null
        }

        val m = Regex("^/(?:abs)?proxy/(\\d{1,5})(/.*)?$").find(uri.encodedPath ?: "") ?: return null
        val port = m.groupValues[1]
        val rest = m.groupValues[2].ifEmpty { "/" }
        val query = uri.encodedQuery?.let { "?$it" } ?: ""
        val fragment = uri.encodedFragment?.let { "#$it" } ?: ""
        return Uri.parse("http://localhost:$port$rest$query$fragment")
    }

    /** Abre no Chrome; se ele nao estiver instalado, usa o navegador padrao. */
    private fun openInChrome(uri: Uri) {
        val view = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            startActivity(Intent(view).setPackage("com.android.chrome"))
        } catch (_: android.content.ActivityNotFoundException) {
            runCatching { startActivity(view) }
        }
    }

    // ----------------------------------------------------------------- boot

    private fun boot() {
        if (booting) return
        booting = true
        thread(name = "codedroid-boot") {
            try {
                if (!Installer.isInstalled(env)) {
                    Installer.install(applicationContext, env) { m, p -> setStatus(m, p) }
                }
                Installer.ensureExtras(applicationContext, env) { m, p -> setStatus(m, p) }
                setStatus("Iniciando servidor…")
                if (!ServerService.isRunning()) ServerService.start(applicationContext)
                waitForServer()
                ui.post {
                    web.loadUrl(initialUrl())
                    web.visibility = View.VISIBLE
                    overlay.visibility = View.GONE
                    editorLoaded = true
                    updateKeyBar()
                    web.requestFocus()
                    askBatteryExemption()
                }
            } catch (e: Throwable) {
                showError("Erro: ${e.message ?: e.javaClass.simpleName}\n\n${logTail()}")
            } finally {
                booting = false
            }
        }
    }

    private fun waitForServer() {
        val deadline = System.currentTimeMillis() + 240_000
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            ServerService.lastError?.let { throw IllegalStateException(it) }
            val p = ServerService.process
            if (p != null && !p.isAlive) {
                throw IllegalStateException("O servidor encerrou (código ${p.exitValue()})")
            }
            if (isHealthy()) return
            val secs = (System.currentTimeMillis() - started) / 1000
            setStatus("Iniciando servidor… (${secs}s)")
            Thread.sleep(700)
        }
        throw IllegalStateException("Tempo esgotado esperando o servidor")
    }

    private fun isHealthy(): Boolean = try {
        (URL("${env.url}/healthz").openConnection() as HttpURLConnection).run {
            connectTimeout = 800
            readTimeout = 1500
            responseCode in 200..299
        }
    } catch (_: Exception) {
        false
    }

    private fun logTail(): String = runCatching {
        env.log.readLines().takeLast(25).joinToString("\n")
    }.getOrDefault("")

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

    // ------------------------------------------------------------ lifecycle

    private fun isDebuggable() =
        (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        debugHook(intent)
    }

    /** Ganchos de teste via `adb shell am start --es ...`; ignorados em builds release. */
    private fun debugHook(i: Intent) {
        if (!isDebuggable()) return
        i.getStringExtra("cb_set")?.let {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("test", it))
        }
        if (i.getBooleanExtra("cb_get", false)) {
            android.util.Log.i("codedroid-web", "CD:native-clip=" + ClipboardBridge.readText(this))
        }
        i.getStringExtra("hw")?.let { hwOverrideForTest = it == "1"; updateKeyBar() }
        i.getStringExtra("js")?.let { web.evaluateJavascript(it, null) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Nao fecha o app: o servidor continua vivo em segundo plano.
        moveTaskToBack(true)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        // O servico continua rodando; so soltamos a WebView.
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val REQ_FILE = 1001
    }
}
