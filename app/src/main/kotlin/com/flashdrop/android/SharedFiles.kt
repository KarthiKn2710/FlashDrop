package com.flashdrop.android

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/**
 * Reads the files another app hands FlashDrop through its Share menu. Apps put them in
 * EXTRA_STREAM, in the intent's ClipData, or both, so all are collected. Only content:// links
 * are taken: file:// links cannot be opened without storage permission, and text-only shares
 * (a web link, a note) have nothing to send.
 */
internal object SharedFiles {
    fun isShare(intent: Intent?): Boolean =
        intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE

    fun uris(intent: Intent): List<Uri> {
        val found = LinkedHashSet<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(found::add)
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(found::addAll)
        }
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(found::add)
        }
        return found.filter { it.scheme == ContentResolver.SCHEME_CONTENT }
    }
}
