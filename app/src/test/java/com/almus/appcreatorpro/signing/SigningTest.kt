package com.almus.appcreatorpro.signing

import com.almus.appcreatorpro.data.storage.InMemoryKeyValueStore
import com.almus.appcreatorpro.domain.build.BuildConfiguration
import com.almus.appcreatorpro.domain.build.BuildVariant
import com.almus.appcreatorpro.domain.build.SigningException
import com.almus.appcreatorpro.domain.build.SigningSecretNames
import com.almus.appcreatorpro.domain.build.SigningSource
import com.almus.appcreatorpro.domain.build.SigningValidator
import com.almus.appcreatorpro.service.github.InMemorySecretStore
import com.almus.appcreatorpro.service.signing.CertificateOwner
import com.almus.appcreatorpro.service.signing.KeystoreGenerator
import com.almus.appcreatorpro.service.signing.SigningManager
import com.almus.appcreatorpro.service.signing.SigningStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.util.Base64

private const val PASSWORD = "Str0ngPassw0rdXyz"

private class Fixture {
    val meta = InMemoryKeyValueStore()
    val secrets = InMemorySecretStore()
    private var counter = 0
    val manager = SigningManager(SigningStore(meta, secrets), newId = { "%012x".format(++counter) })
    val owner = CertificateOwner("Musty Salisu", "Almus", "NG")
}

private fun expectKind(kind: SigningException.Kind, block: () -> Unit) {
    try {
        block()
        fail("expected $kind")
    } catch (e: SigningException) {
        assertEquals(kind, e.kind)
    }
}

class SigningTest {

    @Test
    fun a_generated_keystore_opens_with_its_password_and_has_a_certificate() {
        val k = KeystoreGenerator().generate("release", PASSWORD, CertificateOwner("Musty", "Almus", "NG"))
        val ks = KeyStore.getInstance("PKCS12").apply { load(ByteArrayInputStream(k.bytes), PASSWORD.toCharArray()) }
        assertTrue(ks.isKeyEntry("release"))
        assertNotNull(ks.getKey("release", PASSWORD.toCharArray()))
        assertTrue(k.subject.contains("CN=Musty"))
        assertEquals(32 * 3 - 1, k.sha256.length)
    }

    @Test
    fun the_generator_inspects_its_own_output_and_rejects_wrong_input() {
        val gen = KeystoreGenerator()
        val k = gen.generate("release", PASSWORD, CertificateOwner("Musty"))
        val ok = gen.inspect(k.bytes, PASSWORD, null, PASSWORD)
        assertEquals("release", ok.alias)
        assertEquals(k.sha256, ok.sha256)
        expectKind(SigningException.Kind.WRONG_PASSWORD) { gen.inspect(k.bytes, "wrong-password-123", "release", "wrong-password-123") }
        expectKind(SigningException.Kind.ALIAS_NOT_FOUND) { gen.inspect(k.bytes, PASSWORD, "nope", PASSWORD) }
    }

    @Test
    fun error_messages_never_contain_the_password() {
        val gen = KeystoreGenerator()
        val k = gen.generate("release", PASSWORD, CertificateOwner("Musty"))
        try {
            gen.inspect(k.bytes, "totally-wrong-pw-9", "release", "totally-wrong-pw-9")
            fail("should fail")
        } catch (e: SigningException) {
            assertFalse(e.message.orEmpty().contains("totally-wrong-pw-9"))
        }
    }

    @Test
    fun password_and_alias_rules() {
        assertNotNull(SigningValidator.validateNewPassword("short"))
        assertNotNull(SigningValidator.validateNewPassword("has space in it 123"))
        assertNotNull(SigningValidator.validateNewPassword("aaaaaaaaaaaaaaaa"))
        assertNull(SigningValidator.validateNewPassword(PASSWORD))
        assertNull(SigningValidator.validateAlias("release"))
        assertNotNull(SigningValidator.validateAlias("bad alias"))
        assertNotNull(SigningValidator.validateAlias(""))
        assertNotNull(SigningValidator.validateLabel(" "))
        assertTrue(SigningManager.generatePassword().let { it.length == 24 && SigningValidator.validateNewPassword(it) == null })
    }

