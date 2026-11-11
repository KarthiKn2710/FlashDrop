package com.flashdrop.domain.usecase

import com.flashdrop.core.model.RemoteDevice
import com.flashdrop.core.model.SelectedItem
import com.flashdrop.core.model.TransferRecord
import com.flashdrop.domain.repository.HistoryRepository
import com.flashdrop.domain.repository.SelectionRepository
import com.flashdrop.domain.repository.TransferController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Starts sending the current selection to a device, if there is anything to send. */
class SendSelectionUseCase @Inject constructor(
    private val selection: SelectionRepository,
    private val controller: TransferController,
) {
    operator fun invoke(device: RemoteDevice): Boolean {
        val items = selection.items.value
        if (items.isEmpty()) return false
        if (controller.session.value?.status?.isTerminal == false) return false
        controller.send(device, items)
        return true
    }
}

class ObserveRecentTransfersUseCase @Inject constructor(private val history: HistoryRepository) {
    operator fun invoke(limit: Int = 5): Flow<List<TransferRecord>> = history.records().map { it.take(limit) }
}

enum class FileCategory { ALL, IMAGES, VIDEO, AUDIO, DOCUMENTS, OTHER }

enum class SortOrder { NAME, SIZE_DESC, SIZE_ASC }

/** Pure filtering/sorting of the selection for the file picker. */
class FilterSelectionUseCase @Inject constructor() {
    operator fun invoke(items: List<SelectedItem>, query: String, category: FileCategory, sort: SortOrder): List<SelectedItem> {
        val q = query.trim()
        val filtered = items.filter { item ->
            (category == FileCategory.ALL || categoryOf(item) == category) &&
                (q.isEmpty() || item.relativePath.contains(q, ignoreCase = true))
        }
        return when (sort) {
            SortOrder.NAME -> filtered.sortedBy { it.relativePath.lowercase() }
            SortOrder.SIZE_DESC -> filtered.sortedByDescending { it.size }
            SortOrder.SIZE_ASC -> filtered.sortedBy { it.size }
        }
    }

    fun categoryOf(item: SelectedItem): FileCategory {
        val mime = item.mimeType
        return when {
            mime.startsWith("image/") -> FileCategory.IMAGES
            mime.startsWith("video/") -> FileCategory.VIDEO
            mime.startsWith("audio/") -> FileCategory.AUDIO
            mime.startsWith("text/") || mime == "application/pdf" || "document" in mime ||
                "sheet" in mime || "presentation" in mime || mime == "application/msword" -> FileCategory.DOCUMENTS
            else -> FileCategory.OTHER
        }
    }
}
