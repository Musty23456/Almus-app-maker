package com.almus.appcreatorpro.agent

import com.almus.appcreatorpro.ai.Parsed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentParsersTest {
    private fun plan(text: String, perms: AgentPermissions = AgentPermissions(), newProject: Boolean = false): Parsed<AgentPlan> = AgentPlanParser.parse(text, false, perms, newProject)
    private fun ok(p: Parsed<AgentPlan>): AgentPlan = (p as Parsed.Ok).value

    @Test fun `a plan is parsed and its tasks are renumbered`() {
        val p = ok(plan(planJson(taskSpec("a", "Structure", "scaffold"), taskSpec("b", "Screen", "ui", dependsOn = listOf("a")))))
        assertEquals(listOf("t1", "t2"), p.tasks.map { it.id })
        assertEquals(listOf("t1"), p.tasks[1].dependsOn)
        assertEquals(AgentTaskKind.SCAFFOLD, p.tasks[0].kind)
        assertEquals("You want notes.", p.understanding)
    }

    @Test fun `depends_on can only point at earlier tasks so a plan has no cycles`() {
        val p = ok(plan(planJson(taskSpec("a", "One", dependsOn = listOf("b")), taskSpec("b", "Two", dependsOn = listOf("a")))))
        assertTrue(p.tasks[0].dependsOn.isEmpty())
        assertEquals(listOf("t1"), p.tasks[1].dependsOn)
    }

    @Test fun `unknown kinds become write-code with a warning and unsafe paths are removed`() {
        val p = ok(plan(planJson(taskSpec("a", "Odd", "teleport", paths = listOf("../evil.kt", ".github/workflows/x.yml", FOO_PATH)))))
        assertEquals(AgentTaskKind.CODE, p.tasks[0].kind)
        assertEquals(listOf(FOO_PATH), p.tasks[0].paths)
        assertTrue(p.warnings.any { it.contains("unknown kind") })
        assertTrue(p.warnings.any { it.contains("unsafe file path") })
    }

    @Test fun `too many tasks are dropped with a warning`() {
        val many = (1..9).map { taskSpec("x$it", "Task $it") }.toTypedArray()
        val p = ok(plan(planJson(*many), AgentPermissions(maxTasks = 4)))
        assertEquals(4, p.tasks.size)
        assertTrue(p.warnings.any { it.contains("dropped") })
    }

    @Test fun `a plan without tasks or without a summary is refused`() {
        assertTrue(plan(json(mapOf("summary" to "s", "tasks" to emptyList<Any>()))) is Parsed.Bad)
        assertTrue(plan(json(mapOf("tasks" to listOf(taskSpec("a", "x"))))) is Parsed.Bad)
        assertTrue(plan("not json at all") is Parsed.Bad)
    }

    @Test fun `project name and package only come from a new-project plan`() {
        val extra = mapOf("project_name" to "notes app", "package_name" to "com.example.notes", "language" to "java")
        val existing = ok(plan(planJson(taskSpec("a", "x"), extra = extra)))
        assertEquals(null, existing.projectName)
        val fresh = ok(plan(planJson(taskSpec("a", "x"), extra = extra), newProject = true))
        assertTrue(fresh.projectName!!.isNotBlank())
        assertEquals("com.example.notes", fresh.packageName)
        assertEquals("java", fresh.language)
    }

    @Test fun `dependency advice accepts strings and objects and rejects malformed coordinates`() {
        val d = DependencyAdviceParser.parse(listOf("androidx.room:room-runtime:2.6.1", mapOf("coordinate" to "com.squareup.retrofit2:retrofit:2.11.0", "reason" to "HTTP"), "not a coordinate!", mapOf("coordinate" to "a:b:c:d")))
        assertEquals(listOf("androidx.room:room-runtime:2.6.1", "com.squareup.retrofit2:retrofit:2.11.0"), d.map { it.coordinate })
        assertEquals("HTTP", d[1].reason)
    }

    @Test fun `a task result with changes is parsed`() {
        val r = (AgentTaskResultParser.parse(resultJson(createChange(FOO_PATH, "x"), editChange(MAIN_PATH, "a", "b")), false) as Parsed.Ok).value
        assertEquals(2, r.changes.size)
        assertEquals("create", r.changes[0].action)
        assertEquals(1, r.changes[1].edits.size)
    }

    @Test fun `tool calls are parsed and capped`() {
        val calls = (1..9).map { "read_file" to mapOf("path" to "f$it.kt") }.toTypedArray()
        val r = (AgentTaskResultParser.parse(toolCallsJson(*calls), false) as Parsed.Ok).value
        assertEquals(AgentTaskResultParser.MAX_TOOL_CALLS, r.toolCalls.size)
        assertEquals("f1.kt", r.toolCalls[0].args["path"])
        assertTrue(r.changes.isEmpty())
    }

    @Test fun `an answer with nothing in it is refused and a cut-off answer is reported`() {
        assertTrue(AgentTaskResultParser.parse("{}", false) is Parsed.Bad)
        assertTrue(AgentTaskResultParser.parse("{\"summary\": \"x\", \"changes\": [", true) is Parsed.Bad)
        assertFalse(AgentTaskResultParser.parse(json(mapOf("summary" to "analysis only")), false) is Parsed.Bad)
    }
}
