package net.nyx.messenger.ui.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.nyx.messenger.R
import net.nyx.messenger.di.ServiceLocator
import net.nyx.messenger.profile.AvatarCodec
import net.nyx.messenger.profile.OwnProfile
import net.nyx.messenger.profile.ProfileKeys
import net.nyx.messenger.profile.ProfileStore
import net.nyx.messenger.security.AppLock

/**
 * Édition du profil (v0.2.0 — wire-format.md §7) : nom affiché, bio, site,
 * localisation, avatar (photo picker système — aucune permission requise).
 *
 * VISIBILITÉ PAR CHAMP : un interrupteur public/privé par champ. « Privé » =
 * le champ ne quitte JAMAIS l'appareil (stockage local chiffré par l'OS) ;
 * « public » = diffusé dans le journal (événement `profile`, visible par qui
 * consulte cette identité). Aucun champ n'est public par défaut (ADR-010 :
 * publication = choix explicite).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ProfileStore.get(context) }
    val repo = remember { ServiceLocator.repository }

    // État local de l'écran (chargé du store à l'entrée)
    var profile by remember { mutableStateOf(store.load()) }
    var displayName by remember { mutableStateOf(profile.valueOf(ProfileKeys.DISPLAY_NAME)) }
    var bio by remember { mutableStateOf(profile.valueOf(ProfileKeys.BIO)) }
    var website by remember { mutableStateOf(profile.valueOf(ProfileKeys.WEBSITE)) }
    var location by remember { mutableStateOf(profile.valueOf(ProfileKeys.LOCATION)) }
    var avatarB64 by remember { mutableStateOf(profile.valueOf(ProfileKeys.AVATAR)) }
    var pubDisplayName by remember { mutableStateOf(profile.isPublic(ProfileKeys.DISPLAY_NAME)) }
    var pubBio by remember { mutableStateOf(profile.isPublic(ProfileKeys.BIO)) }
    var pubWebsite by remember { mutableStateOf(profile.isPublic(ProfileKeys.WEBSITE)) }
    var pubLocation by remember { mutableStateOf(profile.isPublic(ProfileKeys.LOCATION)) }
    var pubAvatar by remember { mutableStateOf(profile.isPublic(ProfileKeys.AVATAR)) }

    var publishState by remember { mutableStateOf<String?>(null) }
    var publishError by remember { mutableStateOf<String?>(null) }

    val avatar = remember(avatarB64) {
        avatarB64.takeIf { it.isNotBlank() }?.let { AvatarCodec.fromBase64(it) }
    }

    // Photo picker système (androidx.activity — pas de permission, pas de GMS).
    // v0.2.1 : le picker est une activité système — son ouverture met MainActivity
    // en pause ; on annonce ce passage en arrière-plan ATTENDU (AppLock) pour que
    // choisir une photo ne verrouille pas l'application, et la lecture/décodage
    // sont protégés (IO + runCatching) : une URI expirée ou une image illisible
    // produit un message d'erreur, jamais un crash ni une perte d'édition.
    val pickAvatar = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val b64 = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = context.contentResolver.openInputStream(uri)?.use {
                            it.readBytes()
                        }
                        bytes?.let { AvatarCodec.downscaleToBase64(it) }
                    }.getOrNull()
                }
                if (b64 != null) {
                    avatarB64 = b64
                    publishError = null
                } else {
                    publishError = context.getString(R.string.profile_avatar_error)
                }
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.profile_title)) })
    }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                stringResource(R.string.profile_visibility_intro),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))

            // ---- avatar ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (avatar != null) {
                    Image(
                        bitmap = avatar.asImageBitmap(),
                        contentDescription = stringResource(R.string.profile_avatar_desc),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(72.dp).clip(CircleShape)
                    )
                } else {
                    Card(
                        shape = CircleShape,
                        modifier = Modifier.size(72.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(Icons.Filled.AddAPhoto, contentDescription = stringResource(R.string.profile_avatar_desc))
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.profile_avatar), style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            AppLock.suspendNextBackground()
                            pickAvatar.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }) {
                            Text(stringResource(R.string.profile_pick_avatar))
                        }
                        if (avatarB64.isNotBlank()) {
                            TextButton(onClick = { avatarB64 = "" }) {
                                Text(stringResource(R.string.profile_remove))
                            }
                        }
                    }
                    VisibilityRow(
                        label = if (pubAvatar) stringResource(R.string.profile_public_on)
                                else stringResource(R.string.profile_public_off),
                        checked = pubAvatar,
                        onChange = { pubAvatar = it }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // ---- nom affiché ----
            FieldWithVisibility(
                label = stringResource(R.string.profile_display_name),
                value = displayName,
                onValue = { displayName = it },
                public = pubDisplayName,
                onPublic = { pubDisplayName = it }
            )
            // ---- bio ----
            FieldWithVisibility(
                label = stringResource(R.string.profile_bio),
                value = bio,
                onValue = { bio = it },
                public = pubBio,
                onPublic = { pubBio = it },
                singleLine = false,
                maxLines = 5
            )
            // ---- site ----
            FieldWithVisibility(
                label = stringResource(R.string.profile_website),
                value = website,
                onValue = { website = it },
                public = pubWebsite,
                onPublic = { pubWebsite = it }
            )
            // ---- localisation ----
            FieldWithVisibility(
                label = stringResource(R.string.profile_location),
                value = location,
                onValue = { location = it },
                public = pubLocation,
                onPublic = { pubLocation = it }
            )

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    scope.launch {
                        // 1. Persistance locale COMPLÈTE (publics + privés)
                        var p: OwnProfile = store.load()
                        p = setField(store, p, ProfileKeys.DISPLAY_NAME, displayName, pubDisplayName)
                        p = setField(store, p, ProfileKeys.BIO, bio, pubBio)
                        p = setField(store, p, ProfileKeys.WEBSITE, website, pubWebsite)
                        p = setField(store, p, ProfileKeys.LOCATION, location, pubLocation)
                        p = setField(store, p, ProfileKeys.AVATAR, avatarB64, pubAvatar)
                        profile = p
                        // 2. Publication de la SEULE partie publique (diffusion)
                        repo.publishProfile(store).fold(
                            onSuccess = {
                                publishState = context.getString(R.string.profile_saved)
                                publishError = null
                            },
                            onFailure = { e ->
                                // La carte reste sauvegardée localement ; la
                                // publication réessayera à la prochaine sauvegarde.
                                publishState = context.getString(R.string.profile_saved_local_only)
                                publishError = e.message
                            }
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.profile_save))
            }
            publishState?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
            publishError?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.profile_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun setField(
    store: ProfileStore,
    current: OwnProfile,
    key: String,
    value: String,
    public: Boolean
): OwnProfile {
    val merged = current.fields.toMutableMap()
    if (value.isBlank()) merged.remove(key) else merged[key] =
        net.nyx.messenger.profile.ProfileField(value.trim(), public)
    val next = OwnProfile(merged)
    store.save(next)
    return next
}

/** Champ texte + interrupteur de visibilité (public/privé) aligné sous le champ. */
@Composable
private fun FieldWithVisibility(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    public: Boolean,
    onPublic: (Boolean) -> Unit,
    singleLine: Boolean = true,
    maxLines: Int = 1
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        singleLine = singleLine,
        maxLines = maxLines,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    )
    VisibilityRow(
        label = if (public) stringResource(R.string.profile_public_on) else stringResource(R.string.profile_public_off),
        checked = public,
        onChange = onPublic
    )
}

@Composable
private fun VisibilityRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
