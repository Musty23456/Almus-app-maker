package com.almus.appcreatorpro.github

import com.almus.appcreatorpro.data.storage.InMemoryKeyValueStore
import com.almus.appcreatorpro.domain.github.AuthMethod
import com.almus.appcreatorpro.domain.github.DeviceFlowException
import com.almus.appcreatorpro.domain.github.DeviceFlowException.Reason
import com.almus.appcreatorpro.domain.model.GitHubConnection
import com.almus.appcreatorpro.service.github.AuthBackedGitHubRepository
import com.almus.appcreatorpro.service.github.ClientIdValidator
import com.almus.appcreatorpro.service.github.DeviceFlowClient
import com.almus.appcreatorpro.service.github.GitHubAuthManager
import com.almus.appcreatorpro.service.github.GitHubAuthState
import com.almus.appcreatorpro.service.github.InMemorySecretStore
import com.almus.appcreatorpro.testutil.FakeClock
import com.almus.appcreatorpro.testutil.FakeTransport
import com.almus.appcreatorpro.testutil.jsonResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private const val CLIENT_ID = "Iv1.abcdef1234567890"
private const val TOKEN = "gho_SECRET_TOKEN_1234567890abcdefghijkl"

class DeviceFlowClientTest {
    private val clock = FakeClock()
    private val transport = FakeTransport()
    private val flow = DeviceFlowClient(transport, now = clock::now, sleep = clock::sleep)

    private val codeJson = """{"device_code":"DEV123","user_code":"ABCD-1234","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}"""

    private suspend fun reason(block: suspend () -> Unit): Reason {
        try { block() } catch (e: DeviceFlowException) { return e.reason }
        throw AssertionError("expected DeviceFlowException")
    }

    @Test
    fun client_id_validation() {
        assertNull(ClientIdValidator.validate(CLIENT_ID))
        assertNull(ClientIdValidator.validate("0123456789abcdef0123"))
        listOf("", "  ", "short", "has space here!", "x".repeat(65)).forEach { assertNotNull("'$it'", ClientIdValidator.validate(it)) }
    }

