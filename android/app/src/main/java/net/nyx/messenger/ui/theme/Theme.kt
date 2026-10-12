package net.nyx.messenger.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Thème Nyx — sombre par défaut (usage messagerie privée), violet nuit + vert oignon
 * (clin d'œil aux onion services).
 */
private val NightPurple = Color(0xFF1C1230)
private val SurfacePurple = Color(0xFF2A1C46)
private val OnionGreen = Color(0xFF7DA65F)
private val AccentPurple = Color(0xFFB39DDB)

private val DarkScheme = darkColorScheme(
    primary = OnionGreen,
    secondary = AccentPurple,
    background = NightPurple,
    surface = SurfacePurple,
    onPrimary = Color(0xFF101410),
    onBackground = Color(0xFFEDE7F6),
    onSurface = Color(0xFFEDE7F6)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF4E7A33),
    secondary = Color(0xFF5E4B8A),
    background = Color(0xFFF7F4FC),
    surface = Color.White
)

@Composable
fun NyxTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content
    )
}
