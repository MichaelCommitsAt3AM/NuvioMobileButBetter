package com.nuvio.app.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.formatPlaybackSpeedLabel
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_playback_dialog_close
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
@OptIn(ExperimentalMaterial3Api::class)
private fun HoldToSpeedValueDialog(
    selectedSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

    BasicAlertDialog(
        onDismissRequest = onDismiss,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_playback_hold_speed),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.forEach { speed ->
                        val isSelected = speed == selectedSpeed
                        val containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSpeedSelected(speed) },
                            shape = RoundedCornerShape(12.dp),
                            color = containerColor,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = formatPlaybackSpeedLabel(speed),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                Box(
                                    modifier = Modifier.size(24.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(Res.string.settings_playback_dialog_close),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