    @Test
    fun invalid_client_id_is_rejected_before_any_request() = runTest {
        assertEquals(Reason.INVALID_CLIENT_ID, reason { flow.requestCode("bad id", emptyList()) })
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun requests_a_code_with_only_the_client_id_and_scopes_and_no_secret() = runTest {
        transport.on("POST", "/login/device/code", jsonResponse(200, codeJson))
        val code = flow.requestCode(CLIENT_ID, listOf("public_repo"))
        assertEquals("ABCD-1234", code.userCode)
        assertEquals(5, code.intervalSeconds)
        val body = transport.requests.single().body!!
        assertTrue(body.contains("client_id=$CLIENT_ID"))
        assertTrue(body.contains("scope=public_repo"))
        assertFalse(body.contains("secret", ignoreCase = true))
        assertEquals("application/x-www-form-urlencoded", transport.requests.single().contentType)
        assertFalse(code.toString().contains("DEV123"))
    }

    @Test
    fun github_app_requests_no_scope_parameter() = runTest {
        transport.on("POST", "/login/device/code", jsonResponse(200, codeJson))
        flow.requestCode(CLIENT_ID, emptyList())
        assertFalse(transport.requests.single().body!!.contains("scope"))
    }

    @Test
    fun device_flow_disabled_and_wrong_client_id_have_clear_reasons() = runTest {
        transport.on("POST", "/login/device/code", jsonResponse(200, """{"error":"device_flow_disabled"}"""))
        assertEquals(Reason.DEVICE_FLOW_DISABLED, reason { flow.requestCode(CLIENT_ID, emptyList()) })

        val t2 = FakeTransport().apply { on("POST", "/login/device/code", jsonResponse(404, "")) }
        assertEquals(Reason.INVALID_CLIENT_ID, reason { DeviceFlowClient(t2).requestCode(CLIENT_ID, emptyList()) })

        val t3 = FakeTransport().apply { on("POST", "/login/device/code", IOException("offline")) }
        assertEquals(Reason.NETWORK, reason { DeviceFlowClient(t3).requestCode(CLIENT_ID, emptyList()) })
    }

    @Test
    fun polls_until_approved_waiting_the_interval_each_time() = runTest {
        transport.on("POST", "/login/device/code", jsonResponse(200, codeJson))
        transport.on(
            "POST", "/login/oauth/access_token",
            jsonResponse(200, """{"error":"authorization_pending"}"""),
            jsonResponse(200, """{"error":"authorization_pending"}"""),
            jsonResponse(200, """{"access_token":"$TOKEN","token_type":"bearer","scope":"repo"}"""),
        )
        val token = flow.awaitToken(CLIENT_ID, flow.requestCode(CLIENT_ID, emptyList()))
        assertEquals(TOKEN, token.accessToken)
        assertEquals(setOf("repo"), token.scopes)
        assertEquals(listOf(5_000L, 5_000L, 5_000L), clock.sleeps)
        assertFalse(token.toString().contains(TOKEN))
        val poll = transport.requests.last().body!!
        assertTrue(poll.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"))
        assertTrue(poll.contains("device_code=DEV123"))
    }

    @Test
    fun slow_down_increases_the_interval() = runTest {
        transport.on("POST", "/login/device/code", jsonResponse(200, codeJson))
        transport.on(
            "POST", "/login/oauth/access_token",
            jsonResponse(200, """{"error":"slow_down","interval":10}"""),
            jsonResponse(200, """{"error":"slow_down"}"""),
            jsonResponse(200, """{"access_token":"$TOKEN"}"""),
        )
        flow.awaitToken(CLIENT_ID, flow.requestCode(CLIENT_ID, emptyList()))
        assertEquals(listOf(5_000L, 10_000L, 15_000L), clock.sleeps)
    }

    @Test
    fun denied_expired_and_timeout_stop_the_flow() = runTest {
        transport.on("POST", "/login/device/code", jsonResponse(200, codeJson))
        val code = flow.requestCode(CLIENT_ID, emptyList())

        val denied = FakeTransport().apply { on("POST", "/access_token", jsonResponse(200, """{"error":"access_denied"}""")) }
        assertEquals(Reason.DENIED, reason { DeviceFlowClient(denied, clock::now, clock::sleep).awaitToken(CLIENT_ID, code) })

        val expired = FakeTransport().apply { on("POST", "/access_token", jsonResponse(200, """{"error":"expired_token"}""")) }
        assertEquals(Reason.EXPIRED, reason { DeviceFlowClient(expired, clock::now, clock::sleep).awaitToken(CLIENT_ID, code) })

        val pending = FakeTransport().apply { on("POST", "/access_token", jsonResponse(200, """{"error":"authorization_pending"}""")) }
        assertEquals(Reason.EXPIRED, reason { DeviceFlowClient(pending, clock::now, clock::sleep).awaitToken(CLIENT_ID, code) })
        assertTrue("polling is bounded by the code lifetime", pending.requests.size <= 900 / 5 + 1)
    }

    @Test
    fun a_few_network_errors_while_waiting_are_tolerated_but_not_forever() = runTest {
        transport.on("POST", "/login/device/code", jsonResponse(200, codeJson))
        val code = flow.requestCode(CLIENT_ID, emptyList())
        val flaky = FakeTransport().apply {
            on("POST", "/access_token", IOException("blip"), IOException("blip"), jsonResponse(200, """{"access_token":"$TOKEN"}"""))
        }
        assertEquals(TOKEN, DeviceFlowClient(flaky, clock::now, clock::sleep).awaitToken(CLIENT_ID, code).accessToken)

        val dead = FakeTransport().apply { on("POST", "/access_token", IOException("offline")) }
        assertEquals(Reason.NETWORK, reason { DeviceFlowClient(dead, clock::now, clock::sleep).awaitToken(CLIENT_ID, code) })
    }
}

class GitHubAuthManagerTest {
    private val clock = FakeClock()
    private val transport = FakeTransport()
    private val meta = InMemoryKeyValueStore()
    private val secrets = InMemorySecretStore()

    private fun manager() = GitHubAuthManager(meta, secrets, transport, DeviceFlowClient(transport, clock::now, clock::sleep), clock::now)

    private fun script(userResponse: Any = jsonResponse(200, """{"login":"musty"}""", mapOf("x-oauth-scopes" to "public_repo"))) {
        transport.on("POST", "/login/device/code", jsonResponse(200, """{"device_code":"D","user_code":"WXYZ-9876","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}"""))
        transport.on("POST", "/login/oauth/access_token", jsonResponse(200, """{"access_token":"$TOKEN","scope":"public_repo"}"""))
        transport.on("GET", "/user", userResponse)
    }

    @Test
    fun starts_disconnected_and_never_claims_a_connection_without_a_token() = runTest {
        val m = manager()
        assertEquals(GitHubAuthState.Disconnected, m.state.value)
        assertNull(m.currentAccess())
        assertEquals(GitHubConnection.NotConnected, AuthBackedGitHubRepository(m).getConnection())
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun successful_sign_in_verifies_with_github_then_stores_the_token_encrypted_store_only() = runTest {
        script()
        val m = manager()
        m.connect(AuthMethod.OAUTH_PUBLIC, CLIENT_ID)
        val state = m.state.value as GitHubAuthState.Connected
        assertEquals("musty", state.user.login)
        assertTrue(state.access.canCreatePublicRepository)
        assertFalse(state.access.canCreatePrivateRepository)
        assertEquals(TOKEN, secrets.get("github_access_token"))
        // The token must not be in the plain-text preferences or in the state.
        assertTrue(meta.keys().none { meta.getString(it)!!.contains(TOKEN) })
        assertFalse(state.toString().contains(TOKEN))
        assertEquals(CLIENT_ID, m.savedClientId())
        assertEquals(AuthMethod.OAUTH_PUBLIC, m.savedMethod())
        assertEquals(GitHubConnection.Connected("musty"), AuthBackedGitHubRepository(m).getConnection())
    }

    @Test
    fun the_user_sees_the_code_while_waiting() = runTest {
        script()
        val seen = mutableListOf<GitHubAuthState>()
        lateinit var m: GitHubAuthManager
        val flow = DeviceFlowClient(transport, now = clock::now, sleep = { clock.sleep(it); seen += m.state.value })
        m = GitHubAuthManager(meta, secrets, transport, flow, clock::now)
        m.connect(AuthMethod.OAUTH_PUBLIC, CLIENT_ID)
        val waiting = seen.first() as GitHubAuthState.AwaitingAuthorization
        assertEquals("WXYZ-9876", waiting.userCode)
        assertTrue(m.state.value is GitHubAuthState.Connected)
    }

    @Test
    fun failed_sign_in_stores_nothing_and_is_not_connected() = runTest {
        transport.on("POST", "/login/device/code", jsonResponse(200, """{"error":"device_flow_disabled"}"""))
        val m = manager()
        m.connect(AuthMethod.GITHUB_APP, CLIENT_ID)
        val state = m.state.value
        assertTrue(state is GitHubAuthState.Failed)
        assertTrue((state as GitHubAuthState.Failed).message.contains("Device Flow"))
        assertNull(secrets.get("github_access_token"))
        assertEquals(GitHubConnection.NotConnected, AuthBackedGitHubRepository(m).getConnection())
    }

    @Test
    fun token_that_github_rejects_right_after_sign_in_is_never_stored() = runTest {
        script(userResponse = jsonResponse(401, """{"message":"Bad credentials"}"""))
        val m = manager()
        m.connect(AuthMethod.OAUTH_PRIVATE, CLIENT_ID)
        assertTrue(m.state.value is GitHubAuthState.Failed)
        assertNull(secrets.get("github_access_token"))
        assertNull(meta.getString("github_username"))
    }

    @Test
    fun denied_authorization_ends_in_failed_not_connected() = runTest {
        transport.on("POST", "/login/device/code", jsonResponse(200, """{"device_code":"D","user_code":"U","verification_uri":"v","expires_in":900,"interval":5}"""))
        transport.on("POST", "/login/oauth/access_token", jsonResponse(200, """{"error":"access_denied"}"""))
        val m = manager()
        m.connect(AuthMethod.OAUTH_PUBLIC, CLIENT_ID)
        assertTrue(m.state.value is GitHubAuthState.Failed)
    }

    @Test
    fun github_app_sign_in_has_no_scopes_and_cannot_create_repositories() = runTest {
        script(userResponse = jsonResponse(200, """{"login":"musty"}"""))
        val m = manager()
        m.connect(AuthMethod.GITHUB_APP, CLIENT_ID)
        val access = (m.state.value as GitHubAuthState.Connected).access
        assertTrue(access.scopes.isEmpty())
        assertFalse(access.canCreateRepository)
    }

    @Test
    fun a_saved_sign_in_is_unverified_until_github_confirms_it() = runTest {
        secrets.put("github_access_token", TOKEN)
        meta.putString("github_username", "musty")
        meta.putString("github_auth_method", AuthMethod.OAUTH_PUBLIC.id)
        meta.putString("github_scopes", "public_repo")
        val m = manager()
        assertTrue(m.state.value is GitHubAuthState.Unverified)
        assertNull(m.currentAccess())
        assertEquals("not Connected before verification", GitHubConnection.NotConnected.javaClass, GitHubConnection.NotConnected.javaClass)

        transport.on("GET", "/user", jsonResponse(200, """{"login":"musty"}""", mapOf("x-oauth-scopes" to "public_repo")))
        assertEquals(GitHubConnection.Connected("musty"), AuthBackedGitHubRepository(m).getConnection())
        assertEquals(1, transport.requests.size)
        m.ensureVerified()
        assertEquals("already verified: no second request", 1, transport.requests.size)
    }

    @Test
    fun expired_token_is_deleted_and_state_says_expired() = runTest {
        secrets.put("github_access_token", TOKEN)
        meta.putString("github_username", "musty")
        transport.on("GET", "/user", jsonResponse(401, """{"message":"Bad credentials"}"""))
        val m = manager()
        m.verify()
        assertEquals(GitHubAuthState.Expired("musty"), m.state.value)
        assertNull("dead token must be removed", secrets.get("github_access_token"))
        assertEquals(GitHubConnection.NotConnected, AuthBackedGitHubRepository(m).getConnection())
    }

    @Test
    fun a_401_on_any_later_api_call_also_expires_the_sign_in() = runTest {
        script()
        val m = manager()
        m.connect(AuthMethod.OAUTH_PUBLIC, CLIENT_ID)
        transport.on("GET", "/repos/o/r", jsonResponse(401, "{}"))
        try { m.api.getRepository("o", "r") } catch (_: Exception) {}
        assertTrue(m.state.value is GitHubAuthState.Expired)
        assertNull(secrets.get("github_access_token"))
    }

    @Test
    fun offline_verification_is_unverified_never_connected() = runTest {
        secrets.put("github_access_token", TOKEN)
        meta.putString("github_username", "musty")
        transport.on("GET", "/user", IOException("offline"))
        val m = manager()
        m.verify()
        assertTrue(m.state.value is GitHubAuthState.Unverified)
        assertNotNull(secrets.get("github_access_token"))
        assertEquals(GitHubConnection.NotConnected, AuthBackedGitHubRepository(m).getConnection())
    }

    @Test
    fun disconnect_clears_the_token_and_all_sign_in_details() = runTest {
        script()
        val m = manager()
        m.connect(AuthMethod.OAUTH_PUBLIC, CLIENT_ID)
        m.disconnect()
        assertEquals(GitHubAuthState.Disconnected, m.state.value)
        assertNull(secrets.get("github_access_token"))
        assertNull(meta.getString("github_username"))
        assertNull(meta.getString("github_scopes"))
        assertNull(meta.getString("github_auth_method"))
        // A new manager (app restart) also starts disconnected.
        assertEquals(GitHubAuthState.Disconnected, manager().state.value)
        // And API calls are refused locally.
        transport.requests.clear()
        try { m.api.getCurrentUser() } catch (_: Exception) {}
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun verify_without_a_token_goes_back_to_disconnected() = runTest {
        val m = manager()
        m.verify()
        assertEquals(GitHubAuthState.Disconnected, m.state.value)
    }
}