    @Test
    fun the_manager_creates_a_profile_and_keeps_secrets_out_of_the_plain_store() {
        val f = Fixture()
        val p = f.manager.generate("My key", "Release", PASSWORD, f.owner)
        assertEquals("release", p.alias)
        assertEquals(SigningSource.GENERATED, p.source)
        assertTrue(p.verifiedOnDevice)
        assertEquals(listOf(p), f.manager.profiles())
        val plain = f.meta.keys().joinToString("|") { f.meta.getString(it).orEmpty() }
        assertFalse("password must not be in plain preferences", plain.contains(PASSWORD))
        val s = f.manager.secrets(p.id)
        assertEquals(PASSWORD, s.storePassword)
        assertEquals(PASSWORD, s.keyPassword)
        assertEquals("release", s.keyAlias)
        assertFalse(s.toString().contains(PASSWORD))
        assertFalse(s.toString().contains(s.keystoreBase64.take(20)))
    }

    @Test
    fun the_four_github_secrets_are_named_as_the_workflow_expects() {
        val f = Fixture()
        val p = f.manager.generate("k", "release", PASSWORD, f.owner)
        val entries = f.manager.secrets(p.id).entries()
        assertEquals(SigningSecretNames.ALL, entries.map { it.first })
        val workflow = java.io.File("../.github/workflows/almus-cloud-build.yml").takeIf { it.exists() }
            ?: java.io.File(".github/workflows/almus-cloud-build.yml").takeIf { it.exists() }
        if (workflow != null) {
            val text = workflow.readText()
            for (name in SigningSecretNames.ALL) assertTrue("$name missing in workflow", text.contains("secrets.$name"))
        }
        val decoded = Base64.getDecoder().decode(entries.first { it.first == SigningSecretNames.KEYSTORE_BASE64 }.second)
        val ks = KeyStore.getInstance("PKCS12").apply { load(ByteArrayInputStream(decoded), PASSWORD.toCharArray()) }
        assertTrue(ks.containsAlias("release"))
    }

    @Test
    fun invalid_input_is_refused_before_anything_is_stored() {
        val f = Fixture()
        expectKind(SigningException.Kind.INVALID_INPUT) { f.manager.generate("", "release", PASSWORD, f.owner) }
        expectKind(SigningException.Kind.INVALID_INPUT) { f.manager.generate("k", "bad alias", PASSWORD, f.owner) }
        expectKind(SigningException.Kind.INVALID_INPUT) { f.manager.generate("k", "release", "weak", f.owner) }
        expectKind(SigningException.Kind.INVALID_INPUT) { f.manager.generate("k", "release", PASSWORD, CertificateOwner("")) }
        expectKind(SigningException.Kind.INVALID_INPUT) { f.manager.generate("k", "release", PASSWORD, CertificateOwner("A", country = "Nigeria")) }
        assertTrue(f.manager.profiles().isEmpty())
        assertTrue(f.meta.keys().isEmpty())
    }

    @Test
    fun a_pkcs12_keystore_can_be_imported_and_is_verified() {
        val f = Fixture()
        val bytes = KeystoreGenerator().generate("upload", PASSWORD, CertificateOwner("Musty")).bytes
        val p = f.manager.import("Existing", bytes, "", PASSWORD, "")
        assertEquals(SigningSource.IMPORTED, p.source)
        assertEquals("upload", p.alias)
        assertTrue(p.verifiedOnDevice)
        assertNotNull(p.sha256)
        assertEquals(PASSWORD, f.manager.secrets(p.id).keyPassword)
        assertTrue(f.manager.keystoreBytes(p.id).contentEquals(bytes))
    }

    @Test
    fun importing_with_a_wrong_password_or_wrong_alias_fails_cleanly() {
        val f = Fixture()
        val bytes = KeystoreGenerator().generate("upload", PASSWORD, CertificateOwner("Musty")).bytes
        expectKind(SigningException.Kind.WRONG_PASSWORD) { f.manager.import("x", bytes, "", "not-the-password-1", "") }
        expectKind(SigningException.Kind.ALIAS_NOT_FOUND) { f.manager.import("x", bytes, "other", PASSWORD, "") }
        expectKind(SigningException.Kind.INVALID_INPUT) { f.manager.import("x", ByteArray(0), "", PASSWORD, "") }
        expectKind(SigningException.Kind.INVALID_INPUT) { f.manager.import("x", ByteArray(70_000), "", PASSWORD, "") }
        assertTrue(f.manager.profiles().isEmpty())
    }

    @Test
    fun a_jks_file_is_kept_unverified_and_needs_an_alias() {
        val f = Fixture()
        val jks = byteArrayOf(0xFE.toByte(), 0xED.toByte(), 0xFE.toByte(), 0xED.toByte(), 0, 0, 0, 2, 1, 2, 3)
        expectKind(SigningException.Kind.INVALID_INPUT) { f.manager.import("old", jks, "", "pw-123456", "") }
        val p = f.manager.import("old", jks, "mykey", "pw-123456", "key-123456")
        assertFalse(p.verifiedOnDevice)
        assertNull(p.sha256)
        assertEquals("key-123456", f.manager.secrets(p.id).keyPassword)
    }

