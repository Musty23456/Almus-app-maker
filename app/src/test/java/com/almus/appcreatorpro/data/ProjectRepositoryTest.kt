package com.almus.appcreatorpro.data

import com.almus.appcreatorpro.data.repository.InMemoryProjectRepository
import com.almus.appcreatorpro.domain.error.ValidationException
import com.almus.appcreatorpro.testutil.testProject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProjectRepositoryTest {

    private fun repository(
        initial: List<com.almus.appcreatorpro.domain.model.Project> = emptyList(),
    ): InMemoryProjectRepository {
        var time = 100L
        var counter = 0
        return InMemoryProjectRepository(
            initialProjects = initial,
            clock = { time++ },
            idGenerator = { "id-${counter++}" },
        )
    }

    @Test
    fun starts_with_initial_projects() = runTest {
        val repo = repository(listOf(testProject("a"), testProject("b", name = "Other")))
        assertEquals(2, repo.getProjects().size)
    }

    @Test
    fun create_adds_project_with_trimmed_values() = runTest {
        val repo = repository()
        val created = repo.createProject("  New App  ", " com.example.newapp ", "  desc ")

        assertEquals("id-0", created.id)
        assertEquals("New App", created.name)
        assertEquals("com.example.newapp", created.packageName)
        assertEquals("desc", created.description)
        assertEquals(listOf(created), repo.getProjects())
    }

    @Test
    fun projects_are_sorted_newest_first() = runTest {
        val repo = repository(
            listOf(
                testProject("old", name = "Old", updatedAtMillis = 1L),
                testProject("new", name = "New", updatedAtMillis = 9L),
            ),
        )
        assertEquals(listOf("new", "old"), repo.getProjects().map { it.id })
    }

    @Test
    fun create_rejects_duplicate_name() = runTest {
        val repo = repository(listOf(testProject(name = "Existing")))
        try {
            repo.createProject("existing", "com.example.other", "")
            fail("Expected ValidationException")
        } catch (e: ValidationException) {
            assertTrue(e.message!!.contains("already exists"))
        }
    }

    @Test
    fun create_rejects_invalid_package() = runTest {
        val repo = repository()
        try {
            repo.createProject("Valid Name", "notapackage", "")
            fail("Expected ValidationException")
        } catch (e: ValidationException) {
            // expected
        }
        assertTrue(repo.getProjects().isEmpty())
    }

    @Test
    fun delete_removes_only_the_given_project() = runTest {
        val repo = repository(listOf(testProject("a", name = "A App"), testProject("b", name = "B App")))
        repo.deleteProject("a")
        assertEquals(listOf("b"), repo.getProjects().map { it.id })
    }

    @Test
    fun delete_unknown_id_changes_nothing() = runTest {
        val repo = repository(listOf(testProject("a")))
        repo.deleteProject("missing")
        assertEquals(1, repo.getProjects().size)
    }

    @Test
    fun observe_reflects_changes() = runTest {
        val repo = repository()
        assertTrue(repo.observeProjects().first().isEmpty())
        repo.createProject("Observed", "com.example.observed", "")
        assertEquals(1, repo.observeProjects().first().size)
    }
}
