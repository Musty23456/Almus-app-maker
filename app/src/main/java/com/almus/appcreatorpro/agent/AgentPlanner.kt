package com.almus.appcreatorpro.agent

import com.almus.appcreatorpro.ai.AiMessage
import com.almus.appcreatorpro.ai.AiRole
import com.almus.appcreatorpro.ai.AiStep
import com.almus.appcreatorpro.ai.AiTaskKind
import com.almus.appcreatorpro.ai.ContextBundle
import com.almus.appcreatorpro.ai.Parsed

/** Steps 1 to 3 in one request: restate the requirements, plan, break the plan into tasks. Used for existing and new projects. */
class AgentPlanner(private val client: AgentModelClient, private val perms: AgentPermissions) {

    suspend fun plan(
        goal: String,
        bundle: ContextBundle,
        messages: List<AgentMessage>,
        previous: AgentPlan?,
        newProject: Boolean,
        onChunk: (Int, String) -> Unit = { _, _ -> },
    ): AiStep<AgentPlan> {
        val conversation = ArrayList<AiMessage>()
        conversation += AiMessage(AiRole.USER, AgentPrompts.planRequest(goal, bundle, messages, previous, newProject))
        var retries = 0
        while (true) {
            val completion = when (val r = client.ask(AiTaskKind.AGENT_PLAN, conversation, onChunk)) {
                is AiStep.Ok -> r.value
                is AiStep.Failed -> return AiStep.Failed(r.message, r.retryable)
            }
            when (val parsed = AgentPlanParser.parse(completion.text, completion.truncated, perms, newProject)) {
                is Parsed.Ok -> return AiStep.Ok(parsed.value)
                is Parsed.Bad -> {
                    if (retries >= perms.maxParseRetries) return AiStep.Failed(parsed.reason, retryable = true)
                    retries++
                    conversation += AiMessage(AiRole.MODEL, completion.text.take(1500).ifBlank { "(empty answer)" })
                    conversation += AiMessage(AiRole.USER, AgentPrompts.retryHint(parsed.reason, completion.truncated))
                }
            }
        }
    }
}