    @Test
    fun release_builds_are_blocked_until_a_key_is_chosen_and_its_secrets_confirmed() {
        val f = Fixture()
        val release = BuildConfiguration(BuildVariant.RELEASE_APK)
        assertTrue(f.manager.blockers(BuildConfiguration.DEFAULT, "o/r").isEmpty())
        assertEquals(1, f.manager.blockers(release, "o/r").size)
        assertTrue(f.manager.blockers(release.copy(signingProfileId = "ffffffffffff"), "o/r")[0].contains("no longer exists"))

        val p = f.manager.generate("k", "release", PASSWORD, f.owner)
        val chosen = release.copy(signingProfileId = p.id)
        assertTrue(f.manager.blockers(chosen, "o/r")[0].contains("o/r"))
        f.manager.confirmSecrets(p.id, "o/r")
        assertTrue(f.manager.blockers(chosen, "o/r").isEmpty())
        assertEquals("a confirmation is per repository", 1, f.manager.blockers(chosen, "o/other").size)
        f.manager.unconfirmSecrets(p.id, "o/r")
        assertEquals(1, f.manager.blockers(chosen, "o/r").size)
    }

    @Test
    fun deleting_a_key_removes_every_secret() {
        val f = Fixture()
        val p = f.manager.generate("k", "release", PASSWORD, f.owner)
        f.manager.delete(p.id)
        assertTrue(f.manager.profiles().isEmpty())
        expectKind(SigningException.Kind.NOT_FOUND) { f.manager.secrets(p.id) }
        assertNull(f.secrets.get("signing_keystore_${p.id}"))
        assertNull(f.secrets.get("signing_storepw_${p.id}"))
        assertNull(f.secrets.get("signing_keypw_${p.id}"))
    }

    @Test
    fun secrets_lost_to_a_keystore_reset_give_a_clear_error_not_a_crash() {
        val f = Fixture()
        val p = f.manager.generate("k", "release", PASSWORD, f.owner)
        f.secrets.remove("signing_keystore_${p.id}")
        expectKind(SigningException.Kind.NOT_FOUND) { f.manager.secrets(p.id) }
    }

    @Test
    fun a_store_that_cannot_write_secrets_leaves_nothing_behind() {
        val failing = object : com.almus.appcreatorpro.service.github.SecretStore {
            private val inner = InMemorySecretStore()
            var puts = 0
            override fun get(key: String) = inner.get(key)
            override fun put(key: String, value: String) { if (++puts == 3) throw IllegalStateException("disk full"); inner.put(key, value) }
            override fun remove(key: String) = inner.remove(key)
        }
        val meta = InMemoryKeyValueStore()
        val manager = SigningManager(SigningStore(meta, failing), newId = { "aaaaaaaaaaaa" })
        expectKind(SigningException.Kind.STORAGE) { manager.generate("k", "release", PASSWORD, CertificateOwner("A")) }
        assertTrue(manager.profiles().isEmpty())
        assertNull(failing.get("signing_keystore_aaaaaaaaaaaa"))
    }

    @Test
    fun profile_ids_cannot_escape_the_store() {
        val store = SigningStore(InMemoryKeyValueStore(), InMemorySecretStore())
        assertNull(store.get("../../x"))
        expectKind(SigningException.Kind.NOT_FOUND) { store.secrets("../../x") }
    }
}

class LogSafetyPhase6Test {
    @Test
    fun signing_variable_names_and_keystore_blobs_are_redacted_from_logs() {
        val blob = "A".repeat(300)
        val log = listOf(
            "STORE_PASS=hunter2hunter2",
            "KEY_PASS: hunter2hunter2",
            "ALMUS_SIGN_KEYSTORE_BASE64=$blob",
            "dump $blob end",
            "BUILD FAILED in 3s",
        ).joinToString("\n")
        val out = com.almus.appcreatorpro.domain.github.LogRedactor.redact(log)
        assertFalse(out.contains("hunter2"))
        assertFalse(out.contains("AAAAAAAAAA"))
        assertTrue(out.contains("BUILD FAILED in 3s"))
    }

    @Test
    fun ordinary_log_lines_are_left_alone() {
        val line = "12 tests passed: all good, compass heading e: file.kt:3 Unresolved reference"
        assertEquals(line, com.almus.appcreatorpro.domain.github.LogRedactor.redact(line))
    }
}
