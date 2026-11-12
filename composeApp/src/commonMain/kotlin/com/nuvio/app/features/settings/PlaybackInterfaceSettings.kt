package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.isIos
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import com.nuvio.app.features.player.PlayerSettingsUiState

@Composable
internal fun PlaybackInterfaceSettings(
    isTablet: Boolean,
    settings: PlayerSettingsUiState,
) {
    Column(verticalArrangement = Arrangement.spacedBy(if (isTablet) 18.dp else 12.dp)) {
        SettingsSection(
            title = stringResource(Res.string.settings_playback_page_interface),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_legacy_layout),
                    description = stringResource(Res.string.settings_playback_legacy_layout_description),
                    checked = settings.useLegacyPlayerLayout,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setUseLegacyPlayerLayout,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_show_loading_overlay),
                    description = stringResource(Res.string.settings_playback_show_loading_overlay_description),
                    checked = settings.showLoadingOverlay,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setShowLoadingOverlay,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.playback_show_loading_status),
                    description = stringResource(Res.string.playback_show_loading_status_sub),
                    checked = settings.showPlayerLoadingStatus,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setShowPlayerLoadingStatus,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_pause_overlay),
                    description = stringResource(Res.string.settings_playback_pause_overlay_description),
                    checked = settings.pauseOverlayEnabled,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setPauseOverlayEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_parental_guide),
                    description = stringResource(Res.string.settings_playback_parental_guide_description),
                    checked = settings.showParentalGuide,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setShowParentalGuide,
                )
                if (!isIos) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_auto_remove_black_bars),
                        description = stringResource(Res.string.settings_playback_auto_remove_black_bars_description),
                        checked = settings.autoSwitchToAutoAspect,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setAutoSwitchToAutoAspect,
                    )
                }
                // Chapters work on every engine: ExoPlayer and libmpv on Android, mpv on iOS.
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_exo_chapters),
                    description = stringResource(Res.string.settings_playback_exo_chapters_description),
                    checked = settings.androidExoChaptersEnabled,
                    enabled = !settings.externalPlayerEnabled,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setAndroidExoChaptersEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_chapter_title),
                    description = stringResource(Res.string.settings_playback_chapter_title_description),
                    checked = settings.chapterTitleEnabled,
                    enabled = !settings.externalPlayerEnabled && settings.androidExoChaptersEnabled,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setChapterTitleEnabled,
                )
            }
        }
    }
}
