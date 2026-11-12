package com.flashdrop.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Destination side of the storage engine. Data is always written to a temporary document and
 * only becomes a visible, final file after its SHA-256 has been verified.
 */
interface DestinationStore {
    val label: String

    /** Document URI of the destination root for "open destination", or null for system Downloads. */
    val rootUri: String?

    fun createDirectory(relativePath: String)
    fun createTemp(relativePath: String): String
    fun open(temporaryUri: String): TempFile
    fun exists(temporaryUri: String): Boolean

    /** Publishes a verified file under a name that never overwrites an existing one. */
    fun finalize(temporaryUri: String, relativePath: String): String
    fun discard(temporaryUri: String)

    /** Free space in bytes, or null when the provider cannot tell. */
    fun freeBytes(): Long?
}

/** Random-access handle on a temporary document, backed by a file descriptor. */
class TempFile(private val descriptor: ParcelFileDescriptor) : Closeable {
    private val output = FileOutputStream(descriptor.fileDescriptor).channel
    private val input = FileInputStream(descriptor.fileDescriptor).channel

    fun size(): Long = output.size()

    fun truncate(size: Long) {
        output.truncate(size)
    }

    fun write(buffer: ByteArray, offset: Int, length: Int, position: Long) {
        val bytes = ByteBuffer.wrap(buffer, offset, length)
        var at = position
        while (bytes.hasRemaining()) at += output.write(bytes, at)
    }

    /** Feeds the first [length] bytes into [digest]; used to continue a hash after a resume. */
    fun digestPrefix(digest: MessageDigest, length: Long) {
        val bytes = ByteBuffer.allocate(1 shl 20)
        var at = 0L
        while (at < length) {
            bytes.clear()
            bytes.limit(minOf(bytes.capacity().toLong(), length - at).toInt())
            val n = input.read(bytes, at)
            if (n <= 0) throw IOException("Partial file is shorter than its checkpoint")
            digest.update(bytes.array(), 0, n)
            at += n
        }
    }

    /** Durably flushes written data; only then may it be acknowledged as committed. */
    fun force() = output.force(false)

    override fun close() {
        try {
            descriptor.close()
        } catch (_: IOException) {
        }
    }
}

@Singleton
class DestinationFactory @Inject constructor(@ApplicationContext private val context: Context) {
    fun create(treeUri: String?, label: String): DestinationStore =
        if (treeUri == null) MediaStoreDestination(context) else TreeDestination(context, Uri.parse(treeUri), label)
}

internal fun mimeFromName(name: String): String? {
    val extension = name.substringAfterLast('.', "").lowercase()
    return if (extension.isEmpty()) null else MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
}

/**
 * Default destination: Download/FlashDrop via MediaStore, which needs no storage permission.
 * A pending row is the temporary file; clearing IS_PENDING publishes it. MediaStore picks a
 * unique display name on insert, so an existing file is never overwritten.
 */
internal class MediaStoreDestination(private val context: Context) : DestinationStore {
    private val resolver get() = context.contentResolver
    private val collection: Uri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    override val label = "Download/$FOLDER"
    override val rootUri: String? = null

    override fun createDirectory(relativePath: String) {
        // MediaStore has no empty directories; create it directly where the platform allows.
        try {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "$FOLDER/$relativePath").mkdirs()
        } catch (_: SecurityException) {
        }
    }

    override fun createTemp(relativePath: String): String {
        val name = relativePath.substringAfterLast('/')
        val parent = relativePath.substringBeforeLast('/', "")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + if (parent.isEmpty()) "" else "/$parent",
            )
            // The sender's MIME type is advisory and ignored; derive it from the name only.
            mimeFromName(name)?.let { put(MediaStore.MediaColumns.MIME_TYPE, it) }
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(collection, values)
        } catch (e: Exception) {
            throw IOException("Cannot create $name: ${e.message}", e)
        }
        return uri?.toString() ?: throw IOException("Cannot create $name")
    }

    override fun open(temporaryUri: String): TempFile = openTemp(context, temporaryUri)

    override fun exists(temporaryUri: String): Boolean = documentExists(context, temporaryUri)

    override fun finalize(temporaryUri: String, relativePath: String): String {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        val updated = try {
            resolver.update(Uri.parse(temporaryUri), values, null, null)
        } catch (e: Exception) {
            throw IOException("Cannot publish ${relativePath.substringAfterLast('/')}: ${e.message}", e)
        }
        if (updated == 0) throw IOException("Temporary file disappeared")
        return temporaryUri
    }

    override fun discard(temporaryUri: String) {
        try {
            resolver.delete(Uri.parse(temporaryUri), null, null)
        } catch (_: Exception) {
        }
    }

    override fun freeBytes(): Long? = try {
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val FOLDER = "FlashDrop"
    }
}

