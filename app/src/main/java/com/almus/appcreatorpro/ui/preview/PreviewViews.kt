package com.almus.appcreatorpro.ui.preview

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewRenderProcess
import android.webkit.WebViewRenderProcessClient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.almus.appcreatorpro.preview.Cutout
import com.almus.appcreatorpro.preview.LaidOut
import com.almus.appcreatorpro.preview.NodeType
import com.almus.appcreatorpro.preview.PickEvent
import com.almus.appcreatorpro.preview.PreviewConfig
import com.almus.appcreatorpro.preview.PreviewPalette
import com.almus.appcreatorpro.preview.PreviewRequestPolicy
import com.almus.appcreatorpro.preview.PreviewSession
import com.almus.appcreatorpro.preview.WebBundle
import java.io.ByteArrayInputStream

// ------------------------------------------------------------------------------------------------ device frame

/** A plain drawn device: bezel, status bar with a cut-out, navigation pill. No manufacturer artwork. Content gets widthDp x contentHeightDp. */
@Composable
fun DeviceFrame(config: PreviewConfig, content: @Composable BoxScope.() -> Unit) {
    val d = config.device
    val w = config.widthDp
    val contentH = config.contentHeightDp
    if (!config.showFrame) {
        Box(Modifier.size(w.dp, config.heightDp.dp).clipToBounds(), content = content)
        return
    }
    val bar = if (config.dark) Color(0xFF000000) else Color(0xFFF2F2F2)
    val ink = if (config.dark) Color(0xFFDDDDDD) else Color(0xFF333333)
    val screenShape = RoundedCornerShape(d.cornerDp.dp)
    val statusH = d.statusBarDp
    val navH = d.navBarDp
    Box(Modifier.background(Color(0xFF1A1A1A), RoundedCornerShape((d.cornerDp + 10).dp)).padding(10.dp)) {
        Column(Modifier.size(w.dp, config.heightDp.dp).clip(screenShape)) {
            Box(Modifier.fillMaxWidth().height(statusH.dp).background(bar)) {
                if (statusH > 0) {
                    Text("12:00", color = ink, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterStart).padding(start = 20.dp))
                    when (d.cutout) {
                        Cutout.PUNCH_HOLE -> Box(Modifier.align(Alignment.Center).size(10.dp).background(Color.Black, RoundedCornerShape(5.dp)))
                        Cutout.NOTCH -> Box(Modifier.align(Alignment.TopCenter).size(110.dp, (statusH - 2).coerceAtLeast(8).dp).background(Color.Black, RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)))
                        Cutout.NONE -> {}
                    }
                    Box(Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).size(22.dp, 9.dp).border(1.dp, ink, RoundedCornerShape(2.dp)))
                }
            }
            Box(Modifier.fillMaxWidth().height(contentH.dp).clipToBounds(), content = content)
            Box(Modifier.fillMaxWidth().height(navH.dp).background(bar)) {
                if (navH > 0) Box(Modifier.align(Alignment.Center).size(96.dp, 4.dp).background(ink.copy(alpha = 0.6f), RoundedCornerShape(2.dp)))
            }
        }
    }
}

/**
 * Shows [content] at its real dp size (so layout, media queries and text behave as at that size), then scales the
 * drawn result down (never up unless the user zooms) to fit the space.
 */
@Composable
fun FitToWidth(widthDp: Float, heightDp: Float, availableWidthDp: Float, zoomPercent: Int?, content: @Composable () -> Unit) {
    val density = LocalDensity.current.density
    val fit = (availableWidthDp / widthDp).coerceAtMost(1f).coerceAtLeast(0.1f)
    val scale = zoomPercent?.let { it / 100f } ?: fit
    val wPx = (widthDp * density).toInt().coerceAtLeast(1)
    val hPx = (heightDp * density).toInt().coerceAtLeast(1)
    Box(
        Modifier.layout { measurable, _ ->
            val p = measurable.measure(Constraints.fixed(wPx, hPx))
            layout((wPx * scale).toInt(), (hPx * scale).toInt()) {
                p.placeWithLayer(0, 0) { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f) }
            }
        },
    ) { content() }
}

