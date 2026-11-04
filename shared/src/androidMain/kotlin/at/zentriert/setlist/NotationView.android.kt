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
    var lastCursor: Long? = -2L
    var rendered = false
}

private fun jsString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\""

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun NotationView(tex: String, cursorTick: Long?, scale: Float, modifier: Modifier) {
    val holder = remember { Holder() }
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
                        holder.pendingTex?.let { evaluateJavascript("renderTex(${jsString(it)}, ${holder.pendingScale})", null) }
                    }
                }
                addJavascriptInterface(object {
                    @JavascriptInterface fun onRendered() { holder.rendered = true }
                    @JavascriptInterface fun onError(message: String) { android.util.Log.w("Notation", message) }
                }, "Android")
                loadUrl(PAGE)
            }
        },
        update = { web ->
            if (tex != holder.lastTex || scale != holder.lastScale) {
                holder.lastTex = tex; holder.lastScale = scale; holder.lastCursor = -2L
                if (holder.ready) web.evaluateJavascript("renderTex(${jsString(tex)}, $scale)", null)
                else { holder.pendingTex = tex; holder.pendingScale = scale }
            }
            if (cursorTick != holder.lastCursor && holder.ready) {
                holder.lastCursor = cursorTick
                web.evaluateJavascript("setCursor(${cursorTick ?: "null"})", null)
            }
        },
    )
}
