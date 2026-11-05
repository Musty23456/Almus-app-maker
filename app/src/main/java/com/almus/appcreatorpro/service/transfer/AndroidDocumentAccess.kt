package com.almus.appcreatorpro.service.transfer

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.almus.appcreatorpro.domain.transfer.TransferException
import com.almus.appcreatorpro.domain.transfer.TransferException.Kind
import com.almus.appcreatorpro.domain.transfer.TreeSource
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** [DocumentAccess] backed by Android's ContentResolver and DocumentsContract (no extra dependency needed). */
class AndroidDocumentAccess(private val resolver: ContentResolver) : DocumentAccess {

    override fun describe(uri: String): DocumentInfo {
        val parsed = Uri.parse(uri)
        var name: String? = null
        var size: Long? = null
        try {
            resolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        } catch (_: SecurityException) {
            // the later open call reports the permission problem
        } catch (_: RuntimeException) {
            // some providers do not support queries; name and size stay unknown
        }
        return DocumentInfo(name, size?.takeIf { it > 0L })
    }

    override fun openInput(uri: String): InputStream = try {
        resolver.openInputStream(Uri.parse(uri)) ?: throw TransferException(Kind.NOT_FOUND, "The selected file could not be opened.")
    } catch (e: SecurityException) {
        throw TransferException(Kind.PERMISSION, "Android did not allow access to the selected file. Please choose it again.", e)
    } catch (e: FileNotFoundException) {
        throw TransferException(Kind.NOT_FOUND, "The selected file no longer exists.", e)
    }

    override fun openOutput(uri: String): OutputStream = try {
        resolver.openOutputStream(Uri.parse(uri), "wt") ?: throw TransferException(Kind.NOT_FOUND, "The destination could not be opened.")
    } catch (e: SecurityException) {
        throw TransferException(Kind.PERMISSION, "Android did not allow writing to the chosen location. Please choose another one.", e)
    } catch (e: FileNotFoundException) {
        throw TransferException(Kind.NOT_FOUND, "The destination is no longer available.", e)
    }

    override fun delete(uri: String): Boolean = try {
        DocumentsContract.deleteDocument(resolver, Uri.parse(uri))
    } catch (_: Exception) {
        false
    }

    override fun treeSource(treeUri: String): TreeSource = SafTreeSource(resolver, Uri.parse(treeUri))
}

/** Walks a folder chosen with ACTION_OPEN_DOCUMENT_TREE. Virtual documents (e.g. Google Docs) are skipped. */
class SafTreeSource(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
) : TreeSource {

    override val rootName: String
        get() = try {
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
            var name: String? = null
            resolver.query(docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) name = c.getString(0)
            }
            name ?: rootId.substringAfterLast(':').substringAfterLast('/').ifEmpty { "Imported folder" }
        } catch (_: Exception) {
            "Imported folder"
        }

    override fun forEachFile(action: (path: String, size: Long?, open: () -> InputStream) -> Unit) {
        val rootId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: IllegalArgumentException) {
            throw TransferException(Kind.NOT_FOUND, "The selected folder is not valid.", e)
        }
        walk(rootId, "", 0, action)
    }

    private fun walk(parentId: String, prefix: String, depth: Int, action: (String, Long?, () -> InputStream) -> Unit) {
        if (depth > MAX_DEPTH) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val children = ArrayList<Child>()
        val cursor: Cursor? = try {
            resolver.query(childrenUri, PROJECTION, null, null, null)
        } catch (e: SecurityException) {
            throw TransferException(Kind.PERMISSION, "Android did not allow access to the selected folder. Please choose it again.", e)
        } catch (e: IllegalArgumentException) {
            throw TransferException(Kind.NOT_FOUND, "The selected folder could not be read.", e)
        }
        cursor?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2).orEmpty()
                val size = if (c.isNull(3)) null else c.getLong(3)
                val flags = if (c.isNull(4)) 0 else c.getInt(4)
                children += Child(id, name, mime, size, flags)
            }
        }
        for (child in children.sortedBy { it.name }) {
            val path = if (prefix.isEmpty()) child.name else prefix + "/" + child.name
            if (child.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                walk(child.id, path, depth + 1, action)
            } else if (child.flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT == 0) {
                val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.id)
                action(path, child.size) { open(fileUri) }
            }
        }
    }

    private fun open(uri: Uri): InputStream = try {
        resolver.openInputStream(uri) ?: throw IOException("Could not open the file.")
    } catch (e: SecurityException) {
        throw IOException("Android did not allow access to a file in the folder.", e)
    }

    private class Child(val id: String, val name: String, val mime: String, val size: Long?, val flags: Int)

    private companion object {
        const val MAX_DEPTH = 40
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
    }
}
