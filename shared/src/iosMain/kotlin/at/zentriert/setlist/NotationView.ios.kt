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
    var lastLight = false
    var pendingLight = false
    var lastCursor: Long? = -2L
    var onTap: (Long) -> Unit = {}
}

private fun jsString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\""

/** Loads web/alphatab/index.html from the app bundle (add the `alphatab` folder as a bundle resource). */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun NotationView(tex: String, cursorTick: Long?, scale: Float, light: Boolean, onTap: (tick: Long) -> Unit, modifier: Modifier) {
    val holder = remember { Holder() }
    holder.onTap = onTap
    val delegate = remember {
        object : NSObject(), WKNavigationDelegateProtocol {
            override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
                holder.ready = true
                holder.pendingTex?.let { webView.evaluateJavaScript("renderTex(${jsString(it)}, ${holder.pendingScale}, ${holder.pendingLight})", null) }
            }
        }
    }
    val handler = remember {
        object : NSObject(), platform.WebKit.WKScriptMessageHandlerProtocol {
            override fun userContentController(userContentController: platform.WebKit.WKUserContentController, didReceiveScriptMessage: platform.WebKit.WKScriptMessage) {
                val body = didReceiveScriptMessage.body.toString()
                if (body.startsWith("tap:")) body.removePrefix("tap:").toLongOrNull()?.let(holder.onTap)
            }
        }
    }
    UIKitView(
        factory = {
            val config = WKWebViewConfiguration()
            config.userContentController.addScriptMessageHandler(handler, "setlist")
            val web = WKWebView(frame = platform.CoreGraphics.CGRectZero.readValue(), configuration = config)
            web.navigationDelegate = delegate
            web.opaque = false
            val url = NSBundle.mainBundle.URLForResource("index", "html", "alphatab")
            if (url != null) web.loadFileURL(url, allowingReadAccessToURL = url.URLByDeletingLastPathComponent ?: url)
            web
        },
        modifier = modifier,
        update = { web ->
            if (tex != holder.lastTex || scale != holder.lastScale || light != holder.lastLight) {
                holder.lastTex = tex; holder.lastScale = scale; holder.lastLight = light; holder.lastCursor = -2L
                if (holder.ready) web.evaluateJavaScript("renderTex(${jsString(tex)}, $scale, $light)", null)
                else { holder.pendingTex = tex; holder.pendingScale = scale; holder.pendingLight = light }
            }
            if (cursorTick != holder.lastCursor && holder.ready) {
                holder.lastCursor = cursorTick
                web.evaluateJavaScript("setCursor(${cursorTick ?: "null"})", null)
            }
        },
    )
}
