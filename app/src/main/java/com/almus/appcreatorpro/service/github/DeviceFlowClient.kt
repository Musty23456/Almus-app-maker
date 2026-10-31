package com.almus.appcreatorpro.service.github

import com.almus.appcreatorpro.domain.github.DeviceFlowException
import com.almus.appcreatorpro.domain.github.DeviceFlowException.Reason
import com.almus.appcreatorpro.domain.github.GrantedAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.URLEncoder

/** What the user must do on github.com. [deviceCode] is secret-ish and is kept out of [toString]. */
class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
) {
    override fun toString(): String = "DeviceCode(userCode=$userCode, verificationUri=$verificationUri)"
}

/** The access token. [toString] never prints it. */
class DeviceToken(
    val accessToken: String,
    val scopes: Set<String>,
    val tokenType: String,
) {
    override fun toString(): String = "DeviceToken(scopes=$scopes, tokenType=$tokenType)"
}

object ClientIdValidator {
    private val pattern = Regex("^[A-Za-z0-9._-]{8,64}$")

    /** Returns an error message, or null if [clientId] looks valid. A client id is public, not a secret. */
    fun validate(clientId: String): String? = when {
        clientId.isBlank() -> "Enter the Client ID of your GitHub app."
        !pattern.matches(clientId.trim()) ->
            "That does not look like a Client ID (letters, digits, '.', '-' or '_' only, 8-64 characters)."
        else -> null
    }
}

/**
 * GitHub OAuth *device flow*. Needs only the public client id: there is no client secret anywhere,
 * which is exactly why it is the right flow for an app that ships to phones.
 * https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps#device-flow
 */
class DeviceFlowClient(
    private val transport: HttpTransport,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val deviceCodeUrl: String = "https://github.com/login/device/code",
    private val tokenUrl: String = "https://github.com/login/oauth/access_token",
) {
    suspend fun requestCode(clientId: String, scopes: List<String>): DeviceCode {
        ClientIdValidator.validate(clientId)?.let { throw DeviceFlowException(Reason.INVALID_CLIENT_ID, it) }
        val form = buildList {
            add("client_id" to clientId.trim())
            if (scopes.isNotEmpty()) add("scope" to scopes.joinToString(" "))
        }
        val json = post(deviceCodeUrl, form)
        json.str("error")?.let { throw mapCodeError(it) }
        return DeviceCode(
            deviceCode = json.str("device_code") ?: throw bad(),
            userCode = json.str("user_code") ?: throw bad(),
            verificationUri = json.str("verification_uri") ?: "https://github.com/login/device",
            expiresInSeconds = (json.long("expires_in") ?: 900L).toInt(),
            intervalSeconds = (json.long("interval") ?: 5L).toInt().coerceAtLeast(1),
        )
    }

    /**
     * Polls until the user approves. Honors GitHub's interval, backs off on `slow_down`, and stops when the code
     * expires, the user denies, or the coroutine is cancelled.
     */
    suspend fun awaitToken(clientId: String, code: DeviceCode): DeviceToken {
        val deadline = now() + code.expiresInSeconds * 1000L
        var intervalMs = code.intervalSeconds * 1000L
        var networkErrors = 0
        while (true) {
            if (now() >= deadline) throw DeviceFlowException(Reason.EXPIRED, "The code expired before it was approved. Start again.")
            sleep(intervalMs)
            val json = try {
                post(
                    tokenUrl,
                    listOf(
                        "client_id" to clientId.trim(),
                        "device_code" to code.deviceCode,
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    ),
                ).also { networkErrors = 0 }
            } catch (e: DeviceFlowException) {
                if (e.reason == Reason.NETWORK && ++networkErrors < MAX_NETWORK_ERRORS) continue
                throw e
            }
            val token = json.str("access_token")
            if (token != null) {
                return DeviceToken(token, GrantedAccess.parseScopes(json.str("scope")), json.str("token_type") ?: "bearer")
            }
            when (val error = json.str("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> intervalMs = (json.long("interval")?.times(1000L)) ?: (intervalMs + 5_000L)
                "expired_token" -> throw DeviceFlowException(Reason.EXPIRED, "The code expired before it was approved. Start again.")
                "access_denied" -> throw DeviceFlowException(Reason.DENIED, "Authorization was cancelled on GitHub.")
                null -> throw bad()
                else -> throw mapCodeError(error)
            }
        }
    }

    private fun mapCodeError(error: String): DeviceFlowException = when (error) {
        "device_flow_disabled" -> DeviceFlowException(
            Reason.DEVICE_FLOW_DISABLED,
            "Device flow is turned off for this GitHub app. In the app's settings on github.com, tick \"Enable Device Flow\".",
        )
        "incorrect_client_credentials", "unauthorized_client" -> DeviceFlowException(
            Reason.INVALID_CLIENT_ID,
            "GitHub does not recognise that Client ID. Copy it again from your app's settings page.",
        )
        else -> DeviceFlowException(Reason.UNKNOWN, "GitHub sign-in failed ($error).")
    }

    private suspend fun post(url: String, form: List<Pair<String, String>>): Map<String, Any?> {
        val body = form.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }
        val response = try {
            transport.execute(
                HttpRequest(
                    method = "POST",
                    url = url,
                    headers = mapOf("Accept" to "application/json", "User-Agent" to "AlmusAppCreatorPro"),
                    body = body,
                    contentType = "application/x-www-form-urlencoded",
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw DeviceFlowException(Reason.NETWORK, "Could not reach GitHub. Check your internet connection.", e)
        }
        if (response.code == 404) {
            throw DeviceFlowException(Reason.INVALID_CLIENT_ID, "GitHub does not recognise that Client ID, or Device Flow is not enabled for it.")
        }
        if (response.code >= 500) throw DeviceFlowException(Reason.NETWORK, "GitHub is having problems (HTTP ${response.code}). Try again.")
        return try {
            MiniJson.parseObject(response.body)
        } catch (e: MiniJson.JsonException) {
            throw bad()
        }
    }

    private fun bad() = DeviceFlowException(Reason.BAD_RESPONSE, "GitHub sent a response the app could not read.")

    private companion object {
        const val MAX_NETWORK_ERRORS = 4
    }
}
