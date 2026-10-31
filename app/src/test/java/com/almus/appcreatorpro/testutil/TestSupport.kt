package com.almus.appcreatorpro.testutil

import com.almus.appcreatorpro.domain.model.Project
import com.almus.appcreatorpro.domain.model.ProjectType
import com.almus.appcreatorpro.domain.model.StorageInfo
import com.almus.appcreatorpro.domain.repository.ProjectRepository
import com.almus.appcreatorpro.domain.repository.StorageInfoProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Replaces Dispatchers.Main so ViewModels (viewModelScope) can run in plain JVM unit tests. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

fun testProject(
    id: String = "p1",
    name: String = "Test Project",
    packageName: String = "com.example.test",
    updatedAtMillis: Long = 1_000L,
): Project = Project(
    id = id,
    name = name,
    packageName = packageName,
    description = "",
    createdAtMillis = 0L,
    updatedAtMillis = updatedAtMillis,
)

class FakeStorageInfoProvider(
    private val info: StorageInfo = StorageInfo(totalBytes = 1_000L, freeBytes = 400L, appDataBytes = 50L),
) : StorageInfoProvider {
    override fun getStorageInfo(): StorageInfo = info
}

/** Repository whose every operation fails; used to test error states. */
class FailingProjectRepository : ProjectRepository {
    override fun observeProjects(): Flow<List<Project>> = flow { throw IllegalStateException("boom") }
    override suspend fun getProjects(): List<Project> = throw IllegalStateException("boom")
    override suspend fun getProject(id: String): Project? = throw IllegalStateException("boom")
    override suspend fun createProject(
        name: String,
        packageName: String,
        description: String,
        type: ProjectType,
    ): Project = throw IllegalStateException("boom")

    override suspend fun renameProject(id: String, newName: String): Project = throw IllegalStateException("boom")

    override suspend fun deleteProject(id: String) = throw IllegalStateException("boom")
}
