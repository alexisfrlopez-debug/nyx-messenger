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
import androidx.compose.material3.TextButton
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
import net.nyx.messenger.crypto.PasswordCrypto
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Onboarding desktop — miroir de OnboardingScreen Android : création
 * d'identité (ed25519 → onion), alias optionnel, mot de passe de verrou
 * optionnel ; OU restauration d'une sauvegarde.
 *
 * v0.3.2 — RESTAURATION RÉELLE DES FICHIERS DE SAUVEGARDE (retour terrain :
 * « je n'arrive pas à restaurer un fichier de sauvegarde de compte que
 * j'ai généré avec l'appli téléphone ») : l'import accepte désormais TOUT
 * ce que le téléphone exporte — l'enveloppe chiffrée nyx-enc-v1.… (mot de
 * passe du compte demandé), le JSON v1/v2 en clair des versions anciennes,
 * et la clé nue « nyx1.… » collée — par SÉLECTEUR DE FICHIER (miroir du
 * « Choisir un fichier de sauvegarde… » Android) ou par collage.
 */
@Composable
fun OnboardingScreen(
    onCreate: (alias: String?, password: CharArray?) -> Unit,
    onRestore: (backupText: String, backupPassword: CharArray?, identityPassword: CharArray?) -> Unit,
) {
    var mode by remember { mutableStateOf("create") }
    var alias by remember { mutableStateOf("") }
    var usePassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var restoreInput by remember { mutableStateOf("") }
    var backupPwd by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var fileName by remember { mutableStateOf<String?>(null) }

    // Enveloppe chiffrée détectée dans le contenu courant → champ mot de passe.
    val encrypted = PasswordCrypto.isEncrypted(restoreInput)

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
                    modifier = Modifier.clickable { mode = "restore"; error = null }
                )
            } else {
                Text(
                    "Importez le fichier de sauvegarde généré par votre téléphone (ou un autre PC) : " +
                        "même adresse onion, avec l'alias, le profil, les contacts et la position de lecture. " +
                        "Vous pouvez aussi coller directement la clé de récupération (nyx1.…) — " +
                        "elle restaure l'identité seule.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = restoreInput,
                    onValueChange = { restoreInput = it; error = null },
                    label = { Text("Clé de récupération (nyx1.…) ou contenu de la sauvegarde") },
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                    singleLine = false
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        val fc = JFileChooser()
                        fc.dialogTitle = "Importer une sauvegarde de compte Nyx"
                        fc.isMultiSelectionEnabled = false
                        fc.fileFilter = FileNameExtensionFilter(
                            "Sauvegarde Nyx (txt, json, nyx) ou tout fichier", "txt", "json", "nyx"
                        )
                        fc.setAcceptAllFileFilterUsed(true)
                        if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                            val f: File = fc.selectedFile
                            runCatching {
                                require(f.length() in 1..(2L * 1024 * 1024)) {
                                    "fichier trop volumineux ou vide (${f.length()} octets)"
                                }
                                f.readText(Charsets.UTF_8)
                            }.fold(
                                onSuccess = {
                                    restoreInput = it.trim()
                                    fileName = f.name
                                    error = null
                                },
                                onFailure = { e ->
                                    fileName = null
                                    error = "lecture impossible : ${e.message}"
                                }
                            )
                        }
                    }) { Text("Importer un fichier de sauvegarde…") }
                    fileName?.let {
                        Spacer(Modifier.width(8.dp))
                        Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp))
                    }
                }
                if (encrypted) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = backupPwd,
                        onValueChange = { backupPwd = it; error = null },
                        label = { Text("Mot de passe de la sauvegarde (celui du compte téléphone)") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = { Text("Fichier chiffré détecté (nyx-enc-v1) — mot de passe requis") }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = usePassword, onCheckedChange = { usePassword = it })
                    Text("Protéger l'identité locale par un mot de passe", fontSize = 12.sp)
                }
                if (usePassword) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Mot de passe local (optionnel, ne quitte pas ce PC)") },
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
                            val backupPassword = if (encrypted) backupPwd.toCharArray() else null
                            val identityPassword = if (usePassword) password.toCharArray() else null
                            onRestore(restoreInput, backupPassword, identityPassword)
                        } catch (e: Exception) {
                            busy = false
                            error = e.message ?: "sauvegarde invalide"
                        }
                    },
                    enabled = !busy && restoreInput.isNotBlank() && (!encrypted || backupPwd.isNotEmpty()),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Restaurer mon compte") }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Créer une nouvelle identité",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { mode = "create"; error = null }
                )
            }

            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }
    }
}