/** User-chosen destination folder, accessed through a persisted SAF tree grant. */
internal class TreeDestination(private val context: Context, private val tree: Uri, override val label: String) : DestinationStore {
    private val resolver get() = context.contentResolver
    private val root: DocumentFile = DocumentFile.fromTreeUri(context, tree) ?: throw IOException("Destination folder is not available")
    private val directories = HashMap<String, DocumentFile>()

    /**
     * Names present in each destination folder, listed once per folder. Looking a name up through
     * the provider means listing the whole folder, which made every file cost O(folder size).
     */
    private val names = HashMap<String, HashSet<String>>()

    @Synchronized
    private fun namesIn(path: String, dir: DocumentFile): HashSet<String> = names.getOrPut(path) {
        dir.listFiles().mapNotNullTo(HashSet()) { it.name }
    }

    override val rootUri: String =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)).toString()

    @Synchronized
    private fun directory(path: String): DocumentFile {
        if (path.isEmpty()) return root
        directories[path]?.let { return it }
        val parent = directory(path.substringBeforeLast('/', ""))
        val name = path.substringAfterLast('/')
        val parentPath = path.substringBeforeLast('/', "")
        val siblings = namesIn(parentPath, parent)
        val existing = if (name in siblings) parent.findFile(name)?.takeIf { it.isDirectory } else null
        val dir = existing ?: parent.createDirectory(name) ?: throw IOException("Cannot create folder $name")
        dir.name?.let { siblings.add(it) }
        if (existing == null) names[path] = HashSet() // just created, so known to be empty
        directories[path] = dir
        return dir
    }

    override fun createDirectory(relativePath: String) {
        directory(relativePath)
    }

    override fun createTemp(relativePath: String): String {
        val dirPath = relativePath.substringBeforeLast('/', "")
        val dir = directory(dirPath)
        val siblings = namesIn(dirPath, dir)
        val tempName = relativePath.substringAfterLast('/') + TEMP_SUFFIX
        if (tempName in siblings) dir.findFile(tempName)?.delete() // stale partial from an abandoned session
        val created = dir.createFile("application/octet-stream", tempName)
            ?: throw IOException("Cannot create ${relativePath.substringAfterLast('/')}")
        synchronized(this) { siblings.add(created.name ?: tempName) }
        return created.uri.toString()
    }

    override fun open(temporaryUri: String): TempFile = openTemp(context, temporaryUri)

    override fun exists(temporaryUri: String): Boolean = documentExists(context, temporaryUri)

    override fun finalize(temporaryUri: String, relativePath: String): String {
        val dirPath = relativePath.substringBeforeLast('/', "")
        val dir = directory(dirPath)
        val finalName = uniqueName(namesIn(dirPath, dir), relativePath.substringAfterLast('/'))
        val source = Uri.parse(temporaryUri)

        // Preferred: atomic rename inside the provider.
        val renamed = try {
            DocumentsContract.renameDocument(resolver, source, finalName)
        } catch (e: Exception) {
            null
        }
        if (renamed != null) return renamed.toString()

        // Provider cannot rename: copy to the final name, then remove the temporary document.
        val target = dir.createFile(mimeFromName(finalName) ?: "application/octet-stream", finalName)?.uri
            ?: throw IOException("Cannot create $finalName")
        try {
            resolver.openInputStream(source).use { input ->
                resolver.openOutputStream(target).use { output ->
                    if (input == null || output == null) throw IOException("Cannot copy $finalName")
                    input.copyTo(output, 1 shl 20)
                }
            }
        } catch (e: Exception) {
            try {
                DocumentsContract.deleteDocument(resolver, target)
            } catch (_: Exception) {
            }
            throw if (e is IOException) e else IOException(e.message, e)
        }
        discard(temporaryUri)
        return target.toString()
    }

    /** "name.ext" -> "name (1).ext" ... so an existing file is never silently overwritten. */
    @Synchronized
    private fun uniqueName(taken: HashSet<String>, name: String): String {
        var candidate = name
        if (candidate in taken) {
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0) name.substring(0, dot) else name
            val extension = if (dot > 0) name.substring(dot) else ""
            var n = 1
            do candidate = "$base (${n++})$extension" while (candidate in taken)
        }
        taken.add(candidate) // reserve it for this session
        return candidate
    }

    override fun discard(temporaryUri: String) {
        try {
            DocumentsContract.deleteDocument(resolver, Uri.parse(temporaryUri))
        } catch (_: Exception) {
        }
    }

    override fun freeBytes(): Long? = null

    private companion object {
        const val TEMP_SUFFIX = ".fdpart"
    }
}

private fun openTemp(context: Context, uri: String): TempFile {
    val descriptor = try {
        context.contentResolver.openFileDescriptor(Uri.parse(uri), "rw")
    } catch (e: FileNotFoundException) {
        throw e
    } catch (e: Exception) {
        throw IOException("Cannot open destination file: ${e.message}", e)
    }
    return TempFile(descriptor ?: throw FileNotFoundException("Cannot open destination file"))
}

private fun documentExists(context: Context, uri: String): Boolean = try {
    context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
} catch (e: Exception) {
    false
}
