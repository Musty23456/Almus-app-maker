package com.almus.appcreatorpro.ai

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

/**
 * The AI coding chat of one project: history, streaming answers, cancel, regenerate, clear. No Android classes.
 * [contextFor] turns the files the user attached into a [ContextBundle] (secrets already removed).
 */
class ChatEngine(
    private val projectId: String,
    private val backend: AiBackend,
    private val history: ChatHistoryStore,
    private val contextFor: suspend (List<String>) -> ContextBundle?,
    private val scope: CoroutineScope,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
    /** Phase 24: what Smart mode needs to know about the project. Null = Smart mode still works, without a project profile. */
    private val assistant: ChatAssistant? = null,
) {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()
    private var job: Job? = null

    suspend fun load() {
        // An answer that was still streaming when the app was closed will never finish.
        _state.value = ChatState(turns = history.load(projectId).map { if (it.state == TurnState.STREAMING) it.copy(state = TurnState.CANCELLED) else it })
    }

    /** Returns false when nothing was started (empty text, or an answer is still coming). */
    fun send(text: String, mode: ChatMode, paths: List<String> = emptyList()): Boolean {
        val clean = text.trim()
        if (clean.isEmpty() || _state.value.busy) return false
        val user = ChatTurn(newId(), AiRole.USER, clean, mode = mode, contextPaths = paths, createdAtMillis = clockMillis())
        _state.update { it.copy(turns = it.turns + user, busy = true, notice = null) }
        start(user)
        return true
    }

    /** Answers the last question again (also used to retry after an error). */
    fun regenerate(): Boolean {
        val s = _state.value
        if (s.busy) return false
        var turns = s.turns
        if (turns.lastOrNull()?.role == AiRole.MODEL) turns = turns.dropLast(1)
        val user = turns.lastOrNull()?.takeIf { it.role == AiRole.USER } ?: return false
        _state.update { it.copy(turns = turns, busy = true, notice = null) }
        start(user)
        return true
    }

    fun cancel() { job?.cancel() }

    suspend fun clear() {
        job?.cancel()
        job = null
        _state.value = ChatState()
        history.clear(projectId)
    }

    fun dismissNotice() { _state.update { it.copy(notice = null) } }

    /** What Smart mode would understand [text] to be, right now (pure and cheap: the UI shows it while the user types). */
    fun previewIntent(text: String, attachedCount: Int = 0): IntentGuess =
        IntentDetector.detect(text, attachedCount > 0, FollowUpResolver.lastIntent(_state.value.turns))

    private suspend fun knownPathsOrEmpty(): Set<String> =
        try { assistant?.knownPaths?.invoke() ?: emptySet() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptySet() }

    private fun start(user: ChatTurn) {
        val answerId = newId()
        val mode = user.mode ?: ChatMode.ASK
        _state.update { it.copy(turns = it.turns + ChatTurn(answerId, AiRole.MODEL, "", TurnState.STREAMING, createdAtMillis = clockMillis())) }

        fun patch(change: (ChatTurn) -> ChatTurn) {
            _state.update { s -> s.copy(turns = s.turns.map { if (it.id == answerId) change(it) else it }) }
        }

        job = scope.launch {
            try {
                var paths = user.contextPaths
                var guess: IntentGuess? = null
                var carry: FollowUpResolver.CarryOver? = null
                var task = mode.task
                if (mode == ChatMode.AUTO) {
                    // Phase 24 (Smart mode): understand the request, carry the files of a follow-up over, pick the backend task.
                    val before = _state.value.turns.takeWhile { it.id != user.id }
                    val known = knownPathsOrEmpty()
                    val g = IntentDetector.detect(user.text, paths.isNotEmpty(), FollowUpResolver.lastIntent(before))
                    val c = FollowUpResolver.carryOver(user.text, before, paths, known, g.intent)
                    guess = g; carry = c; paths = c.paths
                    task = IntentRouting.taskFor(g.intent, paths.size)
                    if (c.inherited.isNotEmpty()) {
                        _state.update { s -> s.copy(turns = s.turns.map { if (it.id == user.id) it.copy(contextPaths = c.paths, inheritedContext = true) else it }) }
                    }
                    patch { it.copy(intent = g.intent, language = g.language) }
                }
                val bundle = if (paths.isNotEmpty()) contextFor(paths) else null
                var prefix: String? = null
                if (guess != null) {
                    val profile = try { assistant?.profile?.invoke() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                    prefix = CodingPrompts.build(
                        CodingRequest(
                            intent = guess.intent, language = guess.language, profile = profile,
                            attachedPaths = bundle?.files?.map { it.path } ?: emptyList(), skipped = bundle?.skipped.orEmpty(),
                            followUp = carry?.isFollowUp == true || FollowUpResolver.isFollowUp(user.text, _state.value.turns.takeWhile { it.id != user.id }, guess.intent),
                            carriedOver = carry?.inherited.orEmpty(),
                        ),
                    )
                }
                val visible = _state.value.turns.filter { it.id != answerId }
                val messages = ChatMessageBuilder.build(visible, bundle?.render(), prefix)
                var model: String? = null
                var finishReason: String? = null
                backend.stream(AiRequest(task, messages)).collect { ev ->
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
                withContext(NonCancellable) {
                    try { history.save(projectId, _state.value.turns) } catch (e: Exception) { /* history is a convenience */ }
                }
            }
        }
    }
}