// ------------------------------------------------------------------------------------------------ sandboxed web view

/** The files the intercepting client may serve. Written on the UI thread, read on the WebView's network thread. */
internal class SandboxHolder {
    @Volatile var bundle: WebBundle? = null
    @Volatile var allowInternet: Boolean = false
}

/**
 * The ONLY place that creates a WebView for previews. Isolation, layer by layer:
 *  1. No JavaScript bridge (`addJavascriptInterface` is never called): the page cannot call into the app.
 *  2. Every request passes [PreviewRequestPolicy]: only the in-memory project files on a virtual `.invalid` origin work;
 *     file:, content:, intent: and every other scheme are blocked, and so is the whole internet unless the user turned it on.
 *  3. A Content-Security-Policy in the page and in each response; WebRTC is removed by the injected script.
 *  4. File, content and universal access from file URLs are all off; no cookies; no geolocation; permission requests,
 *     file choosers, new windows and downloads are denied; alert/confirm/prompt never block.
 *  5. The WebView is created fresh for every refresh and destroyed afterwards (storage and cookies cleared), so a runaway
 *     or leftover page state never survives; an unresponsive page is terminated.
 * Not covered: the WebView runs inside the app's process (its renderer is a separate, sandboxed Chromium process on
 * modern Android, but there is no separate Android user or process for it), and a name that resolves to a private
 * address (DNS rebinding) is not stopped when the internet is on.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SandboxedWebView(bundle: WebBundle, version: Int, config: PreviewConfig, inspect: Boolean, session: PreviewSession, modifier: Modifier = Modifier) {
    val holder = remember { SandboxHolder() }
    holder.bundle = bundle
    holder.allowInternet = config.allowInternet
    val context = LocalContext.current
    val webView = remember(version, config.fontScale, config.allowInternet) { createSandboxWebView(context, holder, session, config) }
    DisposableEffect(webView) { onDispose { destroySandbox(webView) } }
    LaunchedEffect(webView, inspect) { webView.evaluateJavascript("window.__almusInspect=$inspect;", null) }
    // A new view is composed for every new WebView; reusing the old AndroidView would keep the destroyed one.
    key(webView) { AndroidView(factory = { webView }, modifier = modifier) }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createSandboxWebView(context: Context, holder: SandboxHolder, session: PreviewSession, config: PreviewConfig): WebView {
    val wv = WebView(context)
    wv.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        @Suppress("DEPRECATION") allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs = false
        @Suppress("DEPRECATION") databaseEnabled = false
        setGeolocationEnabled(false)
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        mediaPlaybackRequiresUserGesture = true
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        cacheMode = WebSettings.LOAD_NO_CACHE
        setSupportZoom(false)
        textZoom = (config.fontScale * 100).toInt()
        // The preview emulates dark mode itself (see ThemeEmulation); the system must not darken pages on top of that.
        if (Build.VERSION.SDK_INT >= 33) isAlgorithmicDarkeningAllowed = false
        else if (Build.VERSION.SDK_INT >= 29) @Suppress("DEPRECATION") forceDark = WebSettings.FORCE_DARK_OFF
    }
    CookieManager.getInstance().setAcceptCookie(false)
    CookieManager.getInstance().setAcceptThirdPartyCookies(wv, false)
    WebView.setWebContentsDebuggingEnabled(false)
    wv.setDownloadListener { _, _, _, _, _ -> session.onBlocked("A download was blocked.") }

    wv.webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val b = holder.bundle
            val files = b?.files.orEmpty()
            val decision = PreviewRequestPolicy.decide(request.url.toString(), request.method, files.keys, b?.entry ?: "index.html", holder.allowInternet, request.isForMainFrame)
            return when (decision) {
                is PreviewRequestPolicy.Decision.Serve -> {
                    val f = files.getValue(decision.path)
                    val headers = HashMap<String, String>()
                    headers["Cache-Control"] = "no-store"
                    if (f.mime == "text/html") headers["Content-Security-Policy"] = com.almus.appcreatorpro.preview.HtmlInstrumenter.csp(holder.allowInternet)
                    WebResourceResponse(f.mime, "utf-8", 200, "OK", headers, ByteArrayInputStream(f.text.toByteArray(Charsets.UTF_8)))
                }
                is PreviewRequestPolicy.Decision.Inline, is PreviewRequestPolicy.Decision.External -> null
                is PreviewRequestPolicy.Decision.Block -> {
                    if (decision.status == 404) session.onResourceError("404 not found", request.url.toString()) else session.onBlocked(decision.reason)
                    WebResourceResponse("text/plain", "utf-8", decision.status, if (decision.status == 404) "Not Found" else "Blocked", emptyMap(), ByteArrayInputStream(decision.reason.toByteArray(Charsets.UTF_8)))
                }
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val b = holder.bundle
            val d = PreviewRequestPolicy.decide(request.url.toString(), request.method, b?.files?.keys.orEmpty(), b?.entry ?: "index.html", false, true)
            if (d is PreviewRequestPolicy.Decision.Serve) return false
            session.onBlocked("Navigation blocked: a preview only shows pages of the project. (${request.url.toString().take(100)})")
            return true
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            session.onResourceError(error.description.toString(), request.url.toString())
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            session.onRenderProcessGone(detail.didCrash())
            return true
        }

        override fun onPageFinished(view: WebView, url: String?) {
            view.evaluateJavascript("window.__almusInspect=${session.state.value.inspect};", null)
        }
    }

    wv.webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(cm: ConsoleMessage): Boolean {
            session.onWebConsole(cm.messageLevel().name, cm.message(), cm.sourceId(), cm.lineNumber())
            return true
        }
        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            session.onWebConsole("LOG", "alert(): $message", null, 0); result.confirm(); return true
        }
        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            session.onWebConsole("LOG", "confirm(): $message (answered: cancel)", null, 0); result.cancel(); return true
        }
        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
            session.onWebConsole("LOG", "prompt(): $message (answered: cancel)", null, 0); result.cancel(); return true
        }
        override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) { callback?.invoke(origin, false, false) }
        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean = false
        override fun onShowFileChooser(webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?): Boolean {
            filePathCallback?.onReceiveValue(null)
            return true
        }
    }

    if (Build.VERSION.SDK_INT >= 29) {
        wv.setWebViewRenderProcessClient(object : WebViewRenderProcessClient() {
            override fun onRenderProcessUnresponsive(view: WebView, renderer: WebViewRenderProcess?) {
                session.onRenderUnresponsive()
                renderer?.terminate()
            }
            override fun onRenderProcessResponsive(view: WebView, renderer: WebViewRenderProcess?) {}
        })
    }

    val entry = holder.bundle?.entry ?: "index.html"
    wv.loadUrl(PreviewRequestPolicy.BASE_URL + Uri.encode(entry, "/"))
    return wv
}

private fun destroySandbox(wv: WebView) {
    try {
        wv.stopLoading()
        wv.loadUrl("about:blank")
        wv.removeAllViews()
        wv.destroy()
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies(null)
    } catch (e: Exception) {
        // Nothing useful can be done if the view is already gone.
    }
}

// ------------------------------------------------------------------------------------------------ approximate (wireframe) view

private val containerTypes = setOf(NodeType.LINEAR, NodeType.FRAME, NodeType.CONSTRAINT, NodeType.SCROLL, NodeType.CARD)

/** Draws a laid-out tree. This is an approximation made by ALMUS, not the Android runtime. Tapping a view reports its source line. */
@Composable
fun WireScreen(tree: LaidOut, palette: PreviewPalette, pick: PickEvent?, fontScale: Float, widthDp: Int, heightDp: Int, onTap: (Float, Float) -> Unit) {
    val density = LocalDensity.current.density
    Box(
        Modifier.size(widthDp.dp, heightDp.dp).clipToBounds().background(Color(palette.background))
            .pointerInput(tree) { detectTapGestures { o -> onTap(o.x / density, o.y / density) } },
    ) { WireNode(tree, 0f, 0f, palette, pick, fontScale) }
}

