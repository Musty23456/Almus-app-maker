package com.almus.appcreatorpro.data

import com.almus.appcreatorpro.data.repository.PrototypeGitHubRepository
import com.almus.appcreatorpro.data.repository.SampleBuildRepository
import com.almus.appcreatorpro.data.sample.SampleData
import com.almus.appcreatorpro.domain.error.FeatureNotImplementedException
import com.almus.appcreatorpro.domain.model.BuildStatus
import com.almus.appcreatorpro.domain.model.GitHubConnection
import com.almus.appcreatorpro.service.build.PrototypeCloudBuildService
import com.almus.appcreatorpro.testutil.testProject
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleDataTest {
    private val now = 1_000_000_000L

    @Test
    fun sample_projects_have_unique_ids_and_valid_timestamps() {
        val projects = SampleData.projects(now)
        assertEquals(3, projects.size)
        assertEquals(projects.size, projects.map { it.id }.toSet().size)
        assertTrue(projects.all { it.updatedAtMillis <= now && it.createdAtMillis <= it.updatedAtMillis })
    }

    @Test
    fun sample_builds_cover_main_statuses_and_are_marked_as_sample() {
        val builds = SampleData.builds(now)
        assertEquals(
            setOf(BuildStatus.RUNNING, BuildStatus.SUCCESS, BuildStatus.FAILED),
            builds.map { it.status }.toSet(),
        )
        assertTrue(builds.all { it.isSample })
    }

    @Test
    fun sample_builds_are_newest_first() {
        val started = SampleData.builds(now).map { it.startedAtMillis }
        assertEquals(started.sortedDescending(), started)
    }

    @Test
    fun running_build_has_no_duration_but_finished_builds_do() {
        val builds = SampleData.builds(now)
        assertTrue(builds.filter { it.status == BuildStatus.RUNNING }.all { it.durationSeconds == null })
        assertTrue(builds.filter { it.status != BuildStatus.RUNNING }.all { it.durationSeconds != null })
    }
}

class PrototypeServicesTest {

    @Test
    fun sample_build_repository_returns_sample_history() = runTest {
        val repo = SampleBuildRepository(clock = { 5_000_000L })
        val history = repo.getBuildHistory()
        assertEquals(4, history.size)
        assertTrue(history.all { it.isSample })
    }

    @Test
    fun github_prototype_is_never_connected() = runTest {
        assertEquals(GitHubConnection.NotConnected, PrototypeGitHubRepository().getConnection())
    }

    @Test
    fun cloud_build_prototype_is_unavailable_and_fails_honestly() = runTest {
        val service = PrototypeCloudBuildService()
        assertFalse(service.isAvailable)

        val result = service.requestBuild(testProject())
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is FeatureNotImplementedException)
    }
}
