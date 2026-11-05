package com.almus.appcreatorpro.domain

import com.almus.appcreatorpro.data.repository.InMemoryProjectRepository
import com.almus.appcreatorpro.domain.error.ValidationException
import com.almus.appcreatorpro.domain.usecase.CreateProjectUseCase
import com.almus.appcreatorpro.testutil.FailingProjectRepository
import com.almus.appcreatorpro.testutil.testProject
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateProjectUseCaseTest {

    @Test
    fun valid_input_creates_project() = runTest {
        val repo = InMemoryProjectRepository()
        val result = CreateProjectUseCase(repo)("My App", "com.example.myapp", "hello")

        assertTrue(result.isSuccess)
        assertEquals("My App", result.getOrNull()?.name)
        assertEquals(1, repo.getProjects().size)
    }

    @Test
    fun invalid_name_fails_without_touching_repository() = runTest {
        val repo = InMemoryProjectRepository()
        val result = CreateProjectUseCase(repo)("", "com.example.app")

        assertTrue(result.exceptionOrNull() is ValidationException)
        assertTrue(repo.getProjects().isEmpty())
    }

    @Test
    fun invalid_package_fails() = runTest {
        val result = CreateProjectUseCase(InMemoryProjectRepository())("Good Name", "bad")
        assertTrue(result.exceptionOrNull() is ValidationException)
    }

    @Test
    fun duplicate_name_fails() = runTest {
        val repo = InMemoryProjectRepository(listOf(testProject(name = "Taken Name")))
        val result = CreateProjectUseCase(repo)("taken name", "com.example.other")
        assertTrue(result.exceptionOrNull() is ValidationException)
    }

    @Test
    fun repository_failure_is_returned_as_failure_not_thrown() = runTest {
        val result = CreateProjectUseCase(FailingProjectRepository())("Good Name", "com.example.good")
        assertTrue(result.isFailure)
    }
}
