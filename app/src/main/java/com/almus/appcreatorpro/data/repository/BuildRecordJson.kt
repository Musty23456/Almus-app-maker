package com.almus.appcreatorpro.data.repository

import com.almus.appcreatorpro.domain.model.ArtifactStatus
import com.almus.appcreatorpro.domain.model.BuildArtifactInfo
import com.almus.appcreatorpro.domain.model.BuildRecord
import com.almus.appcreatorpro.domain.model.BuildStatus
import com.almus.appcreatorpro.service.github.MiniJson
import com.almus.appcreatorpro.service.github.arr
import com.almus.appcreatorpro.service.github.asObj
import com.almus.appcreatorpro.service.github.bool
import com.almus.appcreatorpro.service.github.long
import com.almus.appcreatorpro.service.github.str

/** Stable on-disk form of a [BuildRecord]. Unknown/missing fields fall back to safe defaults. */
object BuildRecordJson {
    fun toJson(r: BuildRecord): String = MiniJson.stringify(
        linkedMapOf(
            "id" to r.id,
            "projectName" to r.projectName,
            "status" to r.status.name,
            "startedAtMillis" to r.startedAtMillis,
            "durationSeconds" to r.durationSeconds?.toLong(),
            "summary" to r.summary,
            "isSample" to r.isSample,
            "projectId" to r.projectId,
            "repoFullName" to r.repoFullName,
            "branch" to r.branch,
            "projectDir" to r.projectDir,
            "commitSha" to r.commitSha,
            "runId" to r.runId,
            "runUrl" to r.runUrl,
            "detail" to r.detail,
            "errorExcerpt" to r.errorExcerpt,
            "artifactNames" to r.artifactNames,
            "artifacts" to r.artifacts.map {
                linkedMapOf(
                    "id" to it.id,
                    "name" to it.name,
                    "sizeBytes" to it.sizeBytes,
                    "expiresAtMillis" to it.expiresAtMillis,
                    "status" to it.status.name,
                )
            },
            "finishedAtMillis" to r.finishedAtMillis,
            "pollingStopped" to r.pollingStopped,
        ),
    )

    /** Returns null for damaged data so one bad file cannot break the whole history. */
    fun fromJson(text: String): BuildRecord? = parse(text)

    private fun parse(text: String): BuildRecord? {
        return try {
        val o = MiniJson.parseObject(text)
        BuildRecord(
            id = o.str("id")?.takeIf { it.isNotBlank() } ?: return null,
            projectName = o.str("projectName").orEmpty(),
            status = BuildStatus.entries.firstOrNull { it.name == o.str("status") } ?: return null,
            startedAtMillis = o.long("startedAtMillis") ?: 0L,
            durationSeconds = o.long("durationSeconds")?.toInt(),
            summary = o.str("summary").orEmpty(),
            isSample = o.bool("isSample") ?: false,
            projectId = o.str("projectId"),
            repoFullName = o.str("repoFullName"),
            branch = o.str("branch"),
            projectDir = o.str("projectDir"),
            commitSha = o.str("commitSha"),
            runId = o.long("runId"),
            runUrl = o.str("runUrl"),
            detail = o.str("detail"),
            errorExcerpt = o.str("errorExcerpt"),
            artifactNames = o.arr("artifactNames").orEmpty().filterIsInstance<String>(),
            artifacts = o.arr("artifacts").orEmpty().mapNotNull { item ->
                val a = item.asObj() ?: return@mapNotNull null
                BuildArtifactInfo(
                    id = a.long("id") ?: return@mapNotNull null,
                    name = a.str("name") ?: return@mapNotNull null,
                    sizeBytes = a.long("sizeBytes") ?: 0L,
                    expiresAtMillis = a.long("expiresAtMillis"),
                    status = ArtifactStatus.entries.firstOrNull { it.name == a.str("status") } ?: ArtifactStatus.AVAILABLE,
                )
            },
            finishedAtMillis = o.long("finishedAtMillis"),
            pollingStopped = o.bool("pollingStopped") ?: false,
        )
        } catch (e: MiniJson.JsonException) {
            null
        }
    }
}
