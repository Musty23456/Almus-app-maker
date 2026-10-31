package com.almus.appcreatorpro.data.repository

import com.almus.appcreatorpro.data.storage.KeyValueStore
import com.almus.appcreatorpro.domain.github.CloudBuildConfig
import com.almus.appcreatorpro.service.github.MiniJson
import com.almus.appcreatorpro.service.github.str

/**
 * Remembers which repository, branch and folder each project builds in. Holds no secrets (so plain
 * app preferences are fine). Also remembers the last repository/branch to pre-fill new projects.
 */
class CloudBuildConfigStore(private val store: KeyValueStore) {

    fun get(projectId: String): CloudBuildConfig? =
        store.getString(key(projectId))?.let { parse(projectId, it) }

    fun save(config: CloudBuildConfig) {
        store.putString(key(config.projectId), serialize(config))
        store.putString(LAST_KEY, serialize(config))
    }

    /** Remembers a repository/branch choice made without a project (only pre-fills later projects). */
    fun saveLast(config: CloudBuildConfig) = store.putString(LAST_KEY, serialize(config))

    fun clear(projectId: String) = store.remove(key(projectId))

    /** The most recently used repository/branch, or null. Its [CloudBuildConfig.projectId] is blank. */
    fun last(): CloudBuildConfig? = store.getString(LAST_KEY)?.let { parse("", it) }

    private fun key(projectId: String) = "cloudcfg_" + projectId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun serialize(c: CloudBuildConfig) = MiniJson.stringify(
        linkedMapOf(
            "owner" to c.repoOwner, "repo" to c.repoName, "branch" to c.branch,
            "dir" to c.projectDir, "gradle" to c.gradleVersion,
        ),
    )

    private fun parse(projectId: String, text: String): CloudBuildConfig? {
        return try {
        val o = MiniJson.parseObject(text)
        CloudBuildConfig(
            projectId = projectId,
            repoOwner = o.str("owner") ?: return null,
            repoName = o.str("repo") ?: return null,
            branch = o.str("branch") ?: return null,
            projectDir = o.str("dir").orEmpty(),
            gradleVersion = o.str("gradle") ?: CloudBuildConfig.DEFAULT_GRADLE_VERSION,
        )
        } catch (e: MiniJson.JsonException) {
            null
        }
    }

    private companion object {
        const val LAST_KEY = "cloudcfg_last"
    }
}
