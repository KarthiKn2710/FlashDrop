package com.flashdrop.feature.send

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.flashdrop.core.designsystem.FdColors
import com.flashdrop.core.designsystem.FdScaffold
import com.flashdrop.core.designsystem.PrimaryButton
import com.flashdrop.core.designsystem.SecondaryButton
import com.flashdrop.core.designsystem.fileIcon
import com.flashdrop.core.model.Formatters
import com.flashdrop.core.model.SelectedItem
import com.flashdrop.domain.repository.SelectionRepository
import com.flashdrop.domain.usecase.FileCategory
import com.flashdrop.domain.usecase.FilterSelectionUseCase
import com.flashdrop.domain.usecase.SortOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class SelectionUiState(
    val visible: List<SelectedItem> = emptyList(),
    val totalCount: Int = 0,
    val totalBytes: Long = 0,
    val loading: Boolean = false,
    val query: String = "",
    val category: FileCategory = FileCategory.ALL,
    val sort: SortOrder = SortOrder.NAME,
)

private data class Filters(val query: String = "", val category: FileCategory = FileCategory.ALL, val sort: SortOrder = SortOrder.NAME)

@HiltViewModel
class SelectionViewModel @Inject constructor(
    private val selection: SelectionRepository,
    private val filter: FilterSelectionUseCase,
) : ViewModel() {
    private val filters = MutableStateFlow(Filters())

    val state: StateFlow<SelectionUiState> = combine(selection.items, selection.loading, filters) { items, loading, f ->
        SelectionUiState(
            visible = filter(items, f.query, f.category, f.sort),
            totalCount = items.size,
            totalBytes = items.sumOf { it.size },
            loading = loading,
            query = f.query,
            category = f.category,
            sort = f.sort,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SelectionUiState())

    fun addFiles(uris: List<String>) = selection.addFiles(uris)
    fun addFolder(uri: String) = selection.addFolder(uri)
    fun remove(uri: String) = selection.remove(uri)
    fun clear() = selection.clear()
    fun setQuery(value: String) { filters.value = filters.value.copy(query = value) }
    fun setCategory(value: FileCategory) { filters.value = filters.value.copy(category = value) }
    fun setSort(value: SortOrder) { filters.value = filters.value.copy(sort = value) }
}

@Composable
fun SelectionScreen(onBack: () -> Unit, onContinue: () -> Unit, viewModel: SelectionViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The system picker is the Storage Access Framework: no broad storage permission is needed.
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.addFiles(uris.map { it.toString() })
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.addFolder(uri.toString())
    }

    FdScaffold(
        title = "Select files",
        onBack = onBack,
        actions = {
            if (state.totalCount > 0) TextButton(onClick = viewModel::clear) { Text("Clear") }
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                HorizontalDivider(color = FdColors.Border)
                Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (state.totalCount == 0) "Nothing selected" else "${state.totalCount} ${if (state.totalCount == 1) "item" else "items"}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(Formatters.bytes(state.totalBytes), style = MaterialTheme.typography.bodySmall, color = FdColors.TextSecondary)
                    }
                    PrimaryButton("Continue", onContinue, enabled = state.totalCount > 0 && !state.loading)
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton("Add files", { pickFiles.launch(arrayOf("*/*")) }, Modifier.weight(1f), icon = Icons.Rounded.NoteAdd)
                SecondaryButton("Add folder", { pickFolder.launch(null) }, Modifier.weight(1f), icon = Icons.Rounded.CreateNewFolder)
            }
            if (state.loading) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp).height(2.dp),
                    color = FdColors.Accent,
                    trackColor = FdColors.Elevated,
                )
            } else {
                Spacer(Modifier.height(12.dp))
            }

            if (state.totalCount == 0) {
                EmptySelection()
            } else {
                Filters(state, viewModel)
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
                    items(state.visible, key = { it.uri }) { item -> SelectedRow(item) { viewModel.remove(item.uri) } }
                    if (state.visible.isEmpty()) {
                        item {
                            Text(
                                "No selected item matches this filter.",
                                Modifier.padding(vertical = 24.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = FdColors.TextSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptySelection() {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Choose what to send", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Add files or whole folders. Folder structure is kept on the other device.",
                style = MaterialTheme.typography.bodyMedium,
                color = FdColors.TextSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Filters(state: SelectionUiState, viewModel: SelectionViewModel) {
    var sortOpen by remember { mutableStateOf(false) }
    Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Search selection") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = FdColors.Border,
                focusedBorderColor = FdColors.Accent,
                unfocusedContainerColor = FdColors.Surface,
                focusedContainerColor = FdColors.Surface,
            ),
        )
        Box {
            IconButton(onClick = { sortOpen = true }) {
                Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = "Sort", tint = FdColors.TextSecondary)
            }
            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }, containerColor = FdColors.Elevated) {
                for ((order, label) in listOf(SortOrder.NAME to "Name", SortOrder.SIZE_DESC to "Largest first", SortOrder.SIZE_ASC to "Smallest first")) {
                    DropdownMenuItem(
                        text = { Text(label, color = if (order == state.sort) FdColors.Accent else FdColors.TextPrimary) },
                        onClick = {
                            viewModel.setSort(order)
                            sortOpen = false
                        },
                    )
                }
            }
        }
    }
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((category, label) in CATEGORIES) {
            FilterChip(
                selected = state.category == category,
                onClick = { viewModel.setCategory(category) },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = FdColors.Surface,
                    labelColor = FdColors.TextSecondary,
                    selectedContainerColor = FdColors.AccentSoft,
                    selectedLabelColor = FdColors.Accent,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = state.category == category,
                    borderColor = FdColors.Border,
                    selectedBorderColor = FdColors.Accent.copy(alpha = 0.4f),
                ),
            )
        }
    }
}

private val CATEGORIES = listOf(
    FileCategory.ALL to "All",
    FileCategory.IMAGES to "Images",
    FileCategory.VIDEO to "Video",
    FileCategory.AUDIO to "Audio",
    FileCategory.DOCUMENTS to "Documents",
    FileCategory.OTHER to "Other",
)

@Composable
private fun SelectedRow(item: SelectedItem, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(fileIcon(item.name, item.isDirectory), contentDescription = null, tint = FdColors.TextSecondary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val folder = item.relativePath.substringBeforeLast('/', "")
            Text(
                if (item.isDirectory) "Empty folder" else Formatters.bytes(item.size) + if (folder.isEmpty()) "" else " · $folder",
                style = MaterialTheme.typography.bodySmall,
                color = FdColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Rounded.Close, contentDescription = "Remove ${item.name}", tint = FdColors.TextSecondary, modifier = Modifier.size(20.dp))
        }
    }
}
