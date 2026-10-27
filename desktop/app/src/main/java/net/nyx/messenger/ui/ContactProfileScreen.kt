package net.nyx.messenger.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * v0.3.4 « parité Android » — FICHE DU CONTACT, MIROIR de
 * ContactProfileScreen Android : photo, nom affiché, @pseudo, adresse onion
 * (copiable), bio, site, localisation — récupérées à la demande
 * (GET /client/profile) puis en cache (TTL 1 h, miroir refreshPeerProfiles).
 */
@Composable
fun ContactProfileScreen(peer: String, onBack: () -> Unit, onMessage: (String) -> Unit) {
    var profile by remember(peer) { mutableStateOf(ServiceLocator.store.peerProfile(peer)) }
    var loading by remember(peer) { mutableStateOf(true) }
    var copied by remember(peer) { mutableStateOf<String?>(null) }

    LaunchedEffect(peer) {
        ServiceLocator.repository.fetchProfile(peer)
        profile = ServiceLocator.store.peerProfile(peer)
        loading = false
    }

    val contact = remember(peer) { ServiceLocator.store.contact(peer) }
    val alias = contact?.alias?.takeIf { it.isNotBlank() } ?: (peer.take(10) + "…")
    val displayName = profile?.displayName?.takeIf { it.isNotBlank() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(displayName ?: alias, profile?.avatarB64.orEmpty(), 56)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(displayName ?: alias, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (displayName != null) Text("@$alias", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(14.dp))

        // Adresse onion (copiable — miroir Android).
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                .padding(12.dp)
        ) {
            Text("Adresse Nyx", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
            Text(peer, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row {
                Button(onClick = {
                    copyToClipboard(peer); copied = "Adresse copiée."
                }) { Text("Copier l'adresse") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { onMessage(peer) }) { Text("Écrire") }
            }
            copied?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        Spacer(Modifier.height(12.dp))

        when {
            loading -> Text("Récupération de la fiche via Tor…", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
            profile == null || (profile!!.displayName.isBlank() && profile!!.bio.isBlank()) ->
                Text(
                    "Ce contact n'a pas encore publié de fiche publique.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.outline
                )
            else -> {
                if (!profile!!.bio.isBlank()) {
                    ProfileCard("Bio") { Text(profile!!.bio, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (!profile!!.website.isBlank()) {
                    ProfileCard("Site web") { Text(profile!!.website, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
                }
                if (!profile!!.location.isBlank()) {
                    ProfileCard("Localisation") { Text(profile!!.location, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Retour") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ProfileCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Text(title, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        content()
    }
}

internal fun copyToClipboard(text: String) {
    runCatching {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
            java.awt.datatransfer.StringSelection(text), null
        )
    }
}
