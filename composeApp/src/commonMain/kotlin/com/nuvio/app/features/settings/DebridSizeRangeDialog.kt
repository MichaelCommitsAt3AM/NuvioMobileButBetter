package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.features.debrid.debridSizePresetsGb
import com.nuvio.app.features.debrid.formatDebridSizeGb
import com.nuvio.app.features.debrid.parseDebridSizeGb
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_clear
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.settings_debrid_selection_any
import nuvio.composeapp.generated.resources.settings_debrid_size_custom
import nuvio.composeapp.generated.resources.settings_debrid_size_custom_hint
import nuvio.composeapp.generated.resources.settings_debrid_size_invalid
import nuvio.composeapp.generated.resources.settings_debrid_size_inverted
import nuvio.composeapp.generated.resources.settings_debrid_size_maximum
import nuvio.composeapp.generated.resources.settings_debrid_size_minimum
import nuvio.composeapp.generated.resources.settings_debrid_size_range
import org.jetbrains.compose.resources.stringResource

/** Independent bounds with a local draft: only Save updates the profile's preferences. */
@Composable
internal fun DebridSizeRangeDialog(minGb: Double, maxGb: Double, onSave: (Double, Double) -> Unit, onDismiss: () -> Unit) {
    // null selects Custom; zero is an unbounded side (Any).
    var minChoice by rememberSaveable(minGb, maxGb) { mutableStateOf(minGb.takeIf { it in debridSizePresetsGb }) }
    var maxChoice by rememberSaveable(minGb, maxGb) { mutableStateOf(maxGb.takeIf { it in debridSizePresetsGb }) }
    var minText by rememberSaveable(minGb, maxGb) { mutableStateOf(if (minChoice == null) minGb.toString().removeSuffix(".0") else "") }
    var maxText by rememberSaveable(minGb, maxGb) { mutableStateOf(if (maxChoice == null) maxGb.toString().removeSuffix(".0") else "") }
    var openMinimum by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val minimumLabel = stringResource(Res.string.settings_debrid_size_minimum)
    val maximumLabel = stringResource(Res.string.settings_debrid_size_maximum)
    val anyLabel = stringResource(Res.string.settings_debrid_selection_any)
    val customLabel = stringResource(Res.string.settings_debrid_size_custom)
    val invalidMessage = stringResource(Res.string.settings_debrid_size_invalid)
    val invertedMessage = stringResource(Res.string.settings_debrid_size_inverted)

    DialogSurface(onDismissRequest = onDismiss, title = stringResource(Res.string.settings_debrid_size_range)) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SizeBoundField(
                    label = minimumLabel, choice = minChoice, customText = minText, anyLabel = anyLabel, customLabel = customLabel,
                    modifier = Modifier.weight(1f),
                    onExpand = { openMinimum = if (openMinimum == true) null else true },
                    onTextChanged = { minText = it; error = null },
                )
                SizeBoundField(
                    label = maximumLabel, choice = maxChoice, customText = maxText, anyLabel = anyLabel, customLabel = customLabel,
                    modifier = Modifier.weight(1f),
                    onExpand = { openMinimum = if (openMinimum == false) null else false },
                    onTextChanged = { maxText = it; error = null },
                )
            }
            openMinimum?.let { minimum ->
                Text(if (minimum) minimumLabel else maximumLabel, style = MaterialTheme.typography.labelLarge)
                val selected = if (minimum) minChoice else maxChoice
                (debridSizePresetsGb + null).forEach { option ->
                    DialogOption(
                        text = when (option) { null -> customLabel; 0.0 -> anyLabel; else -> formatDebridSizeGb(option) },
                        selected = option == selected,
                        onClick = {
                            if (minimum) minChoice = option else maxChoice = option
                            openMinimum = null
                            error = null
                        },
                    )
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        DialogButtons {
            DialogButton(text = stringResource(Res.string.action_clear), onClick = {
                minChoice = 0.0; maxChoice = 0.0; minText = ""; maxText = ""; openMinimum = null; error = null
            })
            DialogButton(text = stringResource(Res.string.action_save), style = DialogButtonStyle.Primary, onClick = {
                val min = minChoice ?: parseDebridSizeGb(minText)
                val max = maxChoice ?: parseDebridSizeGb(maxText)
                when {
                    min == null || max == null -> error = invalidMessage
                    max > 0.0 && min > max -> error = invertedMessage
                    else -> { onSave(min, max); onDismiss() }
                }
            })
        }
    }
}

@Composable
private fun SizeBoundField(
    label: String, choice: Double?, customText: String, anyLabel: String, customLabel: String,
    modifier: Modifier, onExpand: () -> Unit, onTextChanged: (String) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        OutlinedButton(
            onClick = onExpand,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                when (choice) { null -> if (customText.isBlank()) customLabel else "$customText GB"; 0.0 -> anyLabel; else -> formatDebridSizeGb(choice) },
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null)
        }
        if (choice == null) {
            OutlinedTextField(
                value = customText, onValueChange = onTextChanged,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$label in GB" }, singleLine = true,
                placeholder = { Text(stringResource(Res.string.settings_debrid_size_custom_hint), maxLines = 1) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        }
    }
}
