package com.almus.appcreatorpro.data.repository

import com.almus.appcreatorpro.data.sample.SampleData
import com.almus.appcreatorpro.domain.model.BuildRecord
import com.almus.appcreatorpro.domain.repository.BuildRepository
import kotlinx.coroutines.delay

/** PROTOTYPE: returns fake sample build history. Real GitHub Actions history arrives in Phase 2. */
class SampleBuildRepository(
    private val latencyMs: Long = 0L,
    private val clock: () -> Long = System::currentTimeMillis,
) : BuildRepository {

    override suspend fun getBuildHistory(): List<BuildRecord> {
        if (latencyMs > 0) delay(latencyMs)
        return SampleData.builds(clock())
    }
}
