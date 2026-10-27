package net.nyx.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.nyx.messenger.desktop.ServiceLocator

/**
 * « Mon adresse Nyx » — v0.3.4 « parité Android » : MIROIR de MyOnionScreen
 * Android : photo + @pseudo, adresse copiable, « Partager » (équivalent
 * bureau du partage système Android : carte de contact formatée prête à
 * coller/envoyer), note de confidentialité, lien « Modifier mon profil »,
 * clé de récupération.
 */
@Composable
fun MyOnionPanel(onOpenMyProfile: () -> Unit) {
    val onion = ServiceLocator.identity.onionAddress ?: ""
    val alias = ServiceLocator.identity.alias ?: ""
    val profile = remember { ServiceLocator.store.myProfile() }
    val displayName = profile.displayName.takeIf { it.isNotBlank() } ?: alias.ifBlank { "Nyx" }
    val recovery = remember { ServiceLocator.recoveryKey() }
    var copied by remember { mutableStateOf<String?>(null) }
    var shareOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(displayName, profile.avatarB64, 52)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(displayName, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                if (alias.isNotBlank()) {
                    Text("@$alias", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Mon adresse Nyx", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(4.dp))
        Text(
            "C'est votre identité sur le réseau : partagez-la pour qu'on vous ajoute.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)).padding(16.dp)
        ) {
            Text(onion, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = {
                    copyToClipboard(onion); copied = "adresse copiée"
                }) { Text("Copier") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { shareOpen = true }) { Text("Partager") }
                Spacer(Modifier.width(8.dp))
                if (alias.isNotBlank()) {
                    Button(onClick = {
                        copyToClipboard("@$alias"); copied = "alias copié"
                    }) { Text("Copier l'alias") }
                }
            }
            copied?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
            }
        }

        Spacer(Modifier.height(10.dp))
        // Note de confidentialité (miroir Android privacy_note).
        Text(
            "Votre adresse onion ne révèle ni votre IP ni votre localisation : elle voyage " +
                "chiffrée de bout en bout à travers le réseau Tor.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onOpenMyProfile) { Text("Modifier mon profil") }

        Spacer(Modifier.height(12.dp))
        Text("Clé de récupération", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
        Text(
            "Elle restaure votre compte ENTIER (même adresse onion) — gardez-la hors ligne, " +
                "traitée comme un mot de passe maître.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)).padding(16.dp)
        ) {
            Text(
                recovery ?: "indisponible (identité protégée par mot de passe)",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (recovery != null) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    copyToClipboard(recovery); copied = "clé copiée"
                }) { Text("Copier la clé") }
            }
        }
    }

    // « Partager » (équivalent bureau de la feuille de partage Android).
    if (shareOpen) {
        val card = buildString {
            append("Mon adresse Nyx :\n")
            append(onion)
            if (alias.isNotBlank()) append("\nMon pseudo : @$alias")
            if (displayName.isNotBlank() && displayName != alias) append("\n$displayName")
        }
        AlertDialog(
            onDismissRequest = { shareOpen = false },
            title = { Text("Partager votre adresse") },
            text = {
                Column {
                    Text(card, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Copiez cette carte et envoyez-la par le canal de votre choix.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    copyToClipboard(card)
                    copied = "carte copiée"
                    shareOpen = false
                }) { Text("Copier") }
            },
            dismissButton = { TextButton(onClick = { shareOpen = false }) { Text("Fermer") } }
        )
    }
}
