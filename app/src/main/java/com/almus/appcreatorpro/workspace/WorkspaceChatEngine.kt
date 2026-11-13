package com.almus.appcreatorpro.workspace

import com.almus.appcreatorpro.ai.AiBackend
import com.almus.appcreatorpro.ai.AiException
import com.almus.appcreatorpro.ai.AiRequest
import com.almus.appcreatorpro.ai.AiRole
import com.almus.appcreatorpro.ai.AiStreamEvent
import com.almus.appcreatorpro.ai.AiTaskKind
import com.almus.appcreatorpro.ai.ChatHistoryStore
import com.almus.appcreatorpro.ai.ChatMessageBuilder
import com.almus.appcreatorpro.ai.ChatState
import com.almus.appcreatorpro.ai.ChatTurn
import com.almus.appcreatorpro.ai.TurnState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What one question needs besides the conversation: the instructions and the project context (already secret-free). */
class WorkspacePromptContext(val instructions: String, val projectContext: String?)

/**
 * The AI conversation of ONE repository workspace.
 *
 * Safety by construction: this class receives a backend, a chat-history store and a function that READS project context.
 * It has no file writer, no change manager and no agent, so it cannot modify a project. Approval ([approvePlan]) only adds
 * a line to the conversation; changing files is a separate step in the Agent, which asks the user again before applying.
 */
class WorkspaceChatEngine(
    private val workspaceId: String,
    private val backend: AiBackend,
    private val history: ChatHistoryStore,
    private val promptFor: suspend (userText: String, stage: WorkspaceStage) -> WorkspacePromptContext,
    private val scope: CoroutineScope,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    private val historyKey = "ws-$workspaceId"
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()
    private var job: Job? = null

    val stage: WorkspaceStage get() = WorkspacePlans.stageOf(_state.value.turns)

    suspend fun load() {
        val turns = history.load(historyKey).map { if (it.state == TurnState.STREAMING) it.copy(state = TurnState.CANCELLED) else it }
        _state.value = ChatState(turns = turns)
    }

    /** Returns false when nothing was started (empty text, or an answer is still coming). */
    fun send(text: String): Boolean {
        val clean = text.trim()
        if (clean.isEmpty() || clean == WorkspacePlans.APPROVAL_TEXT || _state.value.busy) return false
        val user = ChatTurn(newId(), AiRole.USER, clean, createdAtMillis = clockMillis())
        _state.update { it.copy(turns = it.turns + user, busy = true, notice = null) }
        start(user)
        return true
    }

    /** Answers the last question again (also the retry after an error). */
    fun retry(): Boolean {
        val s = _state.value
        if (s.busy) return false
        var turns = s.turns
        if (turns.lastOrNull()?.role == AiRole.MODEL) turns = turns.dropLast(1)
        val user = turns.lastOrNull()?.takeIf { it.role == AiRole.USER && it.text != WorkspacePlans.APPROVAL_TEXT } ?: return false
        _state.update { it.copy(turns = turns, busy = true, notice = null) }
        start(user)
        return true
    }

    /**
     * Records the user's approval of the plan they are looking at. Allowed only while a complete plan is the last thing in the
     * conversation. Calls no AI and changes no file. Returns false (and does nothing) in any other state.
     */
    fun approvePlan(): Boolean {
        val s = _state.value
        if (s.busy || WorkspacePlans.stageOf(s.turns) != WorkspaceStage.PLAN_READY) return false
        val turn = ChatTurn(newId(), AiRole.USER, WorkspacePlans.APPROVAL_TEXT, createdAtMillis = clockMillis())
        _state.update { it.copy(turns = it.turns + turn, notice = null) }
        scope.launch { saveHistory() }
        return true
    }

    /** The approved plan, or null unless the conversation is in the approved stage right now. */
    fun approvedPlan(): String? =
        if (stage == WorkspaceStage.APPROVED) WorkspacePlans.currentPlan(_state.value.turns) else null

    fun cancel() { job?.cancel() }

    suspend fun clear() {
        job?.cancel()
        job = null
        _state.value = ChatState()
        history.clear(historyKey)
    }

    fun dismissNotice() { _state.update { it.copy(notice = null) } }

    private fun start(user: ChatTurn) {
        val answerId = newId()
        _state.update { it.copy(turns = it.turns + ChatTurn(answerId, AiRole.MODEL, "", TurnState.STREAMING, createdAtMillis = clockMillis())) }

        fun patch(change: (ChatTurn) -> ChatTurn) {
            _state.update { s -> s.copy(turns = s.turns.map { if (it.id == answerId) change(it) else it }) }
        }

        job = scope.launch {
            try {
                val visible = _state.value.turns.filter { it.id != answerId }
                // The stage the AI is told about is the one BEFORE this question, so a plan under discussion stays "ready".
                // A new message after an approval re-opens the discussion: the approval no longer covers what was just asked.
                val before = WorkspacePlans.stageOf(visible.dropLast(1))
                val stageNow = if (before == WorkspaceStage.APPROVED) WorkspaceStage.DISCUSSING else before
                val prompt = promptFor(user.text, stageNow)
                val messages = ChatMessageBuilder.build(visible, prompt.projectContext, prompt.instructions)
                var model: String? = null
                var finishReason: String? = null
                backend.stream(AiRequest(AiTaskKind.CHAT, messages)).collect { ev ->
                    when (ev) {
                        is AiStreamEvent.Started -> model = ev.model
                        is AiStreamEvent.Delta -> patch { it.copy(text = it.text + ev.text) }
                        is AiStreamEvent.Finished -> finishReason = ev.finishReason
                    }
                }
                patch { it.copy(state = TurnState.DONE, model = model) }
                if (finishReason == "MAX_TOKENS") _state.update { it.copy(notice = "The answer was cut off because it reached the length limit.") }
            } catch (e: CancellationException) {
                patch { it.copy(state = TurnState.CANCELLED) }
                throw e
            } catch (e: AiException) {
                patch { it.copy(state = TurnState.ERROR, error = e.message, retryable = e.retryable) }
            } catch (e: Exception) {
                patch { it.copy(state = TurnState.ERROR, error = "Something went wrong while asking the AI.", retryable = true) }
            } finally {
                _state.update { it.copy(busy = false) }
                withContext(NonCancellable) { saveHistory() }
            }
        }
    }

    private suspend fun saveHistory() {
        try {
            history.save(historyKey, _state.value.turns)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The history is a convenience; a failed save must never break the chat.
        }
    }
}
