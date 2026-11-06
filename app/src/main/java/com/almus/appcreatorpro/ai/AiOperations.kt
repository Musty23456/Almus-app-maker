package com.almus.appcreatorpro.ai

/** Outcome of one AI operation: a value, or a message for the user. Never throws for expected failures. */
sealed interface AiStep<out T> {
    data class Ok<out T>(val value: T) : AiStep<T>
    data class Failed(val message: String, val retryable: Boolean = false) : AiStep<Nothing>
}

data class GenerationOutcome(val project: GeneratedProject, val report: ValidationReport, val finishedCleanly: Boolean)

/**
 * The individual AI jobs (plan, generate, choose files, edit, review). Each builds the request, runs it (streaming, so
 * long answers keep the connection alive), parses the structured answer and checks it. Nothing is written anywhere.
 */
class AiOperations(private val backend: AiBackend, private val clockMillis: () -> Long = { System.currentTimeMillis() }) {

    private suspend fun <T> guarded(block: suspend () -> AiStep<T>): AiStep<T> = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: AiException) {
        AiStep.Failed(e.message.orEmpty(), e.retryable)
    } catch (e: Exception) {
        // Any other problem (an unexpected answer shape, a bug) must become a message, never a crash of the whole app.
        AiStep.Failed("The AI answer could not be processed (${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}). Try again.", retryable = true)
    } catch (e: Throwable) {
        // Errors (a failed class initialiser, a stack overflow, out of memory) are not Exceptions; without this they close the app.
        AiStep.Failed("The AI answer could not be processed (${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}). Try again.", retryable = true)
    }

    private suspend fun ask(task: AiTaskKind, text: String, onProgress: (Int) -> Unit): AiCompletion =
        backend.completeStreaming(AiRequest(task, listOf(AiMessage(AiRole.USER, text))), onProgress)

    private fun checkDescription(d: String): String? = when {
        d.trim().length < MIN_DESCRIPTION -> "Describe the app in a little more detail (at least $MIN_DESCRIPTION characters)."
        d.length > MAX_DESCRIPTION -> "The description is too long (limit $MAX_DESCRIPTION characters)."
        else -> null
    }

    suspend fun plan(description: String, onProgress: (Int) -> Unit = {}): AiStep<ProjectPlan> = guarded {
        checkDescription(description)?.let { return@guarded AiStep.Failed(it) }
        val c = ask(AiTaskKind.PLAN_PROJECT, "Create a development plan for this app:\n\n${description.trim()}", onProgress)
        when (val p = ProjectPlanParser.parse(c.text, c.truncated)) {
            is Parsed.Ok -> AiStep.Ok(p.value)
            is Parsed.Bad -> AiStep.Failed(p.reason, retryable = true)
        }
    }

    suspend fun generateProject(description: String, plan: ProjectPlan?, onProgress: (Int) -> Unit = {}): AiStep<GenerationOutcome> = guarded {
        checkDescription(description)?.let { return@guarded AiStep.Failed(it) }
        val text = buildString {
            append("Generate this app as a complete project.\n\nDESCRIPTION:\n").append(description.trim()).append('\n')
            if (plan != null) append("\nAPPROVED PLAN (follow it):\n").append(plan.toPromptText())
        }
        val c = ask(AiTaskKind.GENERATE_PROJECT, text, onProgress)
        when (val p = GeneratedProjectParser.parse(c.text, c.truncated)) {
            is Parsed.Bad -> AiStep.Failed(p.reason, retryable = true)
            is Parsed.Ok -> AiStep.Ok(GenerationOutcome(p.value, GeneratedFileValidator.validate(p.value.files), finishedCleanly = !c.truncated))
        }
    }

    /** Step 1 of editing: the model sees only the file LIST and says which files it needs to read. */
    suspend fun selectFiles(instruction: String, structure: ContextBundle, known: Set<String>, onProgress: (Int) -> Unit = {}): AiStep<EditSelection> = guarded {
        val c = ask(AiTaskKind.EDIT_PROJECT_SELECT, "REQUEST:\n${instruction.trim()}\n\n${structure.render(includeStructure = true)}", onProgress)
        when (val p = EditSelectionParser.parse(c.text, known, c.truncated)) {
            is Parsed.Ok -> AiStep.Ok(p.value)
            is Parsed.Bad -> AiStep.Failed(p.reason, retryable = true)
        }
    }

    /** Step 2: the model sees the chosen files and proposes changes, which are checked against the real files. */
    suspend fun proposeEdit(instruction: String, context: ContextBundle, files: AiProjectFiles, onProgress: (Int) -> Unit = {}): AiStep<EditResult> = guarded {
        if (context.files.isEmpty()) return@guarded AiStep.Failed("No files are selected to send.")
        val c = ask(AiTaskKind.EDIT_PROJECT, "REQUEST:\n${instruction.trim()}\n\n${context.render()}", onProgress)
        val proposal = when (val p = EditProposalParser.parse(c.text, c.truncated)) {
            is Parsed.Ok -> p.value
            is Parsed.Bad -> return@guarded AiStep.Failed(p.reason, retryable = true)
        }
        val resolved = ChangeResolver.fromProposal(proposal, files, instruction.trim().take(60).ifBlank { "AI change" }, clockMillis())
        AiStep.Ok(EditResult(proposal.summary, resolved.changeSet, resolved.rejected))
    }

    suspend fun review(context: ContextBundle, focus: ReviewCategory?, onProgress: (Int) -> Unit = {}): AiStep<ReviewResult> = guarded {
        if (context.files.isEmpty()) return@guarded AiStep.Failed("No files are selected to review.")
        val focusLine = if (focus != null) "Focus mainly on: ${focus.label}.\n\n" else ""
        val c = ask(AiTaskKind.REVIEW, "Review these files.\n\n$focusLine${context.render()}", onProgress)
        when (val p = ReviewParser.parse(c.text, c.truncated)) {
            is Parsed.Ok -> AiStep.Ok(p.value)
            is Parsed.Bad -> AiStep.Failed(p.reason, retryable = true)
        }
    }

    companion object {
        const val MIN_DESCRIPTION = 15
        const val MAX_DESCRIPTION = 4000
    }
}

data class EditResult(val summary: String, val changeSet: ChangeSet?, val rejected: List<RejectedChange>)
