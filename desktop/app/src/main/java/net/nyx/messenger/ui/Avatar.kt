package net.nyx.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.nyx.messenger.media.MediaIngest

/**
 * Pastille d'avatar — v0.3.4 « parité Android » : IMAGE réelle (base64 du
 * profil, miroir AvatarView Android) avec repli INITIAL (l'avatar absent ou
 * illisible reste une pastille lettrée — échec silencieux, jamais de crash).
 * Composant PARTAGÉ barre latérale + carnet + conversation + fiches.
 */
@Composable
fun Avatar(label: String, avatarB64: String = "", sizeDp: Int = 34) {
    // Décodage Skia (JPEG du profil) — repli initial si absent/illisible.
    val decoded: ImageBitmap? = remember(avatarB64) {
        runCatching {
            if (avatarB64.isBlank()) null
            else org.jetbrains.skia.Image.makeFromEncoded(
                java.util.Base64.getDecoder().decode(avatarB64)
            ).asImageBitmap()
        }.getOrNull()
    }
    Box(
        Modifier.size(sizeDp.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary),
        contentAlignment = Alignment.Center
    ) {
        if (decoded != null) {
            androidx.compose.foundation.Image(
                bitmap = decoded,
                contentDescription = "avatar",
                modifier = Modifier.size(sizeDp.dp).clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                label.take(1).uppercase(),
                color = MaterialTheme.colorScheme.onSecondary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
