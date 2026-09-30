package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.formatPlaybackSpeedLabel
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_done
import nuvio.composeapp.generated.resources.settings_playback_hold_speed
import nuvio.composeapp.generated.resources.settings_playback_hold_to_speed
import nuvio.composeapp.generated.resources.settings_playback_hold_to_speed_description
import nuvio.composeapp.generated.resources.settings_touch_controls_brightness
import nuvio.composeapp.generated.resources.settings_touch_controls_brightness_description
import nuvio.composeapp.generated.resources.settings_touch_controls_double_tap_seek
import nuvio.composeapp.generated.resources.settings_touch_controls_double_tap_seek_description
import nuvio.composeapp.generated.resources.settings_touch_controls_section_gestures
import nuvio.composeapp.generated.resources.settings_touch_controls_swipe_seek
import nuvio.composeapp.generated.resources.settings_touch_controls_swipe_seek_description
import nuvio.composeapp.generated.resources.settings_touch_controls_volume
import nuvio.composeapp.generated.resources.settings_touch_controls_volume_description
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.touchControlsSettingsContent(isTablet: Boolean) {
    item {
        val playerSettingsUiState by remember {
            PlayerSettingsRepository.ensureLoaded()
            PlayerSettingsRepository.uiState
        }.collectAsStateWithLifecycle()
        val isExternalPlayer = playerSettingsUiState.externalPlayerEnabled
        var showHoldToSpeedValueDialog by remember { mutableStateOf(false) }

        SettingsSection(
            title = stringResource(Res.string.settings_touch_controls_section_gestures),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_touch_controls_double_tap_seek),
                    description = stringResource(Res.string.settings_touch_controls_double_tap_seek_description),
                    checked = playerSettingsUiState.gestureDoubleTapSeekEnabled,
                    enabled = !isExternalPlayer,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setGestureDoubleTapSeekEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_touch_controls_swipe_seek),
                    description = stringResource(Res.string.settings_touch_controls_swipe_seek_description),
                    checked = playerSettingsUiState.gestureSwipeSeekEnabled,
                    enabled = !isExternalPlayer,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setGestureSwipeSeekEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_touch_controls_brightness),
                    description = stringResource(Res.string.settings_touch_controls_brightness_description),
                    checked = playerSettingsUiState.gestureBrightnessEnabled,
                    enabled = !isExternalPlayer,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setGestureBrightnessEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_touch_controls_volume),
                    description = stringResource(Res.string.settings_touch_controls_volume_description),
                    checked = playerSettingsUiState.gestureVolumeEnabled,
                    enabled = !isExternalPlayer,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setGestureVolumeEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_hold_to_speed),
                    description = stringResource(Res.string.settings_playback_hold_to_speed_description),
                    checked = playerSettingsUiState.holdToSpeedEnabled,
                    enabled = !isExternalPlayer,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setHoldToSpeedEnabled,
                )
                if (playerSettingsUiState.holdToSpeedEnabled && !isExternalPlayer) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_playback_hold_speed),
                        description = formatPlaybackSpeedLabel(playerSettingsUiState.holdToSpeedValue),
                        isTablet = isTablet,
                        onClick = { showHoldToSpeedValueDialog = true },
                    )
                }
            }
        }

        if (showHoldToSpeedValueDialog) {
            HoldToSpeedValueDialog(
                selectedSpeed = playerSettingsUiState.holdToSpeedValue,
                onSpeedSelected = { speed ->
                    PlayerSettingsRepository.setHoldToSpeedValue(speed)
                    showHoldToSpeedValueDialog = false
                },
                onDismiss = { showHoldToSpeedValueDialog = false },
            )
        }
    }
}

@Composable
private fun HoldToSpeedValueDialog(
    selectedSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_playback_hold_speed),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { speed ->
                val isSelected = speed == selectedSpeed
                DialogOption(
                    text = formatPlaybackSpeedLabel(speed),
                    selected = isSelected,
                    onClick = { onSpeedSelected(speed) },
                )
            }
        }

        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_done),
                onClick = onDismiss,
            )
        }
    }
}
