package net.nyx.messenger.ui.onion

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.nyx.messenger.R
import net.nyx.messenger.di.ServiceLocator
import net.nyx.messenger.profile.ProfileKeys

/**
 * « Mon adresse Nyx » (v0.2.0) : affichage de l'adresse onion DE L'UTILISATEUR
 * (son identité, ADR-003) + alias d'invitation, copie et partage via la feuille
 * système. C'est LA carte de visite de l'app : l'utilisateur décide à qui la
 * montrer — l'adresse est un identifiant pseudonyme, partager à la demande
 * explicite uniquement (anti-énumération, même logique que wire-format.md §6.4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyOnionScreen(
    onBack: () -> Unit,
    onOpenProfile: () -> Unit
) {
    val context = LocalContext.current
    val identity = ServiceLocator.identity
    val onion = identity.onionAddress.orEmpty()
    val alias = identity.alias
    var copied by remember { mutableStateOf(false) }
    val profileStore = remember { net.nyx.messenger.profile.ProfileStore.get(context) }
    val avatarB64 = profileStore.load().valueOf(ProfileKeys.AVATAR)

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.my_onion_title)) })
    }) { padding ->
        net.nyx.messenger.ui.components.CenteredScrollColumn(Modifier.padding(padding)) {
            // Avatar (s'il est défini — champ privé ou public, l'affichage est local)
            net.nyx.messenger.ui.components.AvatarView(avatarB64)
            net.nyx.messenger.ui.components.AvatarGap()

            if (!alias.isNullOrBlank()) {
                Text("@$alias", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
            }
            Text(stringResource(R.string.my_onion_label), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    onion,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                )
            }
            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("nyx_onion", onion))
                    copied = true
                }) {
                    Text(stringResource(if (copied) R.string.copied else R.string.copy))
                }
                Button(onClick = {
                    // Partage : l'utilisateur choisit le canal ET le destinataire —
                    // Nyx ne transmet rien de son côté (aucune donnée sortante ici).
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText(alias, onion))
                    }
                    context.startActivity(Intent.createChooser(send, context.getString(R.string.my_onion_share_chooser)))
                }) {
                    Text(stringResource(R.string.my_onion_share))
                }
            }
            Spacer(Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.my_onion_privacy_note),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onOpenProfile) {
                Text(stringResource(R.string.my_onion_edit_profile))
            }
        }
    }
}

private fun shareText(alias: String?, onion: String): String =
    buildString {
        if (!alias.isNullOrBlank()) appendLine("@$alias")
        append(onion)
    }
