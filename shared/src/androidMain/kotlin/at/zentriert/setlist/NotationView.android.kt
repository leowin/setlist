package at.zentriert.setlist

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader

private const val PAGE = "https://appassets.androidplatform.net/assets/alphatab/index.html"

private class Holder {
    var ready = false
    var pendingTex: String? = null
    var pendingScale = 1f
    var lastTex: String? = null
    var lastScale = 1f
    var lastLight = false
    var pendingLight = false
    var lastCursor: Long? = -2L
    var lastMarks: String = ""
    var rendered = false
    var onTap: (Long) -> Unit = {}
}

private fun jsString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\""

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun NotationView(tex: String, cursorTick: Long?, scale: Float, light: Boolean, onTap: (tick: Long) -> Unit, modifier: Modifier, marks: String) {
    val holder = remember { Holder() }
    holder.onTap = onTap
    AndroidView(
        modifier = modifier,
        factory = { context ->
            val loader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                .build()
            WebView(context).apply {
                setBackgroundColor(Color.parseColor("#101010"))
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        loader.shouldInterceptRequest(request.url)
                    override fun onPageFinished(view: WebView, url: String?) {
                        holder.ready = true
                        holder.pendingTex?.let { evaluateJavascript("renderTex(${jsString(it)}, ${holder.pendingScale}, ${holder.pendingLight})", null) }
                    }
                }
                addJavascriptInterface(object {
                    @JavascriptInterface fun onRendered() { holder.rendered = true }
                    @JavascriptInterface fun onError(message: String) { android.util.Log.w("Notation", message) }
                    @JavascriptInterface fun onBeatTapped(tick: Long) { post { holder.onTap(tick) } }
                }, "Android")
                loadUrl(PAGE)
            }
        },
        update = { web ->
            if (tex != holder.lastTex || scale != holder.lastScale || light != holder.lastLight) {
                holder.lastTex = tex; holder.lastScale = scale; holder.lastLight = light; holder.lastCursor = -2L
                if (holder.ready) web.evaluateJavascript("renderTex(${jsString(tex)}, $scale, $light)", null)
                else { holder.pendingTex = tex; holder.pendingScale = scale; holder.pendingLight = light }
            }
            if (cursorTick != holder.lastCursor && holder.ready) {
                holder.lastCursor = cursorTick
                web.evaluateJavascript("setCursor(${cursorTick ?: "null"})", null)
            }
            if (marks != holder.lastMarks && holder.ready) {
                holder.lastMarks = marks
                web.evaluateJavascript("setMarks(${jsString(marks)})", null)
            }
        },
    )
}
