package com.flashdrop.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * A few labelled figures ("Transferred 1.4 MB · Speed 7 MB/s · Time 3s"). Side by side at
 * normal text sizes; with large text there is no room for columns, so each figure gets its own
 * line with the label on the left and the value on the right. Each figure is read as one item.
 */
@Composable
fun StatRow(stats: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    val large = LocalDensity.current.fontScale >= LARGE_TEXT
    if (large) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, value) in stats) {
                Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(label, Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    Text(value, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.End)
                }
            }
        }
    } else {
        Row(modifier.fillMaxWidth()) {
            for ((label, value) in stats) {
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                    SectionLabel(label)
                    Spacer(Modifier.height(2.dp))
                    Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                }
            }
        }
    }
}

private const val LARGE_TEXT = 1.3f