@Composable
private fun WireNode(n: LaidOut, ox: Float, oy: Float, palette: PreviewPalette, pick: PickEvent?, fs: Float) {
    val node = n.node
    if (node.invisible) return
    val w = n.w.coerceAtLeast(0f)
    val h = n.h.coerceAtLeast(0f)
    val shape = RoundedCornerShape(node.corner.coerceAtMost(minOf(w, h) / 2f).coerceAtLeast(0f).dp)
    val picked = pick != null && pick.line == node.line && pick.path == node.sourcePath
    val outline = Color(palette.outline).copy(alpha = if (node.type in containerTypes) 0.25f else 0.45f)
    var m = Modifier.offset((n.x - ox).dp, (n.y - oy).dp).size(w.dp, h.dp)
    node.background?.let { m = m.background(Color(it), shape) }
    m = m.border(if (picked) 2.dp else 0.5.dp, if (picked) Color(palette.primary) else outline, shape)
    if (node.type == NodeType.SCROLL || n.clipped) m = m.clipToBounds()
    val textColor = Color(node.textColor ?: palette.onSurface)
    val pad = Modifier.padding(node.padding.left.coerceAtLeast(0f).dp, node.padding.top.coerceAtLeast(0f).dp, node.padding.right.coerceAtLeast(0f).dp, node.padding.bottom.coerceAtLeast(0f).dp)
    Box(m) {
        when (node.type) {
            NodeType.TEXT -> Text(node.text.orEmpty(), color = textColor, fontSize = (node.textSizeSp * fs).sp, fontWeight = if (node.bold) FontWeight.Bold else null, overflow = TextOverflow.Clip, modifier = pad)
            NodeType.BUTTON -> if (node.children.isEmpty()) Text(node.text.orEmpty(), color = textColor, fontSize = (node.textSizeSp * fs).sp, modifier = Modifier.align(Alignment.Center))
            NodeType.EDIT -> {
                val shown = node.text ?: node.hint.orEmpty()
                Text(shown, color = if (node.text == null) textColor.copy(alpha = 0.55f) else textColor, fontSize = (node.textSizeSp * fs).sp, modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp))
            }
            NodeType.TOOLBAR -> if (node.children.isEmpty()) Text(node.text.orEmpty(), color = textColor, fontSize = (node.textSizeSp * fs).sp, modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp))
            NodeType.PLACEHOLDER -> if (node.children.isEmpty()) Text(node.text.orEmpty(), color = textColor.copy(alpha = 0.8f), fontSize = 11.sp, modifier = Modifier.align(Alignment.Center))
            NodeType.IMAGE -> Canvas(Modifier.fillMaxSize()) {
                val c = outline
                drawLine(c, Offset(0f, 0f), Offset(size.width, size.height), 1f)
                drawLine(c, Offset(size.width, 0f), Offset(0f, size.height), 1f)
            }
            NodeType.SWITCH -> {
                Box(Modifier.align(Alignment.Center).size(36.dp, 18.dp).background(Color(palette.primary).copy(alpha = 0.5f), RoundedCornerShape(9.dp)))
                Box(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).size(14.dp).background(Color(palette.primary), RoundedCornerShape(7.dp)))
            }
            NodeType.CHECK -> {
                Box(Modifier.align(Alignment.CenterStart).padding(start = 8.dp).size(16.dp).border(1.5.dp, Color(palette.primary), RoundedCornerShape(3.dp)))
                if (node.text != null) Text(node.text, color = textColor, fontSize = (node.textSizeSp * fs).sp, modifier = Modifier.align(Alignment.CenterStart).padding(start = 32.dp))
            }
            NodeType.PROGRESS -> Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(0.4f).height(if (h < 10f) h.dp else 4.dp).background(Color(palette.primary), RoundedCornerShape(2.dp)))
            NodeType.LIST -> Column { repeat(4) { Box(Modifier.fillMaxWidth().height(36.dp).padding(6.dp).background(Color(palette.placeholder), RoundedCornerShape(6.dp))) } }
            else -> {}
        }
        for (c in n.children) WireNode(c, n.x, n.y, palette, pick, fs)
        if (n.clipped && node.type == NodeType.SCROLL) Text("scrolls", fontSize = 9.sp, color = Color(palette.outline), modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp))
    }
}
