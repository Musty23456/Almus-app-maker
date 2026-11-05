package com.almus.appcreatorpro.ai

import com.almus.appcreatorpro.data.storage.InMemoryKeyValueStore
import com.almus.appcreatorpro.service.github.InMemorySecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsAndSecurityTest {
    private fun ok(s: String) = assertTrue(s, BackendUrlValidator.validate(s) is UrlCheck.Ok)
    private fun bad(s: String) = assertTrue(s, BackendUrlValidator.validate(s) is UrlCheck.Bad)

    @Test fun `backend address rules`() {
        ok("https://ai.example.com"); ok("https://ai.example.com:8443/"); ok("https://example.com/almus")
        ok("http://10.0.2.2:8080"); ok("http://localhost:8080"); ok("http://127.0.0.1:8080")
        bad(""); bad("   "); bad("ai.example.com"); bad("ftp://ai.example.com")
        bad("http://ai.example.com"); bad("http://192.168.1.5:8080")
        bad("https://user:pass@ai.example.com"); bad("https://ai.example.com?key=1"); bad("https://ai.example.com/#x")
        bad("https://ai example.com"); bad("https://ai.example.com:99999"); bad("https://ai.example.com:abc"); bad("https://")
        assertEquals("https://ai.example.com", (BackendUrlValidator.validate(" https://ai.example.com/ ") as UrlCheck.Ok).url)
    }

    @Test fun `access code goes only to the secret store`() {
        val meta = InMemoryKeyValueStore(); val secrets = InMemorySecretStore()
        val s = AiSettingsStore(meta, secrets)
        assertEquals(AiSettingsStore.SaveResult.Saved, s.save("https://ai.example.com", "my-very-secret-code"))
        assertTrue(meta.keys().none { meta.getString(it)!!.contains("my-very-secret-code") })
        assertEquals("my-very-secret-code", s.config()!!.accessCode)
        assertFalse(s.config()!!.toString().contains("my-very-secret-code"))
    }

    @Test fun `save validates and can keep the stored code`() {
        val s = AiSettingsStore(InMemoryKeyValueStore(), InMemorySecretStore())
        assertTrue(s.save("https://ai.example.com", "") is AiSettingsStore.SaveResult.Invalid) // no code yet
        assertTrue(s.save("https://ai.example.com", "short") is AiSettingsStore.SaveResult.Invalid)
        assertTrue(s.save("http://evil.example.com", "long-enough-code") is AiSettingsStore.SaveResult.Invalid)
        assertNull(s.config())
        assertEquals(AiSettingsStore.SaveResult.Saved, s.save("https://ai.example.com", "long-enough-code"))
        assertEquals(AiSettingsStore.SaveResult.Saved, s.save("https://new.example.com", null)) // keeps the code
        assertEquals("long-enough-code", s.config()!!.accessCode)
        assertEquals("https://new.example.com", s.config()!!.baseUrl)
    }

    @Test fun `disconnect removes address, model and code`() {
        val s = AiSettingsStore(InMemoryKeyValueStore(), InMemorySecretStore())
        s.save("https://ai.example.com", "long-enough-code"); s.selectModel("pro")
        s.clear()
        assertNull(s.config()); assertNull(s.baseUrl()); assertNull(s.selectedModel()); assertFalse(s.hasAccessCode())
    }

    // ---- the app must never contain a Gemini key or call Google directly -----------------------------------------------

    private fun appSources(): List<File> {
        val roots = listOf(File("src/main"), File("app/src/main")).filter { it.isDirectory }
        assertTrue("run from the app module", roots.isNotEmpty())
        return roots.flatMap { r -> r.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml", "kts", "json", "properties", "txt") }.toList() }
    }

    @Test fun `no Google API key is in the app sources or resources`() {
        val key = Regex("AIza[0-9A-Za-z_\\-]{35}")
        val offenders = appSources().filter { key.containsMatchIn(it.readText()) }.map { it.path }
        assertTrue("Gemini/Google key found in: $offenders", offenders.isEmpty())
    }

    @Test fun `the app never talks to Google's generative API directly`() {
        val offenders = appSources().filter { it.extension == "kt" && it.readText().contains("generativelanguage.googleapis.com") }.map { it.path }
        assertTrue("Direct Gemini calls found in: $offenders", offenders.isEmpty())
    }

    @Test fun `gradle build files do not define a Gemini key`() {
        val gradle = listOf(File("build.gradle.kts"), File("app/build.gradle.kts")).filter { it.isFile }
        assertTrue(gradle.isNotEmpty())
        gradle.forEach { f ->
            val t = f.readText()
            assertFalse(f.path, Regex("(?i)gemini[_a-z]*key|GEMINI_API_KEY|buildConfigField\\([^)]*(api|key)", RegexOption.IGNORE_CASE).containsMatchIn(t))
        }
    }

    @Test fun `cleartext traffic is limited to local development hosts`() {
        val cfg = listOf(File("src/main/res/xml/network_security_config.xml"), File("app/src/main/res/xml/network_security_config.xml")).first { it.isFile }.readText()
        assertTrue(cfg.contains("<base-config cleartextTrafficPermitted=\"false\""))
        val hosts = Regex("<domain[^>]*>([^<]+)</domain>").findAll(cfg).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("10.0.2.2", "localhost", "127.0.0.1"), hosts)
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.isFile }.readText()
        assertTrue(manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
        assertFalse(manifest.contains("usesCleartextTraffic=\"true\""))
    }

    @Test fun `secret scanner removes keys, tokens and passwords but keeps references and code`() {
        val text = "val k = \"AIzaSyA1234567890abcdefghijklmnopqrstuvw\"\nstorePassword = \"hunter2hunter2\"\nkeyPassword=System.getenv(\"KP\")\nval k2 = BuildConfig.KEY\n" +
            "t ghp_abcdefghijklmnopqrstuvwxyz0123456789\n-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\nfun main() {}"
        val r = SecretScanner.redact(text)
        assertEquals(4, r.count)
        for (leak in listOf("AIzaSyA123", "hunter2", "ghp_abc", "AAAA")) assertFalse(leak, r.text.contains(leak))
        assertTrue(r.text.contains("System.getenv") && r.text.contains("BuildConfig.KEY") && r.text.contains("fun main()"))
        assertEquals(0, SecretScanner.redact("val passwordField = TextField()\nandroid:inputType=\"textPassword\"").count)
    }

    @Test fun `credential files and secret-named resources are blocked, source code is scanned instead`() {
        for (p in listOf("local.properties", "app/release.jks", "keystore.properties", "app/google-services.json", ".env", "certs/server.pem", "id_rsa"))
            assertTrue(p, SecretScanner.isSensitivePath(p))
        assertTrue(SecretScanner.isSensitivePath("app/src/main/res/values/secrets.xml"))
        assertFalse(SecretScanner.isSensitivePath("app/src/main/java/com/x/TokenStore.kt"))
        assertFalse(SecretScanner.isSensitivePath("app/src/main/java/com/x/MainActivity.kt"))
    }

    @Test fun `sse reader handles comments, multi-line data and a missing final blank line`() {
        val r = SseEventReader()
        val out = mutableListOf<SseEvent>()
        for (l in listOf(": ping", "event: delta", "data: {\"a\":1}", "", "data: one", "data: two", "", "event: done", "data: {}")) r.feedLine(l)?.let { out += it }
        r.finish()?.let { out += it }
        assertEquals(listOf(SseEvent("delta", "{\"a\":1}"), SseEvent("message", "one\ntwo"), SseEvent("done", "{}")), out)
    }
}
