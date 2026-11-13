package com.almus.appcreatorpro.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.almus.appcreatorpro.agent.AgentRunner
import com.almus.appcreatorpro.ai.ChatTurn
import com.almus.appcreatorpro.domain.error.toUserMessage
import com.almus.appcreatorpro.domain.github.CommitSummary
import com.almus.appcreatorpro.domain.repository.ProjectRepository
import com.almus.appcreatorpro.domain.transfer.TransferProgress
import com.almus.appcreatorpro.service.ai.AiHub
import com.almus.appcreatorpro.workspace.RepoWorkspace
import com.almus.appcreatorpro.workspace.RepoWorkspaceManager
import com.almus.appcreatorpro.workspace.SyncResult
import com.almus.appcreatorpro.workspace.WorkspaceChatEngine
import com.almus.appcreatorpro.workspace.WorkspaceHub
import com.almus.appcreatorpro.workspace.WorkspacePlans
import com.almus.appcreatorpro.workspace.WorkspaceStage
import com.almus.appcreatorpro.workspace.WorkspaceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SyncUi(
    val checking: Boolean = false,
    val syncing: Boolean = false,
    val progress: TransferProgress? = null,
    /** Set when GitHub has a commit that the local copy does not have yet. */
    val newerCommit: CommitSummary? = null,
)

data class WorkspaceUi(
    val loading: Boolean = true,
    val workspace: RepoWorkspace? = null,
    /** Why the workspace cannot be opened (unknown, or its local project was deleted). */
    val problem: String? = null,
    val turns: List<ChatTurn> = emptyList(),
    val busy: Boolean = false,
    val notice: String? = null,
    val stage: WorkspaceStage = WorkspaceStage.DISCUSSING,
    /** The other workspaces, most recent first: the project switcher. */
    val others: List<RepoWorkspace> = emptyList(),
    val sync: SyncUi = SyncUi(),
    val startingAgent: Boolean = false,
    val message: String? = null,
)

/**
 * One repository workspace: its AI conversation, the approval step, and keeping the local copy current.
 *
 * This class never writes project files itself. The conversation cannot (see [WorkspaceChatEngine]); the only things here that
 * touch files are [syncNow] (user-confirmed in the screen, safety backup first) and the hand-off to the Agent after approval.
 */
class RepoWorkspaceViewModel(
    private val workspaceId: String,
    private val store: WorkspaceStore,
    private val hub: WorkspaceHub,
    private val manager: RepoWorkspaceManager,
    private val aiHub: AiHub,
    private val projects: ProjectRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(WorkspaceUi())
    val state: StateFlow<WorkspaceUi> = _state.asStateFlow()

    private var engine: WorkspaceChatEngine? = null

    init {
        viewModelScope.launch {
            store.workspaces.collect { list ->
                _state.update { s ->
                    s.copy(
                        others = list.filter { w -> w.id != workspaceId }.take(8),
                        workspace = list.firstOrNull { w -> w.id == workspaceId } ?: s.workspace,
                    )
                }
            }
        }
        viewModelScope.launch {
            try {
                val workspace = store.get(workspaceId)
                if (workspace == null) {
                    _state.update { it.copy(loading = false, problem = "This workspace is no longer on this phone.") }
                    return@launch
                }
                if (!manager.projectExists(workspace)) {
                    _state.update { it.copy(loading = false, workspace = workspace, problem = "The local copy of ${workspace.fullName} was deleted. Import it again from My GitHub Projects.") }
                    return@launch
                }
                store.markOpened(workspaceId)
                val chat = hub.engine(workspaceId)
                if (chat == null) {
                    _state.update { it.copy(loading = false, problem = "This workspace is no longer on this phone.") }
                    return@launch
                }
                engine = chat
                _state.update { it.copy(loading = false, workspace = store.get(workspaceId) ?: workspace) }
                chat.state.collect { c ->
                    _state.update { s -> s.copy(turns = c.turns, busy = c.busy, notice = c.notice, stage = WorkspacePlans.stageOf(c.turns)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, problem = e.toUserMessage()) }
            }
        }
    }

    // ------------------------------------------------------------------------------------------ conversation

    fun send(text: String) {
        engine?.send(text)
    }

    fun retry() {
        engine?.retry()
    }

    fun cancel() {
        engine?.cancel()
    }

    fun dismissNotice() {
        engine?.dismissNotice()
    }

    fun clearChat() {
        val chat = engine ?: return
        viewModelScope.launch { chat.clear() }
    }

    /** The user pressed "Approve plan". Records the approval in the conversation; nothing is changed. */
    fun approvePlan() {
        if (engine?.approvePlan() != true) _state.update { it.copy(message = "There is no plan waiting for approval.") }
    }

    /**
     * After approval only: hands the approved plan to the Agent, which plans in detail and asks for approval AGAIN before it
     * stages anything, and shows every change for review before any file is written. [onStarted] opens the Agent screen.
     */
    fun startAgent(onStarted: (projectId: String) -> Unit) {
        val workspace = _state.value.workspace ?: return
        val plan = engine?.approvedPlan()
        if (plan == null) {
            _state.update { it.copy(message = "Approve the plan first.") }
            return
        }
        if (_state.value.startingAgent) return
        _state.update { it.copy(startingAgent = true) }
        viewModelScope.launch {
            try {
                val runner = aiHub.agent(workspace.projectId)
                val goal = WorkspacePlans.agentGoal(workspace, plan, AgentRunner.MAX_GOAL_CHARS)
                if (runner.start(goal)) {
                    onStarted(workspace.projectId)
                } else {
                    _state.update { it.copy(message = "The Agent is busy, or it has an unfinished run for this project. Open the Agent to finish or discard that run first.") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = e.toUserMessage()) }
            } finally {
                _state.update { it.copy(startingAgent = false) }
            }
        }
    }

    // ------------------------------------------------------------------------------------------ keeping the copy current

    /** Asks GitHub for the branch's newest commit. Nothing is downloaded. */
    fun checkForUpdates() {
        val workspace = _state.value.workspace ?: return
        if (_state.value.sync.checking || _state.value.sync.syncing) return
        _state.update { it.copy(sync = it.sync.copy(checking = true)) }
        viewModelScope.launch {
            try {
                val latest = manager.latestCommit(workspace)
                if (latest.sha == workspace.commitSha) {
                    _state.update { it.copy(sync = SyncUi(), message = "This copy is up to date with ${workspace.branch}.") }
                } else {
                    _state.update { it.copy(sync = SyncUi(newerCommit = latest)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(sync = it.sync.copy(checking = false), message = e.toUserMessage()) }
            }
        }
    }

    /** Call only after the user confirmed in the screen: this REPLACES the local files (a safety backup is made first). */
    fun syncNow() {
        val workspace = _state.value.workspace ?: return
        if (_state.value.sync.syncing) return
        _state.update { it.copy(sync = SyncUi(syncing = true)) }
        viewModelScope.launch {
            try {
                val result = manager.sync(workspace) { progress ->
                    _state.update { s -> s.copy(sync = s.sync.copy(progress = progress)) }
                }
                when (result) {
                    is SyncResult.UpToDate -> _state.update { it.copy(sync = SyncUi(), message = "Already up to date.") }
                    is SyncResult.Updated -> {
                        hub.invalidate(workspaceId)
                        _state.update {
                            it.copy(
                                sync = SyncUi(),
                                message = "Updated to commit ${result.workspace.shortSha}. A safety backup (\"${result.backupLabel}\") was saved in Backups.",
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(sync = SyncUi()) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(sync = SyncUi(), message = e.toUserMessage()) }
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}
