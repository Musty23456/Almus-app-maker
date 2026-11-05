package com.almus.appcreatorpro.service.transfer

import com.almus.appcreatorpro.domain.transfer.TreeSource
import java.io.InputStream
import java.io.OutputStream

/** Name and size of a document the user picked (either may be unknown). */
data class DocumentInfo(val displayName: String?, val sizeBytes: Long?)

/**
 * Access to files the user chose with Android's Storage Access Framework. Uris travel as strings so the UI logic
 * can be tested without Android classes. SAF needs NO storage permission: the system file picker grants access to
 * exactly the file or folder the user selects.
 *
 * All methods block and must be called off the main thread. Failures are thrown as
 * [com.almus.appcreatorpro.domain.transfer.TransferException] (PERMISSION, NOT_FOUND, READ_FAILED).
 */
interface DocumentAccess {
    fun describe(uri: String): DocumentInfo

    fun openInput(uri: String): InputStream

    /** Opens for writing and truncates. */
    fun openOutput(uri: String): OutputStream

    /** Best effort removal of a partly written export; returns whether it was deleted. */
    fun delete(uri: String): Boolean

    fun treeSource(treeUri: String): TreeSource
}
