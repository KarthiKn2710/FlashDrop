package com.flashdrop.core.designsystem

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * A small preview for images and videos, falling back to the file-type icon. Thumbnails are
 * decoded at display size off the main thread, a few at a time, and cached for the session.
 */
@Composable
fun FileThumbnail(
    uri: String?,
    name: String,
    mimeType: String,
    isDirectory: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val previewable = !isDirectory && uri != null && (mimeType.startsWith("image/") || mimeType.startsWith("video/"))
    val bitmap by produceState<ImageBitmap?>(ThumbnailCache.get(uri, px), uri, px) {
        if (value == null && previewable) {
            value = ThumbnailCache.load(context.contentResolver, uri!!, mimeType, px)
        }
    }
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.size(size).clip(shape).background(FdColors.Elevated).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(bitmap, animationSpec = tween(FdMotion.MEDIUM), label = "thumbnail") { image ->
            if (image != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    if (mimeType.startsWith("video/")) {
                        Box(
                            Modifier.size(size * 0.45f).clip(RoundedCornerShape(50)).background(Color(0x99000000)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.32f))
                        }
                    }
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(fileIcon(name, isDirectory), contentDescription = null, tint = FdColors.TextSecondary, modifier = Modifier.size(size * 0.5f))
                }
            }
        }
    }
}

private object ThumbnailCache {
    /** Roughly 12 MB of decoded thumbnails. */
    private val cache = object : LruCache<String, ImageBitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    /** Decoding is heavy: never more than three at once, however fast the list scrolls. */
    private val decoding = Semaphore(3)

    /** Uris that could not be previewed, so they are not retried while scrolling. */
    private val failed = HashSet<String>()

    private fun key(uri: String, px: Int) = "$px|$uri"

    fun get(uri: String?, px: Int): ImageBitmap? = uri?.let { cache.get(key(it, px)) }

    suspend fun load(resolver: ContentResolver, uri: String, mimeType: String, px: Int): ImageBitmap? {
        val key = key(uri, px)
        cache.get(key)?.let { return it }
        if (synchronized(failed) { key in failed }) return null
        val bitmap = decoding.withPermit {
            withContext(Dispatchers.IO) { decode(resolver, Uri.parse(uri), mimeType, px) }
        }
        if (bitmap == null) {
            synchronized(failed) { failed.add(key) }
            return null
        }
        return bitmap.asImageBitmap().also { cache.put(key, it) }
    }

    private fun decode(resolver: ContentResolver, uri: Uri, mimeType: String, px: Int): Bitmap? {
        // 1. The provider's own thumbnail: cheapest, and works for videos too.
        try {
            return resolver.loadThumbnail(uri, Size(px, px), null)
        } catch (_: Exception) {
        }
        // 2. Providers without thumbnails: decode images ourselves, downsampled while decoding.
        if (mimeType.startsWith("image/")) {
            return try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                    val scale = px.toFloat() / minOf(info.size.width, info.size.height).coerceAtLeast(1)
                    if (scale < 1f) {
                        decoder.setTargetSize(
                            (info.size.width * scale).toInt().coerceAtLeast(1),
                            (info.size.height * scale).toInt().coerceAtLeast(1),
                        )
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } catch (_: Exception) {
                null
            }
        }
        // 3. Videos: a frame from the start of the clip.
        if (mimeType.startsWith("video/")) {
            val retriever = MediaMetadataRetriever()
            return try {
                resolver.openFileDescriptor(uri, "r")?.use { fd ->
                    retriever.setDataSource(fd.fileDescriptor)
                    retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, px, px)
                }
            } catch (_: Exception) {
                null
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {
                }
            }
        }
        return null
    }
}
