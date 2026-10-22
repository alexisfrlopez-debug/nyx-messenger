package net.nyx.messenger.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import net.nyx.messenger.R
import net.nyx.messenger.security.PasswordVault

/**
 * v0.2.26 — CRÉATION FORCÉE DES MOTS DE PASSE (demande opérateur : « mon
 * réglage de biométrie active et mes mots de passe ont été réinitialisés, ce
 * qui démontre que le mot de passe protège l'application mais pas le compte —
 * si une clé de récupération est importée ça fonctionne sans mot de passe »).
 *
 * CALQUE OPAQUE au-dessus de toute la navigation (comme [net.nyx.messenger.ui.lock.LockScreen])
 * dès qu'une identité existe sans mot de passe NORMAL :
 *   1. création FORCÉE du mot de passe NORMAL — le compte a désormais une
 *      protection qui survit à l'importation d'une clé de récupération (les
 *      exports de sauvegarde sont chiffrés avec lui, l'import l'exige) ;
 *   2. création FORCÉE du mot de passe DESTRUCTION — sa saisie lors de
 *      l'importation d'une clé de récupération EFFACE le fichier importé
 *      (mécanisme anti-contrainte, cf. RestoreDialog).
 *
 * Il n'est PAS affiché tant qu'aucune identité n'existe (l'onboarding crée
 * d'abord le compte) ni en session PANIQUE (base leurre — ne pas écrire les
 * mots de passe du vrai compte dans cette session).
 */
@Composable
fun PasswordGate() {
    val context = LocalContext.current
    var step by remember { mutableStateOf(0) } // 0 = normal, 1 = destruction
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }

    fun confirm() {
        if (p1.length < 6) {
            err = context.getString(R.string.gate_pwd_too_short)
            return
        }
        if (p1 != p2) {
            err = context.getString(R.string.gate_pwd_mismatch)
            return
        }
        val mode = if (step == 0) PasswordVault.Mode.NORMAL else PasswordVault.Mode.DESTRUCTION
        if (!PasswordVault.set(context, mode, p1)) {
            err = context.getString(R.string.gate_pwd_conflict)
            return
        }
        p1 = ""; p2 = ""; err = null
        step++
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Spacer(Modifier.height(48.dp))
        Text(
            stringResource(if (step == 0) R.string.gate_title_normal else R.string.gate_title_destruction),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(if (step == 0) R.string.gate_desc_normal else R.string.gate_desc_destruction),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(20.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                PasswordField(p1, { p1 = it; err = null }, stringResource(R.string.gate_pwd))
                Spacer(Modifier.height(8.dp))
                PasswordField(p2, { p2 = it; err = null }, stringResource(R.string.gate_pwd_confirm))
                ErrorLine(err)
                Spacer(Modifier.height(12.dp))
                Button(onClick = ::confirm, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.gate_confirm))
                }
            }
        }
    }
}
