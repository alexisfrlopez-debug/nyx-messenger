package net.nyx.messenger.ui.components

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

/**
 * v0.2.26 — champ de saisie de mot de passe PARTAGÉ (audit RED-DUP) : la
 * même mécanique (masquage, une ligne, pleine largeur) pour tous les
 * dialogues — création forcée [PasswordGate], export chiffré de sauvegarde,
 * import de clé de récupération, export/import de conversations.
 */
@Composable
fun PasswordField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    isError: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        isError = isError,
        modifier = Modifier.fillMaxWidth()
    )
}

/** Ligne d'erreur standard des dialogues (null = rien). */
@Composable
fun ErrorLine(err: String?) {
    err?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}
