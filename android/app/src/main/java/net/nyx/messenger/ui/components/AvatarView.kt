package net.nyx.messenger.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.nyx.messenger.R
import net.nyx.messenger.profile.AvatarCodec

/**
 * Avatar rond — ou un placeholder « personne » — partagé par « Mon adresse »,
 * l'édition de profil et le profil de contact (v0.2.0). Le décodage base64 →
 * Bitmap est mémoïsé par clé (recall léger au recomposition).
 */
@Composable
fun AvatarView(avatarB64: String?, size: Dp = 96.dp) {
    androidx.compose.foundation.layout.Box(
        contentAlignment = Alignment.Center
    ) {
        val avatar = remember(avatarB64) {
            avatarB64?.takeIf { it.isNotBlank() }?.let { AvatarCodec.fromBase64(it) }
        }
        if (avatar != null) {
            Image(
                bitmap = avatar.asImageBitmap(),
                contentDescription = stringResource(R.string.profile_avatar_desc),
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape)
            )
        } else {
            Icon(
                Icons.Filled.Person,
                contentDescription = stringResource(R.string.profile_avatar_desc),
                modifier = Modifier.size(size * 0.75f),
                tint = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

/** Espacement standard sous un avatar. */
@Composable
fun AvatarGap() {
    Spacer(Modifier.height(12.dp))
}

/**
 * Colonne d'écran centrée, scrollable, padding standard 24 dp — squelette
 * partagé des écrans « fiches » (Mon adresse, profil contact, etc.).
 * Évite la duplication du chaînage de modificateurs entre écrans.
 */
@Composable
fun CenteredScrollColumn(
    base: Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = base
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}
