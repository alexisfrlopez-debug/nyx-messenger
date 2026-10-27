package net.nyx.messenger.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * v0.3.4 « parité Android » — CHAMP MOT DE PASSE partagé, miroir de
 * `ui/components/PasswordField.kt` Android : un seul rendu pour l'écran de
 * verrou, le passe-portail et les dialogues des trois rôles (avant, le trio
 * OutlinedTextField + PasswordVisualTransformation + ligne d'erreur était
 * recopié dans LockScreens et SettingsPanel — duplication relevée par
 * l'audit RED-DUP).
 */

@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Ligne d'erreur bornée sous un champ (même rendu partout). */
@Composable
fun ErrorLine(error: String?) {
    error ?: return
    Spacer(Modifier.height(4.dp))
    Text(error, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
}
