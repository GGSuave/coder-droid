package dev.codedroid.java

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Ponte entre a interface (HTML/JS dentro da WebView) e o app, sem nenhuma porta de rede.
 * JS -> Kotlin: {id, method, params}. Kotlin -> JS: {id, result|error} ou {event, data}.
 * So aceita mensagens da origem dos assets do app (https://appassets.androidplatform.net).
 */
class IdeBridge(private val activity: Activity, private val env: Env) {

    companion object {
        const val NAME = "ideBridge"
        const val ORIGIN = "https://appassets.androidplatform.net"
        private const val MAX_CLIP = 5_000_000
    }

    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newCachedThreadPool()
    private val files = FileApi(env)
    @Volatile private var proxy: JavaScriptReplyProxy? = null

    private val jobs = JobManager(env) { event, data -> emit(event, data) }
    private val lsp = LspSession(
        env,
        onMessage = { body -> emit("lsp.msg", JSONObject().put("json", body)) },
        onExit = { code -> emit("lsp.exit", JSONObject().put("code", code)) },
    )

    fun install(web: WebView): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false
        WebViewCompat.addWebMessageListener(web, NAME, setOf(ORIGIN)) { _, message, _, _, reply ->
            val raw = message.data ?: return@addWebMessageListener
            proxy = reply
            pool.execute { handle(raw) }
        }
        return true
    }

    fun shutdown() {
        jobs.killAll()
        lsp.stop()
    }

    // ------------------------------------------------------------------ transporte

    fun emit(event: String, data: JSONObject) {
        val msg = JSONObject().put("event", event).put("data", data).toString()
        ui.post { proxy?.postMessage(msg) }
    }

    private fun respond(id: Int, result: Any?, error: String?) {
        val msg = JSONObject().put("id", id)
        if (error != null) msg.put("error", error) else msg.put("result", result ?: JSONObject.NULL)
        val s = msg.toString()
        ui.post { proxy?.postMessage(s) }
    }

    private fun handle(raw: String) {
        val req = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val id = req.optInt("id", -1)
        try {
            val result = dispatch(req.optString("method"), req.optJSONObject("params") ?: JSONObject())
            if (id >= 0) respond(id, result, null)
        } catch (e: Throwable) {
            if (id >= 0) respond(id, null, e.message ?: e.javaClass.simpleName)
        }
    }

    // ------------------------------------------------------------------ metodos

    private fun dispatch(method: String, p: JSONObject): Any? = when (method) {
        "fs.list" -> files.list(p.getString("path"))
        "fs.read" -> files.read(p.getString("path"))
        "fs.write" -> { files.write(p.getString("path"), p.getString("text")); true }
        "fs.mkdirs" -> { files.mkdirs(p.getString("path")); true }
        "fs.exists" -> files.exists(p.getString("path"))
        "fs.rename" -> { files.rename(p.getString("from"), p.getString("to")); true }
        "fs.delete" -> { files.delete(p.getString("path")); true }

        "proc.run" -> jobs.run(p.getString("cmd"), p.optString("cwd", "/root"))
        "proc.stdin" -> { jobs.stdin(p.getInt("id"), p.getString("text")); true }
        "proc.kill" -> { jobs.kill(p.getInt("id")); true }

        "lsp.start" -> { lsp.start(p.getString("root")); true }
        "lsp.send" -> { lsp.send(p.getString("json")); true }
        "lsp.stop" -> { lsp.stop(); true }

        "clip.read" -> clipboardRead()
        "clip.write" -> { clipboardWrite(p.getString("text")); true }

        "sys.info" -> JSONObject()
            .put("projects", Env.PROJECTS)
            .put("abi", android.os.Build.SUPPORTED_64_BIT_ABIS.firstOrNull() ?: "?")
            .put("lspRunning", lsp.running)
        "sys.openUrl" -> { openUrl(p.getString("url")); true }
        "sys.stopAll" -> { shutdown(); true }

        else -> error("metodo desconhecido: $method")
    }

    // ------------------------------------------------------------------ clipboard / links

    private fun clipboardRead(): String {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0).coerceToText(activity)?.toString().orEmpty().take(MAX_CLIP)
    }

    private fun clipboardWrite(text: String) {
        ui.post {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("CodeDroid Java", text.take(MAX_CLIP)))
        }
    }

    private fun openUrl(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme !in setOf("http", "https")) return
        val view = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        ui.post {
            try {
                activity.startActivity(Intent(view).setPackage("com.android.chrome"))
            } catch (_: android.content.ActivityNotFoundException) {
                runCatching { activity.startActivity(view) }
            }
        }
    }
}
