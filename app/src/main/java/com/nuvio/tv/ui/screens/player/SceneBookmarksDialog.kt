@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme
import java.text.DateFormat
import java.util.Date

@Composable
internal fun SceneBookmarksDialog(
    state: SceneBookmarkPanelState,
    positionMs: Long,
    canSave: Boolean,
    onSave: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onJump: (String) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val defaultName = stringResource(R.string.scene_bookmarks_default_name)
    var name by remember(state.scope) { mutableStateOf(defaultName) }
    var editingId by remember(state.scope) { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    val close = remember { FocusRequester() }
    val writable = !state.loading && !state.busy && !state.failed && state.scope != null
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(24.dp)) {
            val compact = maxHeight < 440.dp
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().heightIn(max = maxHeight)
                .align(androidx.compose.ui.Alignment.Center)
                .background(NuvioTheme.colors.Background, RoundedCornerShape(24.dp)).padding(if (compact) 16.dp else 24.dp)
                .testTag("scene-bookmarks-panel"), verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
                Text(stringResource(R.string.scene_bookmarks_title), style = androidx.tv.material3.MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.scene_bookmarks_scope_notice), style = androidx.tv.material3.MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it.filterNot(Char::isISOControl).take(128) },
                        label = { androidx.compose.material3.Text(stringResource(R.string.scene_bookmarks_name)) }, singleLine = true,
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedTextColor = androidx.compose.ui.graphics.Color.White,
                            unfocusedTextColor = androidx.compose.ui.graphics.Color.White,
                            focusedLabelColor = androidx.compose.ui.graphics.Color.White,
                            unfocusedLabelColor = NuvioTheme.colors.TextSecondary,
                            cursorColor = androidx.compose.ui.graphics.Color.White),
                        enabled = writable, modifier = Modifier.weight(1f).testTag("scene-bookmark-name"))
                    Button(onClick = {
                        val id = editingId
                        if (id == null) onSave(name) else { onRename(id, name); editingId = null; name = defaultName }
                    }, enabled = writable && name.isNotBlank() && (editingId != null || canSave),
                        modifier = Modifier.focusRequester(first).testTag("scene-bookmark-save")) {
                        Text(if (editingId == null) stringResource(R.string.scene_bookmarks_save, formatTime(positionMs))
                            else stringResource(R.string.scene_bookmarks_rename))
                    }
                }
                when {
                    state.loading || state.busy -> Text(stringResource(R.string.scene_bookmarks_loading))
                    state.failed -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.scene_bookmarks_error), modifier = Modifier.weight(1f))
                        Button(onClick = onRetry) { Text(stringResource(R.string.scene_bookmarks_retry)) }
                    }
                    state.scope == null -> Text(stringResource(R.string.scene_bookmarks_unavailable))
                    state.items.isEmpty() -> Text(stringResource(R.string.scene_bookmarks_empty))
                }
                LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth().testTag("scene-bookmark-list"),
                    verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(4.dp)) {
                    items(state.items, key = { it.id }) { item ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { onJump(item.id) }, enabled = writable,
                                modifier = Modifier.weight(1f).testTag("scene-bookmark-jump-${item.id}")) {
                                Column {
                                    Text("${formatTime(item.positionMs)} · ${item.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(remember(item.createdAtMs) { DateFormat.getDateInstance(DateFormat.SHORT).format(Date(item.createdAtMs)) },
                                        style = androidx.tv.material3.MaterialTheme.typography.labelSmall)
                                }
                            }
                            Button(onClick = { editingId = item.id; name = item.name; first.requestFocus() }, enabled = writable,
                                modifier = Modifier.testTag("scene-bookmark-rename-${item.id}")) { Text(stringResource(R.string.scene_bookmarks_rename)) }
                            Button(onClick = { onRemove(item.id) }, enabled = writable,
                                modifier = Modifier.testTag("scene-bookmark-remove-${item.id}")) { Text(stringResource(R.string.scene_bookmarks_remove)) }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (editingId != null) Button(onClick = { editingId = null; name = defaultName }) { Text(stringResource(R.string.scene_bookmarks_cancel)) }
                    Button(onClick = onDismiss, modifier = Modifier.focusRequester(close).testTag("scene-bookmark-close")) {
                        Text(stringResource(R.string.scene_bookmarks_close))
                    }
                }
            }
            LaunchedEffect(writable, canSave) {
                if (writable && canSave) first.requestFocusAfterFrames() else close.requestFocusAfterFrames()
            }
        }
    }
}
