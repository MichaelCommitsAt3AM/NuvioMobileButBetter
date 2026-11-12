package com.nuvio.app.features.party

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.party_code_label
import nuvio.composeapp.generated.resources.party_end
import nuvio.composeapp.generated.resources.party_join_action
import nuvio.composeapp.generated.resources.party_join_description
import nuvio.composeapp.generated.resources.party_join_title
import nuvio.composeapp.generated.resources.party_leave
import nuvio.composeapp.generated.resources.party_now_watching
import nuvio.composeapp.generated.resources.party_seats
import nuvio.composeapp.generated.resources.party_sign_in_required
import nuvio.composeapp.generated.resources.party_title
import nuvio.composeapp.generated.resources.party_watch_with_party
import org.jetbrains.compose.resources.stringResource

/**
 * Entry to watch parties from outside the player: a round translucent button over the Home
 * hero that opens a sheet to join by code, or to see / leave the party you are in.
 * Renders nothing when the backend does not offer watch parties.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartyHomeEntry(
    modifier: Modifier = Modifier,
    onWatch: (PartyMedia) -> Unit,
) {
    val available by PartyRepository.available.collectAsStateWithLifecycle()
    if (!available) return
    val state by PartyRepository.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val tokens = MaterialTheme.nuvio

    Box(modifier = modifier.statusBarsPadding().padding(top = 8.dp, end = 16.dp)) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Groups,
                contentDescription = stringResource(Res.string.party_title),
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
        if (state.party != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF62C889)),
            )
        }
    }

    if (open) {
        ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = tokens.colors.surfaceSheet,
        ) {
            HomePartySheetContent(
                state = state,
                onOpenTitle = { media ->
                    open = false
                    onWatch(media)
                },
                onDismiss = { open = false },
            )
        }
    }
}

@Composable
private fun HomePartySheetContent(
    state: PartyUiState,
    onOpenTitle: (PartyMedia) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val focusRequester = remember { FocusRequester() }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<PartyError?>(null) }
    val party = state.party

    // Straight to the sources for the party's episode; the host's own link plays on its own.
    fun open(party: ActiveParty) = onOpenTitle(party.media)

    fun submit() {
        if (!isValidPartyCode(code) || state.busy) return
        scope.launch {
            error = null
            val result = PartyRepository.join(code)
            if (result != null) {
                haptic.performHapticFeedback(HapticFeedbackType.Reject)
                error = result
            } else {
                PartyRepository.state.value.party?.let(::open)
            }
        }
    }

    LaunchedEffect(party == null) {
        if (party == null) {
            delay(350)
            runCatching { focusRequester.requestFocus() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!PartyRepository.canUse) {
            Text(
                text = stringResource(Res.string.party_sign_in_required),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textSecondary,
            )
        } else if (party == null) {
            Text(
                text = stringResource(Res.string.party_join_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary,
            )
            Text(
                text = stringResource(Res.string.party_join_description),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textSecondary,
            )
            CodeField(
                code = code,
                onCodeChange = { value ->
                    if (state.busy) return@CodeField
                    code = value
                    error = null
                },
                onDone = ::submit,
                focusRequester = focusRequester,
                enabled = true,
                modifier = Modifier.padding(top = 4.dp),
            )
            error?.let { current ->
                Text(
                    text = stringResource(current.messageRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            DialogButton(
                text = stringResource(Res.string.party_join_action),
                onClick = ::submit,
                style = DialogButtonStyle.Primary,
                enabled = isValidPartyCode(code) && !state.busy,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                text = stringResource(Res.string.party_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary,
            )
            Text(
                text = stringResource(Res.string.party_now_watching, party.media.title),
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
            )
            Text(
                text = stringResource(Res.string.party_seats, party.members.size, party.maxMembers),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textSecondary,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(tokens.colors.surfaceCard)
                    .clickable {
                        clipboard.setText(AnnotatedString(party.code))
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.party_code_label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textSecondary,
                )
                Text(
                    text = formatPartyCode(party.code),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.colors.textPrimary,
                )
            }
            DialogButton(
                text = stringResource(Res.string.party_watch_with_party),
                onClick = { open(party) },
                style = DialogButtonStyle.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
            DialogButton(
                text = stringResource(if (party.isHost) Res.string.party_end else Res.string.party_leave),
                onClick = {
                    if (party.isHost) PartyRepository.end() else PartyRepository.leave()
                    onDismiss()
                },
                style = DialogButtonStyle.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
