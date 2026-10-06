package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.core.storage.*

@Composable
internal fun MediaCacheSettingsRows(
    settings: MediaCacheSettings,
    activeBudget: MediaCacheBudget,
    saveFailed: Boolean = false,
    saving: Boolean = false,
    initialFocusRequester: FocusRequester? = null,
    onSave: (MediaCacheSettings) -> Unit
) {
    SettingsToggleRow(
        title = stringResource(R.string.telumia_cache_auto),
        subtitle = stringResource(R.string.telumia_cache_auto_description),
        checked = settings.mode == MediaCacheMode.AUTO,
        enabled = !saving,
        modifier = if (initialFocusRequester != null) Modifier.focusRequester(initialFocusRequester) else Modifier,
        onToggle = { onSave(settings.copy(mode = if (settings.mode == MediaCacheMode.AUTO) MediaCacheMode.MANUAL else MediaCacheMode.AUTO)) }
    )
    if (settings.mode == MediaCacheMode.MANUAL) {
        val values = listOf(32, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536)
        val chosen = ((settings.manualBytes ?: MediaCachePlatform.TV.initialBytes) / MIB).toInt()
        SliderSettingsItem(
            title = stringResource(R.string.telumia_cache_manual),
            subtitle = stringResource(R.string.telumia_cache_manual_description),
            values = (values + chosen).distinct().sorted(),
            selected = chosen,
            enabled = !saving,
            valueText = "$chosen MiB",
            onValueChange = { onSave(settings.copy(manualBytes = it.toLong() * MIB)) }
        )
    }
    SettingsNote(stringResource(R.string.telumia_cache_active, activeBudget.totalBytes / MIB))
    SettingsNote(stringResource(R.string.telumia_cache_restart))
    if (saving) SettingsNote(stringResource(R.string.telumia_cache_saving))
    if (saveFailed) SettingsNote(stringResource(R.string.telumia_cache_save_error), tone = SettingsNoteTone.Danger)
}
