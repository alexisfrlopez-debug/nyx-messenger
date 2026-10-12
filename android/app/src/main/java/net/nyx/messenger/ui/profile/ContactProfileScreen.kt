package net.nyx.messenger.ui.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import net.nyx.messenger.R
import net.nyx.messenger.di.ServiceLocator
import net.nyx.messenger.net.ProfileResponse

/**
 * Consultation de la carte publique d'un contact (v0.2.0) — GET /client/profile
 * (dernier seq gagne, wire-format.md §7.3). Consultation à la demande explicite
 * de l'utilisateur (anti-énumération — §7.4). Si le contact n'a rien publié,
 * un message l'indique (cas normal, pas une erreur).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactProfileScreen(peerOnion: String, onBack: () -> Unit) {
    var profile by remember { mutableStateOf<ProfileResponse?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(peerOnion) {
        ServiceLocator.repository.contactProfile(peerOnion).fold(
            onSuccess = { profile = it },
            onFailure = { error = it.message }
        )
        loading = false
    }

    val avatarB64 = profile?.fields?.get("avatar")?.value

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.contact_profile_title)) })
    }) { padding ->
        net.nyx.messenger.ui.components.CenteredScrollColumn(Modifier.padding(padding)) {
            net.nyx.messenger.ui.components.AvatarView(avatarB64)
            net.nyx.messenger.ui.components.AvatarGap()

            profile?.fields?.get("display_name")?.value?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
            }

            Text(
                peerOnion,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))

            when {
                loading -> Text(stringResource(R.string.contact_profile_loading), style = MaterialTheme.typography.bodyMedium)
                profile == null && error == null ->
                    Text(stringResource(R.string.contact_profile_empty), style = MaterialTheme.typography.bodyMedium)
                profile != null -> {
                    profile!!.fields["bio"]?.value?.takeIf { it.isNotBlank() }?.let {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    profile!!.fields["website"]?.value?.takeIf { it.isNotBlank() }?.let {
                        Text(stringResource(R.string.contact_profile_website, it), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(4.dp))
                    }
                    profile!!.fields["location"]?.value?.takeIf { it.isNotBlank() }?.let {
                        Text(stringResource(R.string.contact_profile_location, it), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                else -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
