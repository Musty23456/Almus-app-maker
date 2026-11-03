package com.almus.appcreatorpro.ui.artifacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.almus.appcreatorpro.domain.repository.BuildStore
import com.almus.appcreatorpro.service.artifacts.ApkStore
import com.almus.appcreatorpro.service.artifacts.ArtifactDetails
import com.almus.appcreatorpro.service.artifacts.ArtifactInspector
import com.almus.appcreatorpro.service.artifacts.StoredApk
import com.almus.appcreatorpro.ui.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.OutputStream

/** One saved APK/AAB with everything the artifact manager shows. */
data class ManagedArtifact(
    val file: StoredApk,
    val buildId: String,
    val projectName: String,
    val buildLabel: String,
    val details: ArtifactDetails,
    val savedAtMillis: Long,
)

/** The artifact manager: every APK/AAB saved on the phone, with size, package and version, share, save and delete. */
class ArtifactsViewModel(
    private val builds: BuildStore,
    private val store: ApkStore,
    private val inspector: ArtifactInspector,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<ManagedArtifact>>>(UiState.Loading)
    val state: StateFlow<UiState<List<ManagedArtifact>>> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = try {
                val items = ArrayList<ManagedArtifact>()
                for (id in store.buildIdsWithFiles()) {
                    val record = builds.getBuild(id)
                    for (file in store.list(id)) {
                        items += ManagedArtifact(
                            file = file,
                            buildId = id,
                            projectName = record?.projectName ?: "Unknown project",
                            buildLabel = (record?.configuration)?.shortLabel() ?: "Debug APK",
                            details = inspector.inspect(file.file, file.isBundle, record?.configuration),
                            savedAtMillis = file.lastModifiedMillis,
                        )
                    }
                }
                UiState.Success(items.sortedByDescending { it.savedAtMillis })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UiState.Error("The saved files could not be listed.")
            }
        }
    }

    fun delete(item: ManagedArtifact) {
        store.delete(item.buildId, item.file.fileName)
        load()
    }

    /** Copies the file into the stream the user chose (Storage Access Framework). */
    fun export(item: ManagedArtifact, open: () -> OutputStream?) {
        viewModelScope.launch {
            _message.value = try {
                val out = open() ?: throw IOException("No destination was chosen.")
                out.use { store.copyTo(item.file, it) }
                "Saved ${item.file.fileName}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                "Could not save the file there: ${e.message ?: "unknown error"}"
            } catch (e: Exception) {
                "Could not save the file there."
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun showMessage(text: String) {
        _message.value = text
    }
}
