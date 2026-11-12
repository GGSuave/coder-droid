package dev.codedroid

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * A WebView do Android nao oferece permissao para navigator.clipboard.readText(), entao o
 * "Colar" do VS Code (menu de contexto, paleta, atalhos) falha. Esta ponte substitui
 * navigator.clipboard por uma implementacao que fala com o ClipboardManager nativo.
 *
 * Seguranca: o listener so aceita mensagens da origem do editor (http://127.0.0.1:PORTA).
 * Iframes sandbox (previews/webviews de extensoes) tem origem opaca e ficam de fora.
 */
object ClipboardBridge {
    private const val NAME = "codedroidClipboard"
    private const val MAX_CHARS = 5_000_000

    /** @return false se este WebView e antigo demais (mantem o comportamento padrao). */
    fun install(ctx: Context, web: WebView, origin: String): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) return false

        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val origins = setOf(origin)

        WebViewCompat.addWebMessageListener(web, NAME, origins) { _, message, _, _, reply ->
            val raw = message.data ?: return@addWebMessageListener
            handle(ctx, cm, raw, reply)
        }
        WebViewCompat.addDocumentStartJavaScript(web, SCRIPT, origins)
        return true
    }

    fun readText(ctx: Context): String {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0).coerceToText(ctx)?.toString().orEmpty()
    }

    private fun handle(ctx: Context, cm: ClipboardManager, raw: String, reply: JavaScriptReplyProxy) {
        val req = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val id = req.optInt("id")
        val out = JSONObject().put("id", id)
        when (req.optString("op")) {
            "read" -> out.put("text", readText(ctx).take(MAX_CHARS))
            "write" -> {
                cm.setPrimaryClip(ClipData.newPlainText("CodeDroid", req.optString("text").take(MAX_CHARS)))
                out.put("text", "")
            }
            else -> out.put("text", "")
        }
        reply.postMessage(out.toString())
    }

    // Executado antes de qualquer script da pagina, apenas na origem permitida.
    private val SCRIPT = """
        (function () {
          if (window.__codedroidClipboard) return;
          var bridge = window.$NAME;
          if (!bridge) return;
          window.__codedroidClipboard = true;

          var pending = {}, seq = 0;
          bridge.onmessage = function (e) {
            var m; try { m = JSON.parse(e.data); } catch (x) { return; }
            var done = pending[m.id];
            if (done) { delete pending[m.id]; done(m.text || ''); }
          };
          function call(op, text) {
            return new Promise(function (resolve) {
              var id = ++seq;
              pending[id] = resolve;
              bridge.postMessage(JSON.stringify({ id: id, op: op, text: text }));
            });
          }

          var clip = {
            readText: function () { return call('read'); },
            writeText: function (t) { return call('write', String(t)).then(function () {}); },
            read: function () {
              return call('read').then(function (t) {
                return [new ClipboardItem({ 'text/plain': new Blob([t], { type: 'text/plain' }) })];
              });
            },
            write: function (items) {
              var chain = Promise.resolve();
              Array.prototype.forEach.call(items, function (item) {
                if (item.types && item.types.indexOf('text/plain') >= 0) {
                  chain = chain.then(function () { return item.getType('text/plain'); })
                               .then(function (b) { return b.text(); })
                               .then(function (t) { return call('write', t); });
                }
              });
              return chain.then(function () {});
            },
            addEventListener: function () {},
            removeEventListener: function () {},
            dispatchEvent: function () { return false; }
          };
          try {
            Object.defineProperty(Navigator.prototype, 'clipboard', { get: function () { return clip; }, configurable: true });
          } catch (e) {
            try { Object.defineProperty(navigator, 'clipboard', { value: clip, configurable: true }); } catch (e2) {}
          }
        })();
    """.trimIndent().replace("\$NAME", NAME)
}
