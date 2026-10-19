package fr.tapissync.garmin

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import fr.tapissync.App
import fr.tapissync.AppLog
import fr.tapissync.ui.Ui
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connexion à Garmin dans la vraie page Garmin (identifiants, code de double
 * authentification, vérifications anti-robot). L'appli ne voit jamais le mot de passe :
 * elle récupère seulement le ticket de connexion (ST-…) à la fin, puis l'échange contre des jetons.
 */
class LoginActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private val handled = AtomicBoolean(false)

    private val ticketInText = Regex("""ticket=(ST-[A-Za-z0-9._\-]+)""")
    private val ticketInJson = Regex(""""serviceTicketId"\s*:\s*"(ST-[^"]+)"""")

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = Ui.vertical(this).apply { setBackgroundColor(Ui.BG) }
        val bar = Ui.horizontal(this).apply {
            val p = Ui.dp(this@LoginActivity, 12)
            setPadding(p, p, p, p)
        }
        val titles = Ui.vertical(this)
        titles.addView(Ui.text(this, "Connexion Garmin", 20f, bold = true))
        status = Ui.text(this, "Saisis tes identifiants Garmin dans la page ci-dessous.", 13f, Ui.MUTED)
        titles.addView(status)
        bar.addView(titles, Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        progress = ProgressBar(this).apply { visibility = View.GONE }
        bar.addView(progress, Ui.lp(Ui.dp(this, 32), Ui.dp(this, 32)))
        root.addView(bar)

        web = WebView(this)
        root.addView(web, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(web, true)
        cookies.removeAllCookies(null)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // La page Garmin peut refuser les WebView : on se présente comme Chrome mobile.
            userAgentString = userAgentString.replace("; wv", "")
        }
        web.addJavascriptInterface(Bridge(), "TapisBridge")
        web.webViewClient = Client()

        AppLog.add("Garmin", "Ouverture de la page de connexion Garmin")
        web.loadUrl(GarminConfig.LOGIN_URL)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun ticket(t: String) = onTicket(t, "page")
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val t = extract(request.url.toString()) ?: return false
            onTicket(t, "redirection")
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val t = extract(request.url.toString()) ?: return null
            onTicket(t, "requête")
            // On bloque la requête : le ticket ne sert qu'une fois, il est pour nous.
            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            injectHooks()
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (handled.get()) return
            injectHooks()
            view.evaluateJavascript("(function(){return document.documentElement.outerHTML})()") { html ->
                val unescaped = html?.replace("\\u003C", "<")?.replace("\\\"", "\"")?.replace("\\/", "/") ?: return@evaluateJavascript
                (ticketInJson.find(unescaped) ?: ticketInText.find(unescaped))?.let { onTicket(it.groupValues[1], "contenu") }
            }
        }
    }

    private fun extract(url: String): String? {
        val decoded = try {
            URLDecoder.decode(url, "UTF-8")
        } catch (_: Exception) {
            url
        }
        return ticketInText.find(decoded)?.groupValues?.get(1)
    }

    /** Intercepte les réponses fetch/XHR de la page pour y lire le ticket. */
    private fun injectHooks() {
        val js = """
            (function(){
              if (window.__tapisHooked) return; window.__tapisHooked = true;
              function scan(t){ try { if(!t) return; t = String(t);
                var m = t.match(/"serviceTicketId"\s*:\s*"(ST-[^"]+)"/) || t.match(/ticket=(ST-[A-Za-z0-9._\-]+)/);
                if (m) TapisBridge.ticket(m[1]); } catch(e){} }
              if (window.fetch) { var of = window.fetch; window.fetch = function(){
                return of.apply(this, arguments).then(function(r){ try { r.clone().text().then(scan); } catch(e){} return r; }); }; }
              var os = XMLHttpRequest.prototype.send;
              XMLHttpRequest.prototype.send = function(){ var x = this;
                x.addEventListener('load', function(){ try { scan(x.responseText); } catch(e){} });
                return os.apply(this, arguments); };
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    private fun onTicket(ticket: String, source: String) {
        if (!handled.compareAndSet(false, true)) return
        AppLog.add("Garmin", "Ticket reçu (via $source)")
        runOnUiThread {
            web.stopLoading()
            web.visibility = View.INVISIBLE
            progress.visibility = View.VISIBLE
            status.text = "Connexion réussie, récupération des droits d'accès…"
        }
        val app = App.instance
        app.io.execute {
            try {
                app.garmin.exchangeTicket(ticket, GarminConfig.MOBILE_SERVICE)
                val name = try {
                    app.garmin.profileName()
                } catch (_: Exception) {
                    null
                }
                app.prefs.garminDisplayName = name
                runOnUiThread {
                    setResult(RESULT_OK)
                    finish()
                }
            } catch (e: Exception) {
                AppLog.add("Garmin", "Échec de l'échange : ${e.message}")
                runOnUiThread {
                    progress.visibility = View.GONE
                    web.visibility = View.VISIBLE
                    status.text = "Échec : ${e.message}\nRéessaie de te connecter."
                    status.setTextColor(Ui.ERROR)
                    handled.set(false)
                    CookieManager.getInstance().removeAllCookies(null)
                    web.loadUrl(GarminConfig.LOGIN_URL)
                }
            }
        }
    }

    @Deprecated("Remplacé par OnBackPressedDispatcher dans AndroidX, inutile ici")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack() && !handled.get()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }
}
