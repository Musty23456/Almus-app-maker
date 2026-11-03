package com.almus.appcreatorpro.service.signing

import com.almus.appcreatorpro.domain.build.SigningException
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import java.security.cert.X509Certificate
import java.util.Date

/** Who the certificate of a new key is issued to. Every field is optional except [commonName]. */
data class CertificateOwner(
    val commonName: String,
    val organization: String = "",
    val country: String = "",
)

class GeneratedKeystore(
    /** PKCS12 file contents. */
    val bytes: ByteArray,
    val alias: String,
    val sha256: String,
    val subject: String,
)

/** What the app could read from a keystore file. */
class KeystoreInspection(
    val alias: String,
    val sha256: String,
    val subject: String,
    val notAfterMillis: Long,
)

/**
 * Creates and inspects signing keystores (PKCS12, the format apksigner, jarsigner and Android Studio all read).
 * Passwords are passed in and used; they are never stored, logged or put into an exception message.
 */
class KeystoreGenerator(
    private val random: SecureRandom = SecureRandom(),
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** RSA 2048 with SHA-256, valid for [validityYears] (default 30: Google Play wants a key valid beyond 2033). */
    fun generate(
        alias: String,
        password: String,
        owner: CertificateOwner,
        validityYears: Int = 30,
        keySizeBits: Int = 2048,
    ): GeneratedKeystore {
        try {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(keySizeBits, random) }.generateKeyPair()
            val name = X500NameBuilder(BCStyle.INSTANCE).apply {
                addRDN(BCStyle.CN, owner.commonName.trim())
                if (owner.organization.isNotBlank()) addRDN(BCStyle.O, owner.organization.trim())
                if (owner.country.isNotBlank()) addRDN(BCStyle.C, owner.country.trim().uppercase().take(2))
            }.build()
            val start = now() - 60_000L
            val end = start + validityYears * 365L * 24L * 60L * 60L * 1000L
            val serial = BigInteger(64, random).abs().add(BigInteger.ONE)
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(pair.private)
            val cert = JcaX509CertificateConverter().getCertificate(
                JcaX509v3CertificateBuilder(name, serial, Date(start), Date(end), name, pair.public).build(signer),
            )
            val chars = password.toCharArray()
            val store = KeyStore.getInstance("PKCS12")
            store.load(null, null)
            // PKCS12 protects the key with the store password, so the key password is the same value.
            store.setKeyEntry(alias, pair.private, chars, arrayOf(cert))
            val out = ByteArrayOutputStream()
            store.store(out, chars)
            return GeneratedKeystore(out.toByteArray(), alias, fingerprint(cert), cert.subjectX500Principal.name)
        } catch (e: SigningException) {
            throw e
        } catch (e: Exception) {
            throw SigningException(SigningException.Kind.UNKNOWN, "The key could not be created on this phone.", e)
        }
    }

    /**
     * Opens a PKCS12 keystore and checks [alias] (or the only entry when [alias] is null) and the key password.
     * Throws [SigningException] with a user-safe message otherwise.
     */
    fun inspect(bytes: ByteArray, storePassword: String, alias: String?, keyPassword: String): KeystoreInspection {
        val store = try {
            KeyStore.getInstance("PKCS12").also { it.load(ByteArrayInputStream(bytes), storePassword.toCharArray()) }
        } catch (e: IOException) {
            throw SigningException(
                SigningException.Kind.WRONG_PASSWORD,
                "The keystore could not be opened. The password is wrong, or the file is not a PKCS12 keystore.",
                e,
            )
        } catch (e: Exception) {
            throw SigningException(SigningException.Kind.NOT_A_KEYSTORE, "This file is not a keystore the app can read.", e)
        }
        val keyAliases = store.aliases().toList().filter { store.isKeyEntry(it) }
        if (keyAliases.isEmpty()) throw SigningException(SigningException.Kind.ALIAS_NOT_FOUND, "The keystore contains no signing key.")
        val chosen = when {
            alias.isNullOrBlank() && keyAliases.size == 1 -> keyAliases.first()
            alias.isNullOrBlank() -> throw SigningException(
                SigningException.Kind.ALIAS_NOT_FOUND,
                "The keystore has several keys (${keyAliases.sorted().joinToString(", ")}). Enter the alias to use.",
            )
            else -> keyAliases.firstOrNull { it.equals(alias, ignoreCase = true) } ?: throw SigningException(
                SigningException.Kind.ALIAS_NOT_FOUND,
                "There is no key with the alias \"$alias\". Available: ${keyAliases.sorted().joinToString(", ")}.",
            )
        }
        try {
            store.getKey(chosen, keyPassword.toCharArray()) ?: throw SigningException(SigningException.Kind.ALIAS_NOT_FOUND, "That alias has no private key.")
        } catch (e: UnrecoverableKeyException) {
            throw SigningException(SigningException.Kind.WRONG_PASSWORD, "The key password is wrong.", e)
        } catch (e: SigningException) {
            throw e
        } catch (e: Exception) {
            throw SigningException(SigningException.Kind.WRONG_PASSWORD, "The key could not be read with that password.", e)
        }
        val cert = store.getCertificate(chosen) as? X509Certificate
            ?: throw SigningException(SigningException.Kind.NOT_A_KEYSTORE, "The key has no certificate.")
        return KeystoreInspection(chosen, fingerprint(cert), cert.subjectX500Principal.name, cert.notAfter.time)
    }

    private fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }
}
