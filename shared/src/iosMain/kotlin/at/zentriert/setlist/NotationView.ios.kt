package at.zentriert.setlist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSBundle
import platform.Foundation.NSURL
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

private class Holder {
    var ready = false
    var pendingTex: String? = null
    var pendingScale = 1f
    var lastTex: String? = null
    var lastScale = 1f
    var lastCursor: Long? = -2L
}

private fun jsString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\""

/** Loads web/alphatab/index.html from the app bundle (add the `alphatab` folder as a bundle resource). */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun NotationView(tex: String, cursorTick: Long?, scale: Float, modifier: Modifier) {
    val holder = remember { Holder() }
    val delegate = remember {
        object : NSObject(), WKNavigationDelegateProtocol {
            override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
                holder.ready = true
                holder.pendingTex?.let { webView.evaluateJavaScript("renderTex(${jsString(it)}, ${holder.pendingScale})", null) }
            }
        }
    }
    UIKitView(
        factory = {
            val web = WKWebView(frame = platform.CoreGraphics.CGRectZero.readValue(), configuration = WKWebViewConfiguration())
            web.navigationDelegate = delegate
            web.opaque = false
            val url = NSBundle.mainBundle.URLForResource("index", "html", "alphatab")
            if (url != null) web.loadFileURL(url, allowingReadAccessToURL = url.URLByDeletingLastPathComponent ?: url)
            web
        },
        modifier = modifier,
        update = { web ->
            if (tex != holder.lastTex || scale != holder.lastScale) {
                holder.lastTex = tex; holder.lastScale = scale; holder.lastCursor = -2L
                if (holder.ready) web.evaluateJavaScript("renderTex(${jsString(tex)}, $scale)", null)
                else { holder.pendingTex = tex; holder.pendingScale = scale }
            }
            if (cursorTick != holder.lastCursor && holder.ready) {
                holder.lastCursor = cursorTick
                web.evaluateJavaScript("setCursor(${cursorTick ?: "null"})", null)
            }
        },
    )
}
