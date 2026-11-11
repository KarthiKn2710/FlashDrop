package com.flashdrop.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import com.flashdrop.core.model.SelectedItem
import com.flashdrop.protocol.SourceReader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Source side of the storage engine. Everything goes through the Storage Access Framework and
 * ContentResolver streams, so cloud-backed and virtual documents work without file paths.
 */
@Singleton
class DocumentScanner @Inject constructor(@ApplicationContext private val context: Context) {
    private val resolver get() = context.contentResolver

    /** Describes individually picked documents. Unreadable ones are skipped. */
    suspend fun describe(uris: List<String>): List<SelectedItem> = withContext(Dispatchers.IO) {
        uris.mapNotNull { text ->
            coroutineContext.ensureActive()
            val uri = Uri.parse(text)
            persist(uri)
            try {
                var name: String? = null
                var size: Long? = null
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0)
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
                val finalName = name ?: uri.lastPathSegment ?: return@mapNotNull null
                val finalSize = size ?: measure(uri) ?: return@mapNotNull null
                SelectedItem(text, finalName, finalName, finalSize, resolver.getType(uri) ?: OCTET_STREAM)
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Walks a picked folder, preserving its hierarchy. Empty folders become directory items. */
    suspend fun scanTree(treeUri: String): List<SelectedItem> = withContext(Dispatchers.IO) {
        val tree = Uri.parse(treeUri)
        persist(tree)
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val rootName = queryName(DocumentsContract.buildDocumentUriUsingTree(tree, rootId)) ?: "Folder"
        val result = ArrayList<SelectedItem>()
        val queue = ArrayDeque<Triple<String, String, Int>>() // document id, relative path, depth
        queue.add(Triple(rootId, rootName, 1))

        while (queue.isNotEmpty() && result.size < MAX_ITEMS) {
            coroutineContext.ensureActive()
            val (dirId, dirPath, depth) = queue.removeFirst()
            var children = 0
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId)
            try {
                resolver.query(
                    childrenUri,
                    arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE),
                    null, null, null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        children++
                        val id = c.getString(0)
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2) ?: OCTET_STREAM
                        val path = "$dirPath/$name"
                        if (mime == Document.MIME_TYPE_DIR) {
                            if (depth < MAX_DEPTH) queue.add(Triple(id, path, depth + 1))
                        } else {
                            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                            val size = if (c.isNull(3)) measure(uri) ?: continue else c.getLong(3)
                            result.add(SelectedItem(uri.toString(), name, path, size, mime))
                        }
                    }
                }
            } catch (e: Exception) {
                continue // unreadable sub-folder: skip it, keep the rest
            }
            if (children == 0) {
                result.add(SelectedItem("$treeUri#$dirId", dirPath.substringAfterLast('/'), dirPath, 0, Document.MIME_TYPE_DIR, isDirectory = true))
            }
        }
        result
    }

    fun openReader(uri: String): SourceReader {
        val stream: InputStream = try {
            resolver.openInputStream(Uri.parse(uri))
        } catch (e: SecurityException) {
            throw IOException("Access to the file was revoked", e)
        } ?: throw FileNotFoundException("Cannot open $uri")
        return object : SourceReader {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = stream.read(buffer, offset, length)
            override fun close() = stream.close()
        }
    }

    fun treeLabel(treeUri: String): String {
        val tree = Uri.parse(treeUri)
        val id = DocumentsContract.getTreeDocumentId(tree)
        return queryName(DocumentsContract.buildDocumentUriUsingTree(tree, id)) ?: id.substringAfterLast(':').ifEmpty { "Custom folder" }
    }

    /** Keeps access across process restarts where the provider allows it. */
    fun persist(uri: Uri, write: Boolean = false) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            resolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
        }
    }

    private fun queryName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    /** Some providers do not report a size; the manifest needs an exact one, so count the stream. */
    private fun measure(uri: Uri): Long? = try {
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(256 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
            }
            total
        }
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val OCTET_STREAM = "application/octet-stream"
        const val MAX_ITEMS = 100_000
        const val MAX_DEPTH = 32
    }
}
