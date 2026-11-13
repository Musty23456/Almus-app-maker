package com.almus.appcreatorpro.workspace

import com.almus.appcreatorpro.ai.AiRole
import com.almus.appcreatorpro.ai.ChatTurn
import com.almus.appcreatorpro.ai.TurnState

/** The marker lines that tell the app "this part of the answer is a plan". Pure text handling, easy to test. */
object WorkspacePlans {
    const val PLAN_OPEN = "<<<PLAN>>>"
    const val PLAN_CLOSE = "<<<END_PLAN>>>"

    /** The text of the user turn that records approval. Only the "Approve plan" button creates it. */
    const val APPROVAL_TEXT = "I approve this plan."

    /** An answer split around its plan. [plan] is null when the answer has no complete plan. */
    data class Split(val before: String, val plan: String?, val after: String)

    /**
     * The plan inside [text], or null. A plan counts only when BOTH markers are present, so an answer that was cut off
     * in the middle of a plan can never be approved.
     */
    fun extract(text: String): String? = split(text).plan

    fun split(text: String): Split {
        val open = text.indexOf(PLAN_OPEN)
        if (open < 0) return Split(text.trim(), null, "")
        val close = text.indexOf(PLAN_CLOSE, open + PLAN_OPEN.length)
        if (close < 0) {
            // Still streaming, or cut off: show it as text without the marker, but it is not an approvable plan.
            return Split((text.substring(0, open) + text.substring(open + PLAN_OPEN.length)).trim(), null, "")
        }
        val plan = text.substring(open + PLAN_OPEN.length, close).trim()
        val before = text.substring(0, open).trim()
        val after = text.substring(close + PLAN_CLOSE.length).trim()
        return Split(before, plan.ifEmpty { null }, after)
    }

    /** Where the conversation stands. A new message after an approval means the plan must be approved again. */
    fun stageOf(turns: List<ChatTurn>): WorkspaceStage {
        val last = turns.lastOrNull() ?: return WorkspaceStage.DISCUSSING
        if (last.role == AiRole.USER && last.text == APPROVAL_TEXT) {
            val before = turns.getOrNull(turns.lastIndex - 1)
            return if (before != null && isPlanTurn(before)) WorkspaceStage.APPROVED else WorkspaceStage.DISCUSSING
        }
        return if (isPlanTurn(last)) WorkspaceStage.PLAN_READY else WorkspaceStage.DISCUSSING
    }

    /** The plan the user is looking at: the latest plan, but only while it is ready or approved. */
    fun currentPlan(turns: List<ChatTurn>): String? = when (stageOf(turns)) {
        WorkspaceStage.DISCUSSING -> null
        WorkspaceStage.PLAN_READY -> turns.lastOrNull()?.let { extract(it.text) }
        WorkspaceStage.APPROVED -> turns.getOrNull(turns.lastIndex - 1)?.let { extract(it.text) }
    }

    private fun isPlanTurn(t: ChatTurn): Boolean =
        t.role == AiRole.MODEL && t.state == TurnState.DONE && extract(t.text) != null

    /** The goal handed to the Agent after approval: what the user asked for, then the plan they approved. */
    fun agentGoal(workspace: RepoWorkspace, plan: String, maxChars: Int): String {
        val head = "Improve the existing project ${workspace.fullName} (branch ${workspace.branch}). Follow ONLY this plan, which the user approved:\n\n"
        val room = (maxChars - head.length).coerceAtLeast(0)
        return head + if (plan.length <= room) plan else plan.take(room)
    }
}
