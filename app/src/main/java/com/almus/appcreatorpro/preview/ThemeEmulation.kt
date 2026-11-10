package com.almus.appcreatorpro.preview

/**
 * Light and dark mode for WEB previews, without any extra library.
 *
 * Android WebView has no per-page switch for `prefers-color-scheme`, so the preview rewrites the project's own CSS
 * before loading it: `(prefers-color-scheme: dark)` becomes an always-true or always-false media condition, and
 * `color-scheme` is resolved to one value. A small script answers `matchMedia('(prefers-color-scheme: ...)')` the same
 * way. This is an EMULATION of the setting, done on a copy of the files; the project's files are never changed.
 * Limits: complex media queries passed to `matchMedia` are answered by the real browser, and CSS the page builds at
 * run time is not rewritten.
 */
object ThemeEmulation {
    private val prefers = Regex("\\(\\s*prefers-color-scheme\\s*:\\s*(dark|light)\\s*\\)", RegexOption.IGNORE_CASE)
    private val colorScheme = Regex("color-scheme\\s*:\\s*([^;}!]*)", RegexOption.IGNORE_CASE)

    fun rewriteCss(css: String, dark: Boolean): String {
        var out = prefers.replace(css) { m ->
            val wantsDark = m.groupValues[1].equals("dark", ignoreCase = true)
            if (wantsDark == dark) "(min-width: 0px)" else "(max-width: 0px)"
        }
        out = colorScheme.replace(out) { m -> "color-scheme: " + resolveScheme(m.groupValues[1], dark) + " " }
        return out
    }

    /** `light dark` -> the one the current mode would use; a page that only supports one scheme keeps it. */
    fun resolveScheme(value: String, dark: Boolean): String {
        val words = value.lowercase().split(' ', '\t', '\n').filter { it.isNotBlank() }
        val hasDark = "dark" in words
        val hasLight = "light" in words || "normal" in words || words.isEmpty()
        return when {
            hasDark && hasLight -> if (dark) "dark" else "light"
            hasDark -> "dark"
            else -> "light"
        }
    }

    /**
     * The script that makes `matchMedia` agree with the chosen mode, supports tap-to-source, and removes WebRTC (which
     * neither the request gate nor the CSP can stop). One line, so line numbers of the page do not move.
     */
    fun hookScript(dark: Boolean): String =
        "(function(){try{delete window.RTCPeerConnection;delete window.webkitRTCPeerConnection;delete window.RTCDataChannel}catch(e){}" +
            "var D=${if (dark) "true" else "false"};var om=window.matchMedia;" +
            "window.matchMedia=function(q){var s=String(q);var m=/^\\s*\\(\\s*prefers-color-scheme\\s*:\\s*(dark|light)\\s*\\)\\s*\$/i.exec(s);" +
            "if(m){var v=(m[1].toLowerCase()==='dark')===D;return{matches:v,media:s,onchange:null,addListener:function(){},removeListener:function(){}," +
            "addEventListener:function(){},removeEventListener:function(){},dispatchEvent:function(){return false}}}return om.call(window,q)};" +
            "window.__almusInspect=false;" +
            "document.addEventListener('click',function(e){if(!window.__almusInspect)return;e.preventDefault();e.stopPropagation();var t=e.target;" +
            "while(t&&t.nodeType===1&&!t.getAttribute('data-almus-src'))t=t.parentNode;" +
            "if(t&&t.getAttribute){console.log('[almus:pick] '+t.getAttribute('data-almus-src')+' '+t.tagName.toLowerCase())}},true);})();"
}
