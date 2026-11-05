package com.almus.appcreatorpro.data.repository

import com.almus.appcreatorpro.data.storage.KeyValueStore
import com.almus.appcreatorpro.domain.build.BuildConfiguration
import com.almus.appcreatorpro.domain.build.BuildOptions
import com.almus.appcreatorpro.domain.build.BuildVariant
import com.almus.appcreatorpro.domain.build.OutputFormat
import com.almus.appcreatorpro.service.github.MiniJson
import com.almus.appcreatorpro.service.github.bool
import com.almus.appcreatorpro.service.github.long
import com.almus.appcreatorpro.service.github.str

/** Stable stored form of a [BuildConfiguration]. Holds no secret (the signing profile is referenced by id only). */
object BuildConfigurationJson {
    fun toMap(c: BuildConfiguration): Map<String, Any?> = linkedMapOf(
        "variant" to c.variant.id,
        "customOutput" to c.customOutput.id,
        "customRelease" to c.customRelease,
        "customSign" to c.customSign,
        "applicationId" to c.applicationId,
        "versionName" to c.versionName,
        "versionCode" to c.versionCode?.toLong(),
        "minSdk" to c.minSdk?.toLong(),
        "targetSdk" to c.targetSdk?.toLong(),
        "minify" to c.options.minify,
        "shrinkResources" to c.options.shrinkResources,
        "signingProfileId" to c.signingProfileId,
    )

    fun toJson(c: BuildConfiguration): String = MiniJson.stringify(toMap(c))

    /** Unknown or missing fields fall back to the defaults; returns null only for text that is not JSON at all. */
    fun fromJson(text: String): BuildConfiguration? = try {
        fromMap(MiniJson.parseObject(text))
    } catch (e: MiniJson.JsonException) {
        null
    }

    fun fromMap(o: Map<String, Any?>): BuildConfiguration = BuildConfiguration(
        variant = BuildVariant.fromId(o.str("variant")) ?: BuildVariant.DEBUG_APK,
        customOutput = OutputFormat.fromId(o.str("customOutput")) ?: OutputFormat.APK,
        customRelease = o.bool("customRelease") ?: false,
        customSign = o.bool("customSign") ?: true,
        applicationId = o.str("applicationId"),
        versionName = o.str("versionName"),
        versionCode = o.long("versionCode")?.toInt(),
        minSdk = o.long("minSdk")?.toInt(),
        targetSdk = o.long("targetSdk")?.toInt(),
        options = BuildOptions(minify = o.bool("minify"), shrinkResources = o.bool("shrinkResources")),
        signingProfileId = o.str("signingProfileId"),
    )
}

/** Remembers the build configuration of each project (private preferences; no secrets inside). */
class BuildConfigurationStore(private val store: KeyValueStore) {

    fun get(projectId: String): BuildConfiguration =
        store.getString(key(projectId))?.let { BuildConfigurationJson.fromJson(it) } ?: BuildConfiguration.DEFAULT

    fun save(projectId: String, config: BuildConfiguration) {
        store.putString(key(projectId), BuildConfigurationJson.toJson(config))
    }

    fun clear(projectId: String) = store.remove(key(projectId))

    private fun key(projectId: String) = "buildcfg_" + projectId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
}
