package net.nyx.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.nyx.messenger.desktop.ServiceLocator
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/** « Mon adresse Nyx » — miroir de MyOnionScreen Android (copier + alias). */
@Composable
fun MyOnionPanel() {
    val onion = ServiceLocator.identity.onionAddress ?: ""
    val alias = ServiceLocator.identity.alias ?: ""
    val recovery = remember { ServiceLocator.recoveryKey() }
    var copied by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        Text("Mon adresse Nyx", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(6.dp))
        Text(
            "C'est votre identité sur le réseau : partagez-la pour qu'on vous ajoute.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)).padding(16.dp)
        ) {
            Text(onion, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = {
                    Toolkit.getDefaultToolkit().systemClipboard
                        .setContents(StringSelection(onion), null)
                    copied = "adresse copiée"
                }) { Text("Copier l'adresse") }
                Spacer(Modifier.width(8.dp))
                if (alias.isNotBlank()) {
                    Button(onClick = {
                        Toolkit.getDefaultToolkit().systemClipboard
                            .setContents(StringSelection("@$alias"), null)
                        copied = "alias copié"
                    }) { Text("Copier l'alias") }
                }
            }
            if (alias.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("Pseudonyme public : @$alias", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
            copied?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
            }
        }

        Spacer(Modifier.height(16.dp))
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
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (recovery != null) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    Toolkit.getDefaultToolkit().systemClipboard
                        .setContents(StringSelection(recovery), null)
                    copied = "clé copiée"
                }) { Text("Copier la clé") }
            }
        }
    }
}
