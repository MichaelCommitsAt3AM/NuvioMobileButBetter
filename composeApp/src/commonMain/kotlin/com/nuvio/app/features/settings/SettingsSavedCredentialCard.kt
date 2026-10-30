package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.profiles.ProfileRepository

@Composable
internal fun SettingsSavedCredentialCard(onReplace: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.CheckCircleOutline, contentDescription = null, tint = Color(0xFF4CAF70))
        Text("Key saved", modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onReplace) { Text("Replace key") }
    }
}

@Composable
internal fun SettingsCredentialRow(title: String, value: String, isTablet: Boolean, onReplace: () -> Unit) {
    if (value.isBlank()) {
        SettingsNavigationRow(title = title, description = "Not set", isTablet = isTablet, onClick = onReplace)
    } else {
        Column(Modifier.fillMaxWidth().padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            SettingsSavedCredentialCard(onReplace)
        }
    }
}

@Composable
internal fun SettingsApiKeyEditor(title: String, description: String, label: String, value: String, isTablet: Boolean, onCommit: (String) -> Unit) {
    val profileId = ProfileRepository.activeProfileId
    var replacing by rememberSaveable(profileId, value) { mutableStateOf(false) }
    var draft by rememberSaveable(profileId, value) { mutableStateOf("") }
    val configured = value.isNotBlank()
    Column(Modifier.fillMaxWidth().padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (configured && !replacing) {
            SettingsSavedCredentialCard(onReplace = { replacing = true })
        } else {
            SettingsSecretTextField(value = draft, onValueChange = { draft = it }, label = label, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onCommit(draft.trim()); replacing = false; draft = "" }, enabled = draft.trim().isNotBlank() && draft.trim() != value) { Text("Save") }
                if (configured) {
                    OutlinedButton(onClick = { replacing = false; draft = "" }) { Text("Cancel") }
                    TextButton(onClick = { onCommit(""); replacing = false; draft = "" }) { Text("Clear") }
                }
            }
        }
    }
}
