package net.nyx.messenger.ui.components

import android.content.Context
import android.net.Uri
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.nyx.messenger.R
import net.nyx.messenger.security.PasswordVault

/**
 * v0.2.26 — briques de dialogue partagées (audit RED-DUP) :
 *
 *  - [readSafText] : lecture texte d'un document SAF (sauvegardes de compte,
 *    exports de conversations) — UNE seule copie de la mécanique
 *    openInputStream/bufferedReader ;
 *  - [DialogDismissButton] : bouton « Annuler » standard des AlertDialog ;
 *  - [BackupPasswordDialog] : confirmation du mot de passe du compte AVANT
 *    l'export d'une sauvegarde chiffrée — partagé par l'onboarding (clé de
 *    récupération) et Paramètres (sauvegarde de l'identité).
 */

/** Lecture texte d'un document SAF — null si illisible. */
suspend fun readSafText(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    runCatching {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
    }.getOrNull()
}

/** Bouton « Annuler » standard des dialogues. */
@Composable
fun DialogDismissButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(stringResource(R.string.cancel)) }
}

/**
 * Confirme le mot de passe NORMAL du compte puis invoque [onConfirmed] avec
 * lui (le fichier de sauvegarde sera chiffré avec ce mot de passe).
 */
@Composable
fun BackupPasswordDialog(onDismiss: () -> Unit, onConfirmed: (String) -> Unit) {
    val context = LocalContext.current
    var pwd by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_pwd_title)) },
        text = {
            Column {
                Text(stringResource(R.string.backup_pwd_desc), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                PasswordField(
                    pwd, { pwd = it; err = null },
                    stringResource(R.string.gate_pwd), isError = err != null
                )
                ErrorLine(err)
            }
        },
        confirmButton = {
            Button(onClick = {
                // Le mot de passe doit être CELUI DU COMPTE (mode NORMAL) —
                // c'est lui qui dévérouillera le fichier à l'importation.
                val mode = PasswordVault.verify(context, pwd)
                if (mode != PasswordVault.Mode.NORMAL) {
                    err = context.getString(R.string.backup_pwd_wrong)
                } else {
                    onConfirmed(pwd)
                }
            }) { Text(stringResource(R.string.backup_pwd_confirm)) }
        },
        dismissButton = { DialogDismissButton(onDismiss) }
    )
}
