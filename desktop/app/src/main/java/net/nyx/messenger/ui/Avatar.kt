package net.nyx.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Pastille d'avatar (initiale de l'alias/onion) — composant PARTAGÉ
 * barre latérale + carnet de contacts (l'audit RED-DUP du session 70 a
 * attrapé la copie : refactorée en composant unique).
 */
@Composable
fun Avatar(label: String, sizeDp: Int = 34) {
    Box(
        Modifier.size(sizeDp.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label.take(1).uppercase(),
            color = MaterialTheme.colorScheme.onSecondary,
            fontWeight = FontWeight.Bold
        )
    }
}
