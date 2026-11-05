package com.almus.appcreatorpro.github

import com.almus.appcreatorpro.domain.github.AuthMethod
import com.almus.appcreatorpro.domain.github.CloudBuildConfig
import com.almus.appcreatorpro.domain.github.GrantedAccess
import com.almus.appcreatorpro.domain.github.WorkflowStatusMapper
import com.almus.appcreatorpro.domain.model.BuildStatus
import com.almus.appcreatorpro.service.github.HttpRequest
import com.almus.appcreatorpro.service.github.MiniJson
import com.almus.appcreatorpro.service.github.asArr
import com.almus.appcreatorpro.service.github.long
import com.almus.appcreatorpro.service.github.obj
import com.almus.appcreatorpro.service.github.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniJsonTest {
    @Test
    fun parses_nested_objects_arrays_and_numbers() {
        val o = MiniJson.parseObject("""{"a":{"b":[1,2,{"c":"x"}]},"n":-12,"d":1.5,"t":true,"f":false,"z":null}""")
        assertEquals(3, o.obj("a")!!["b"].asArr()!!.size)
        assertEquals(-12L, o.long("n"))
        assertEquals(1.5, o["d"])
        assertEquals(true, o["t"])
        assertNull(o["z"])
    }

    @Test
    fun parses_escapes_and_unicode() {
        val o = MiniJson.parseObject("""{"s":"line\nTab\t\"q\" é \\"}""")
        assertEquals("line\nTab\t\"q\" é \\", o.str("s"))
    }

    @Test
    fun round_trips_special_characters() {
        val original = mapOf("k" to "a\"b\\c\n\u0001é", "list" to listOf(1L, "two", null, true))
        val parsed = MiniJson.parseObject(MiniJson.stringify(original))
        assertEquals(original["k"], parsed.str("k"))
        assertEquals(listOf<Any?>(1L, "two", null, true), parsed["list"])
    }

    @Test
    fun rejects_malformed_input() {
        listOf("", "{", "{\"a\":}", "[1,]x", "{\"a\":1} trailing", "nul", "{\"a\":\"unterminated}").forEach {
            val failed = runCatching { MiniJson.parse(it) }.exceptionOrNull() is MiniJson.JsonException
            assertTrue("should reject: $it", failed)
        }
    }

    @Test
    fun rejects_absurdly_deep_nesting() {
        val deep = "[".repeat(200) + "]".repeat(200)
        assertTrue(runCatching { MiniJson.parse(deep) }.exceptionOrNull() is MiniJson.JsonException)
    }

    @Test
    fun parse_object_requires_an_object() {
        assertTrue(runCatching { MiniJson.parseObject("[1]") }.exceptionOrNull() is MiniJson.JsonException)
    }
}

class WorkflowStatusMapperTest {
    @Test
    fun only_completed_plus_success_is_success() {
        assertEquals(BuildStatus.SUCCESS, WorkflowStatusMapper.map("completed", "success"))
        listOf("queued", "in_progress", "waiting", "requested", "pending", null).forEach { status ->
            assertFalse("status=$status must never map to SUCCESS", WorkflowStatusMapper.map(status, "success") == BuildStatus.SUCCESS)
        }
    }

    @Test
    fun maps_queued_and_running_states() {
        listOf("queued", "waiting", "requested", "pending", "something_new").forEach {
            assertEquals(BuildStatus.QUEUED, WorkflowStatusMapper.map(it, null))
        }
        assertEquals(BuildStatus.RUNNING, WorkflowStatusMapper.map("in_progress", null))
    }

    @Test
    fun maps_every_unsuccessful_conclusion() {
        assertEquals(BuildStatus.CANCELLED, WorkflowStatusMapper.map("completed", "cancelled"))
        listOf("failure", "timed_out", "startup_failure", "action_required", "skipped", "stale", "neutral", null).forEach {
            assertEquals("conclusion=$it", BuildStatus.FAILED, WorkflowStatusMapper.map("completed", it))
        }
    }

    @Test
    fun terminal_flags() {
        assertTrue(BuildStatus.SUCCESS.isTerminal && BuildStatus.FAILED.isTerminal && BuildStatus.CANCELLED.isTerminal)
        assertFalse(BuildStatus.PREPARING.isTerminal || BuildStatus.QUEUED.isTerminal || BuildStatus.RUNNING.isTerminal)
    }
}

class GrantedAccessTest {
    @Test
    fun github_app_can_never_create_repositories() {
        val access = GrantedAccess(AuthMethod.GITHUB_APP, emptySet())
        assertFalse(access.canCreateRepository)
        assertFalse(GrantedAccess(AuthMethod.GITHUB_APP, setOf("repo")).canCreatePrivateRepository)
    }

    @Test
    fun oauth_public_scope_allows_only_public_repositories() {
        val access = GrantedAccess(AuthMethod.OAUTH_PUBLIC, setOf("public_repo"))
        assertTrue(access.canCreatePublicRepository)
        assertFalse(access.canCreatePrivateRepository)
    }

    @Test
    fun oauth_repo_scope_allows_both_and_missing_scope_allows_none() {
        val full = GrantedAccess(AuthMethod.OAUTH_PRIVATE, setOf("repo"))
        assertTrue(full.canCreatePublicRepository && full.canCreatePrivateRepository)
        // Decided by what GitHub GRANTED, not what was requested.
        assertFalse(GrantedAccess(AuthMethod.OAUTH_PRIVATE, emptySet()).canCreateRepository)
    }

    @Test
    fun parses_scope_strings() {
        assertEquals(setOf("repo", "workflow"), GrantedAccess.parseScopes("repo, workflow"))
        assertEquals(setOf("a", "b"), GrantedAccess.parseScopes("a b"))
        assertTrue(GrantedAccess.parseScopes(null).isEmpty())
    }

    @Test
    fun oauth_methods_request_the_minimum_scope_and_never_workflow() {
        assertEquals(listOf("public_repo"), AuthMethod.OAUTH_PUBLIC.requestedScopes)
        assertEquals(listOf("repo"), AuthMethod.OAUTH_PRIVATE.requestedScopes)
        assertTrue(AuthMethod.GITHUB_APP.requestedScopes.isEmpty())
        assertTrue(AuthMethod.entries.none { "workflow" in it.requestedScopes })
    }

    @Test
    fun method_ids_round_trip() {
        AuthMethod.entries.forEach { assertEquals(it, AuthMethod.fromId(it.id)) }
        assertNull(AuthMethod.fromId("nope"))
    }
}

class CloudBuildConfigTest {
    @Test
    fun default_directory_is_a_safe_slug() {
        assertEquals("almus-projects/my-cool-app", CloudBuildConfig.defaultDir("My Cool App!"))
        assertEquals("almus-projects/project", CloudBuildConfig.defaultDir("!!!"))
    }

    @Test
    fun http_request_to_string_never_contains_the_token() {
        val request = HttpRequest("GET", "https://api.github.com/user", mapOf("Authorization" to "Bearer SECRET_TOKEN_VALUE"))
        assertFalse(request.toString().contains("SECRET_TOKEN_VALUE"))
    }
}
