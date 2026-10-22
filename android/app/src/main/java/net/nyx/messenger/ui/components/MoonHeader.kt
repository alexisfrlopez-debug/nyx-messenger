package net.nyx.messenger.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.nyx.messenger.R

/**
 * v0.2.35 — EN-TÊTE « NYX, DÉESSE DE LA NUIT » (refonte esthétique) :
 * croissant + constellation (drawable nyx_moon_stars) au-dessus du titre
 * des écrans d'entrée (Connexions, Onboarding). Extrait en composant dès
 * la première duplication (règle RED-DUP de l'audit — même motif que
 * ScreenColumn en v0.2.5).
 */
@Composable
fun MoonHeader(size: Dp = 84.dp) {
    Image(
        painter = painterResource(R.drawable.nyx_moon_stars),
        contentDescription = null,
        modifier = Modifier.size(size)
    )
}
