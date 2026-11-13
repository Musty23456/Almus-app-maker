package com.almus.appcreatorpro.repair

import com.almus.appcreatorpro.ai.AiChangeManager
import com.almus.appcreatorpro.ai.InMemoryChangeHistoryStore
import com.almus.appcreatorpro.debug.CodeEdit
import com.almus.appcreatorpro.debug.TextEdits
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepairSessionTest {
    private val issue = diagnose()
    private val fixed = MAIN_TEXT.replace("        foo()", "        println(\"foo\")")

    private suspend fun prepared(env: Env = Env(), vararg extra: com.almus.appcreatorpro.debug.FixProposal): Pair<Env, RepairSession> {
        val s = env.session()
        s.prepare(issue, listOf(proposal(issue, "1")) + extra)
        return env to s
    }

    // ---- never overwrite without approval ----------------------------------------------------------------------------

    @Test fun `preparing a plan does not change any file`() = runTest {
        val (env, s) = prepared()
        assertEquals(RepairPhase.AWAITING_APPROVAL, s.state.value.phase)
        assertTrue(env.files.writes.isEmpty())
        assertEquals(MAIN_TEXT, env.files.files[MAIN])
        assertEquals(FileDecision.PENDING, s.state.value.decisionOf(MAIN))
    }

    @Test fun `accepting alone writes nothing`() = runTest {
        val (env, s) = prepared()
        assertTrue(s.accept(MAIN))
        assertTrue(env.files.writes.isEmpty())
        assertEquals(MAIN_TEXT, env.files.files[MAIN])
    }

    @Test fun `apply with nothing accepted writes nothing`() = runTest {
        val (env, s) = prepared()
        assertEquals(RepairApplyOutcome.NothingAccepted, s.apply())
        assertTrue(env.files.writes.isEmpty()); assertTrue(env.backups.saved.isEmpty())
        assertTrue(env.changes.history.value.isEmpty())
    }

    @Test fun `apply without a plan does nothing`() = runTest {
        assertEquals(RepairApplyOutcome.NoPlan, Env().session().apply())
    }

    // ---- acceptance ---------------------------------------------------------------------------------------------------

    @Test fun `accepted files are written, recorded and the originals are saved first`() = runTest {
        val (env, s) = prepared()
        s.accept(MAIN)
        val r = s.apply() as RepairApplyOutcome.Applied
        assertEquals(fixed, env.files.files[MAIN])
        assertEquals(listOf(MAIN), r.paths)
        // original preserved
        assertEquals(MAIN_TEXT, env.backups.load("p1", r.applyId)!![MAIN])
        // change history
        val rec = env.changes.history.value.single()
        assertEquals(MAIN_TEXT, rec.files.single().before); assertEquals(fixed, rec.files.single().after)
        // repair record
        val e = s.state.value.log.single()
        assertEquals(RepairEvent.APPLIED, e.event); assertEquals(MAIN, e.path); assertEquals(r.applyId, e.applyId)
        assertEquals(TextEdits.hash(MAIN_TEXT), e.beforeHash); assertEquals(TextEdits.hash(fixed), e.afterHash)
        assertEquals(1, e.added); assertEquals(1, e.removed)
        assertEquals(FileDecision.APPLIED, s.state.value.decisionOf(MAIN))
    }

    @Test fun `applying never claims the error is fixed`() = runTest {
        val (_, s) = prepared()
        s.accept(MAIN); s.apply()
        val st = s.state.value
        assertEquals(RepairPhase.APPLIED_UNVERIFIED, st.phase)
        assertTrue(st.message!!.contains(RepairText.NOT_VERIFIED))
        assertTrue(RepairPhase.APPLIED_UNVERIFIED.label.contains("not verified"))
    }

    @Test fun `individual files can be accepted separately`() = runTest {
        val util = proposal(issue, "u", path = UTIL, text = UTIL_TEXT, edit = CodeEdit.replace(4, "    return 1"))
        val (env, s) = prepared(Env(), util)
        s.accept(UTIL)
        s.apply()
        assertEquals(MAIN_TEXT, env.files.files[MAIN])
        assertTrue(env.files.files[UTIL]!!.contains("return 1"))
        assertEquals(FileDecision.PENDING, s.state.value.decisionOf(MAIN))
        // the other file can still be accepted and applied afterwards
        s.accept(MAIN)
        assertTrue(s.apply() is RepairApplyOutcome.Applied)
        assertEquals(fixed, env.files.files[MAIN])
        assertEquals(2, env.changes.history.value.size)
    }

    @Test fun `accept all valid skips blocked patches`() = runTest {
        val bad = proposal(issue, "bad", edit = CodeEdit.replace(7, "        val a = (1"), title = "Bad")
        val util = proposal(issue, "u", path = UTIL, text = UTIL_TEXT, edit = CodeEdit.replace(4, "    return 1"))
        val s = Env().session()
        s.prepare(issue, listOf(bad, util))
        assertEquals(1, s.acceptAllValid())
        assertEquals(FileDecision.ACCEPTED, s.state.value.decisionOf(UTIL))
        assertEquals(FileDecision.PENDING, s.state.value.decisionOf(MAIN))
    }

    @Test fun `a patch that breaks a check cannot be accepted or applied`() = runTest {
        val bad = proposal(issue, "bad", edit = CodeEdit.replace(FOO_LINE, "        foo("))
        val env = Env(); val s = env.session()
        s.prepare(issue, listOf(bad))
        assertFalse(s.accept(MAIN))
        assertNotNull(s.state.value.message)
        assertEquals(RepairApplyOutcome.NothingAccepted, s.apply())
        assertTrue(env.files.writes.isEmpty())
    }

    // ---- rejection -----------------------------------------------------------------------------------------------------

    @Test fun `rejecting keeps the file untouched and is recorded`() = runTest {
        val (env, s) = prepared()
        assertTrue(s.reject(MAIN))
        assertEquals(FileDecision.REJECTED, s.state.value.decisionOf(MAIN))
        assertEquals(RepairEvent.REJECTED, s.state.value.log.single().event)
        assertEquals(RepairApplyOutcome.NothingAccepted, s.apply())
        assertTrue(env.files.writes.isEmpty()); assertEquals(MAIN_TEXT, env.files.files[MAIN])
    }

    @Test fun `an accepted file that is then rejected is not written`() = runTest {
        val (env, s) = prepared()
        s.accept(MAIN); s.reject(MAIN)
        assertEquals(RepairApplyOutcome.NothingAccepted, s.apply())
        assertTrue(env.files.writes.isEmpty())
    }

    @Test fun `a rejected file can be reconsidered`() = runTest {
        val (env, s) = prepared()
        s.reject(MAIN); s.clearDecision(MAIN)
        assertEquals(FileDecision.PENDING, s.state.value.decisionOf(MAIN))
        assertTrue(s.accept(MAIN)); assertTrue(s.apply() is RepairApplyOutcome.Applied)
        assertEquals(fixed, env.files.files[MAIN])
    }

    @Test fun `reject all records every file`() = runTest {
        val util = proposal(issue, "u", path = UTIL, text = UTIL_TEXT, edit = CodeEdit.replace(4, "    return 1"))
        val (env, s) = prepared(Env(), util)
        assertEquals(2, s.rejectAllPending())
        assertEquals(2, s.state.value.log.count { it.event == RepairEvent.REJECTED })
        assertTrue(env.files.writes.isEmpty())
    }

    // ---- refusals --------------------------------------------------------------------------------------------------------

    @Test fun `a file edited after planning makes apply refuse and write nothing`() = runTest {
        val (env, s) = prepared()
        s.accept(MAIN)
        env.files.files[MAIN] = MAIN_TEXT + "// my edit\n"
        val r = s.apply()
        assertTrue(r is RepairApplyOutcome.Stale)
        assertEquals(MAIN_TEXT + "// my edit\n", env.files.files[MAIN]); assertTrue(env.files.writes.isEmpty())
        assertTrue(s.state.value.log.none { it.event == RepairEvent.APPLIED })
        assertTrue(env.changes.history.value.isEmpty())
    }

    @Test fun `if the originals cannot be saved nothing is written`() = runTest {
        val (env, s) = prepared()
        env.backups.failSaves = true
        s.accept(MAIN)
        assertTrue(s.apply() is RepairApplyOutcome.BackupFailed)
        assertTrue(env.files.writes.isEmpty()); assertEquals(MAIN_TEXT, env.files.files[MAIN])
    }

    @Test fun `a write failure leaves the project as it was`() = runTest {
        val env = Env(failWritesTo = setOf(UTIL))
        val util = proposal(issue, "u", path = UTIL, text = UTIL_TEXT, edit = CodeEdit.replace(4, "    return 1"))
        val s = env.session()
        s.prepare(issue, listOf(proposal(issue, "1"), util))
        s.acceptAllValid()
        assertTrue(s.apply() is RepairApplyOutcome.Failed)
        assertEquals(MAIN_TEXT, env.files.files[MAIN]); assertEquals(UTIL_TEXT, env.files.files[UTIL])
        assertTrue(s.state.value.log.none { it.event == RepairEvent.APPLIED })
    }

    // ---- rollback ------------------------------------------------------------------------------------------------------------

    @Test fun `rollback restores the original text exactly and is recorded`() = runTest {
        val (env, s) = prepared()
        s.accept(MAIN)
        val applied = s.apply() as RepairApplyOutcome.Applied
        val r = s.rollback(applied.applyId) as RepairRollbackOutcome.RolledBack
        assertEquals(MAIN_TEXT, env.files.files[MAIN])
        assertEquals(listOf(MAIN), r.paths)
        assertEquals(RepairEvent.ROLLED_BACK, s.state.value.log.first().event)
        assertEquals(FileDecision.ROLLED_BACK, s.state.value.decisionOf(MAIN))
        assertEquals(RepairRollbackOutcome.AlreadyRolledBack, s.rollback(applied.applyId))
        assertTrue(RepairLogs.appliedRepairs(s.state.value.log).single().rolledBack)
    }

    @Test fun `after a rollback the patch can be accepted and applied again`() = runTest {
        val (env, s) = prepared()
        s.accept(MAIN)
        val applied = s.apply() as RepairApplyOutcome.Applied
        s.rollback(applied.applyId)
        assertTrue(s.accept(MAIN))
        assertTrue(s.apply() is RepairApplyOutcome.Applied)
        assertEquals(fixed, env.files.files[MAIN])
    }

    @Test fun `rollback refuses to throw away later edits`() = runTest {
        val (env, s) = prepared()
        s.accept(MAIN)
        val applied = s.apply() as RepairApplyOutcome.Applied
        val edited = fixed + "// later edit\n"
        env.files.files[MAIN] = edited
        val r = s.rollback(applied.applyId)
        assertEquals(RepairRollbackOutcome.Conflict(listOf(MAIN)), r)
        assertEquals(edited, env.files.files[MAIN])
    }

    @Test fun `a forced rollback saves the current text first`() = runTest {
        val (env, s) = prepared()
        s.accept(MAIN)
        val applied = s.apply() as RepairApplyOutcome.Applied
        val edited = fixed + "// later edit\n"
        env.files.files[MAIN] = edited
        assertTrue(s.rollback(applied.applyId, force = true) is RepairRollbackOutcome.RolledBack)
        assertEquals(MAIN_TEXT, env.files.files[MAIN])
        assertTrue(env.backups.saved.values.any { it[MAIN] == edited })
    }

    @Test fun `rollback works from the saved originals when the change history is gone`() = runTest {
        val (env, s) = prepared()
        s.accept(MAIN)
        val applied = s.apply() as RepairApplyOutcome.Applied
        // a new run of the app: fresh change history, same repair record and backups
        val fresh = RepairSession("p1", env.files, AiChangeManager("p1", env.files, InMemoryChangeHistoryStore()), env.log, env.backups)
        fresh.load()
        assertTrue(fresh.rollback(applied.applyId) is RepairRollbackOutcome.RolledBack)
        assertEquals(MAIN_TEXT, env.files.files[MAIN])
    }

    @Test fun `rollback from backups also refuses to lose later edits`() = runTest {
        val (env, s) = prepared()
        s.accept(MAIN)
        val applied = s.apply() as RepairApplyOutcome.Applied
        env.files.files[MAIN] = fixed + "// later\n"
        val fresh = RepairSession("p1", env.files, AiChangeManager("p1", env.files, InMemoryChangeHistoryStore()), env.log, env.backups)
        fresh.load()
        assertTrue(fresh.rollback(applied.applyId) is RepairRollbackOutcome.Conflict)
        assertEquals(fixed + "// later\n", env.files.files[MAIN])
    }

    @Test fun `rolling back an unknown repair does nothing`() = runTest {
        val (env, s) = prepared()
        assertEquals(RepairRollbackOutcome.NotFound, s.rollback("nope"))
        assertTrue(env.files.writes.isEmpty())
    }

    // ---- record ---------------------------------------------------------------------------------------------------------------

    @Test fun `every change is recorded in order and survives a new session`() = runTest {
        val util = proposal(issue, "u", path = UTIL, text = UTIL_TEXT, edit = CodeEdit.replace(4, "    return 1"))
        val (env, s) = prepared(Env(), util)
        s.accept(MAIN); s.reject(UTIL)
        val applied = s.apply() as RepairApplyOutcome.Applied
        s.rollback(applied.applyId)
        val events = s.state.value.log.map { it.event }
        assertEquals(listOf(RepairEvent.ROLLED_BACK, RepairEvent.APPLIED, RepairEvent.REJECTED), events)
        val again = env.session(); again.load()
        assertEquals(events, again.state.value.log.map { it.event })
    }

    @Test fun `discarding the plan keeps the record`() = runTest {
        val (_, s) = prepared()
        s.reject(MAIN); s.discard()
        assertEquals(RepairPhase.NONE, s.state.value.phase)
        assertNull(s.state.value.plan)
        assertEquals(1, s.state.value.log.size)
    }

    @Test fun `a plan without patches says there is nothing to apply`() = runTest {
        val s = Env().session()
        s.prepare(issue, emptyList())
        assertEquals(RepairPhase.NO_PATCH, s.state.value.phase)
        assertEquals(RepairApplyOutcome.NothingAccepted, s.apply())
    }

    @Test fun `preview shows the original and the modified text`() = runTest {
        val (_, s) = prepared()
        val p = s.preview(MAIN)!!
        assertEquals(MAIN_TEXT, p.originalText); assertEquals(fixed, p.modifiedText)
        assertEquals(1, p.hunks); assertEquals(1, p.added); assertEquals(1, p.removed)
        assertNull(s.preview("nope"))
    }
}
