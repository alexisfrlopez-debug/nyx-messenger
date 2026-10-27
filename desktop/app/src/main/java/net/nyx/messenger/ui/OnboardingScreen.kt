package net.nyx.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.nyx.messenger.crypto.AliasValidator

/**
 * Onboarding desktop — miroir de OnboardingScreen Android : création
 * d'identité (ed25519 → onion), alias optionnel, mot de passe de verrou
 * optionnel ; OU restauration par clé de récupération (nyx1.…).
 */
@Composable
fun OnboardingScreen(
    onCreate: (alias: String?, password: CharArray?) -> Unit,
    onRestore: (key: String, password: CharArray?) -> Unit,
) {
    var mode by remember { mutableStateOf("create") }
    var alias by remember { mutableStateOf("") }
    var usePassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var recoveryKey by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.width(460.dp).clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Lune + titre
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text("☾", fontSize = 30.sp, color = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.height(12.dp))
            Text("Nyx", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text(
                "Messagerie anonyme, chiffrée de bout en bout, entièrement sur Tor.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))

            if (mode == "create") {
                Text(
                    "Votre identité sera votre adresse onion — générée localement, " +
                        "aucune inscription, aucun numéro, aucun email.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = AliasValidator.normalize(it) },
                    label = { Text("Pseudonyme (optionnel)") },
                    supportingText = { Text("3-24 caractères : a-z 0-9 _ — réservé sur le réseau") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = usePassword, onCheckedChange = { usePassword = it })
                    Text("Protéger l'identité par un mot de passe", fontSize = 12.sp)
                }
                if (usePassword) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Mot de passe") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (usePassword && password.length < 4) { error = "mot de passe trop court (≥ 4)"; return@Button }
                        if (alias.isNotBlank() && !AliasValidator.isValid(alias)) {
                            error = "alias invalide (3-24 : a-z 0-9 _)"; return@Button
                        }
                        error = null; busy = true
                        onCreate(alias.ifBlank { null }, if (usePassword) password.toCharArray() else null)
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Créer mon identité Nyx") }
                Spacer(Modifier.height(8.dp))
                Text(
                    "J'ai déjà une identité",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { mode = "restore" }
                )
            } else {
                Text(
                    "Collez votre clé de récupération (nyx1.…) pour retrouver exactement " +
                        "la même adresse onion et le même compte.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = recoveryKey,
                    onValueChange = { recoveryKey = it.trim() },
                    label = { Text("Clé de récupération (nyx1.…)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = usePassword, onCheckedChange = { usePassword = it })
                    Text("Protéger par un mot de passe", fontSize = 12.sp)
                }
                if (usePassword) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Mot de passe") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        error = null; busy = true
                        try {
                            onRestore(recoveryKey, if (usePassword) password.toCharArray() else null)
                        } catch (e: Exception) {
                            busy = false
                            error = e.message ?: "clé invalide"
                        }
                    },
                    enabled = !busy && recoveryKey.startsWith("nyx1."),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Restaurer mon identité") }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Créer une nouvelle identité",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { mode = "create" }
                )
            }

            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }
    }
}

/** clickable importé (fondation) — utilisé pour les liens textuels. */
