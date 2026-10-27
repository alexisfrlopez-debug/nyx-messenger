package net.nyx.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.nyx.messenger.crypto.PasswordCrypto
import net.nyx.messenger.desktop.ServiceLocator
import net.nyx.messenger.replica.EmbeddedReplica
import net.nyx.messenger.security.DesktopVault
import net.nyx.messenger.update.DesktopUpdater
import java.io.File
import javax.swing.JFileChooser

/**
 * Réglages — v0.3.4 « parité Android » : MIROIR de SettingsScreen Android,
 * MÊMES cartes et MÊMES boutons : Thème (3 états, réactif), Fermeture
 * (Demander/Réduire/Quitter), Mots de passe (NORMAL/PANIC/DESTRUCTION),
 * Mise à jour (état + Vérifier maintenant + Installer + canal test),
 * Réplique embarquée (stats + Purger + adresse), Répliques personnalisés
 * (LISTE), Statut des réplicas (live), Sauvegarde de compte (clé + export),
 * Export/import des conversations, Compte (Déconnecter), Notifications,
 * Zone dangereuse, À propos.
 */
@Composable
fun SettingsPanel(onAccountDisconnected: () -> Unit) {
    val scope = rememberCoroutineScope()
    val s = ServiceLocator.settings.get()
    var message by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)) {

        // ---------- Thème (radio — miroir Android, appliqué À CHAUD) ----------
        SettingCard("Thème") {
            RadioRow("Système", s.theme == "système") { ServiceLocator.setTheme("système") }
            RadioRow("Sombre (nuit)", s.theme == "nuit") { ServiceLocator.setTheme("nuit") }
            RadioRow("Claire (aube)", s.theme == "aube") { ServiceLocator.setTheme("aube") }
            Text("Appliqué immédiatement — plus besoin de redémarrer.", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
        }

        // ---------- Fermeture (radio — miroir Android) ----------
        SettingCard("Fermeture") {
            RadioRow("Demander", s.closeBehavior == "demander") { ServiceLocator.settings.update { it.copy(closeBehavior = "demander") } }
            RadioRow("Réduire (Tor actif, plateau)", s.closeBehavior == "reduire") { ServiceLocator.settings.update { it.copy(closeBehavior = "reduire") } }
            RadioRow("Quitter", s.closeBehavior == "quitter") { ServiceLocator.settings.update { it.copy(closeBehavior = "quitter") } }
        }

        // ---------- Notifications ----------
        SettingCard("Notifications") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Messages et invitations", Modifier.weight(1f), fontSize = 12.sp)
                Switch(checked = s.notificationsEnabled, onCheckedChange = {
                    ServiceLocator.settings.update { c -> c.copy(notificationsEnabled = it) }
                })
            }
        }

        // ---------- Mots de passe (miroir PasswordsCard Android) ----------
        PasswordsCard()

        // ---------- Mise à jour (miroir UpdateCard Android) ----------
        SettingCard("Mise à jour") {
            when (val st = DesktopUpdater.state) {
                is DesktopUpdater.State.Idle -> Text("Aucun contrôle effectué.", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                is DesktopUpdater.State.Checking -> Text("Contrôle en cours…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                is DesktopUpdater.State.UpToDate -> Text("À jour (${st.tag}).", fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)
                is DesktopUpdater.State.Available -> Text("Version ${st.release.version} disponible — téléchargement automatique…", fontSize = 12.sp)
                is DesktopUpdater.State.Downloading -> Column {
                    Text("Téléchargement ${st.version} : ${st.bytes / 1048576} Mo", fontSize = 12.sp)
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { if (st.total > 0) st.bytes.toFloat() / st.total else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                is DesktopUpdater.State.Ready -> Column {
                    Text("Version ${st.version} prête.", fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)
                    Button(onClick = { DesktopUpdater.install() }) { Text("Installer la mise à jour") }
                }
                is DesktopUpdater.State.Failed -> Text("Échec : ${st.reason}", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { scope.launch { DesktopUpdater.checkNow() } }) { Text("Vérifier maintenant") }
                Spacer(Modifier.width(12.dp))
                Text("Canal test (préversions)", fontSize = 11.sp)
                Switch(checked = s.updateTestChannel, onCheckedChange = {
                    ServiceLocator.settings.update { c -> c.copy(updateTestChannel = it) }
                })
            }
        }

        // ---------- Réplique embarquée (miroir EmbeddedReplicaCard Android) ----------
        SettingCard("Réplique embarquée") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Votre appareil héberge vos messages et relaie ceux de vos contacts " +
                            "(rétention 48 h, quotas symétriques).",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = s.embeddedReplicaEnabled, onCheckedChange = {
                    ServiceLocator.settings.update { c -> c.copy(embeddedReplicaEnabled = it) }
                    EmbeddedReplica.serving = it
                })
            }
            if (s.embeddedReplicaEnabled && ServiceLocator.identity.onionAddress != null) {
                val stats = remember(refresh) { EmbeddedReplica.stats() }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Adresse : ${ServiceLocator.identity.onionAddress}:80",
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Journal : ${stats.first} événement(s), ${stats.second / 1024} Ko",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.outline
                )
                Row {
                    TextButton(onClick = {
                        copyToClipboard("${ServiceLocator.identity.onionAddress}:80")
                        message = "Adresse de réplique copiée."
                    }) { Text("Copier l'adresse") }
                    TextButton(onClick = {
                        val n = EmbeddedReplica.purgeNow()
                        refresh++
                        message = "Purgé : $n événement(s) expiré(s) supprimé(s)."
                    }) { Text("Purger") }
                }
            }
        }

        // ---------- Répliques personnalisés (LISTE — miroir ReplicasListCard) ----------
        CustomReplicasCard()

        // ---------- Statut des réplicas (live — miroir ReplicaStatusCard) ----------
        SettingCard("Statut des réplicas") {
            val probe = ServiceLocator.replicaProbe.value
            val replicas = ServiceLocator.repository.replicas
            if (replicas.isEmpty()) {
                Text("Aucun réplica connu pour l'instant.", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
            } else {
                Text(
                    "Sonde d'ensemble : ${probe.okCount}/${probe.total} joignable(s)" +
                        (probe.detail?.let { " — $it" } ?: ""),
                    fontSize = 11.sp,
                    color = if (probe.ok == true) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(4.dp))
                replicas.take(6).forEach { addr ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (addr.length > 26) addr.take(14) + "…" + addr.takeLast(8) else addr,
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                        if (addr == "${ServiceLocator.identity.onionAddress}:80") {
                            Text("EMBARQUÉ", fontSize = 9.sp, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (replicas.size > 6) Text("+ ${replicas.size - 6} autre(s)…", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
            }
        }

        // ---------- Sauvegarde de compte (miroir BackupCard Android) ----------
        BackupCard()

        // ---------- Conversations (miroir ConversationsCard Android) ----------
        ConversationsCard()

        // ---------- Compte (miroir AccountCard Android) ----------
        AccountCard(onAccountDisconnected)

        // ---------- Zone dangereuse ----------
        SettingCard("Zone dangereuse") {
            Text(
                "Efface identité, messages, contacts et réglages de CET appareil. " +
                    "Action irréversible — pensez à la sauvegarde.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.error
            )
            Button(
                onClick = {
                    ServiceLocator.wipeAll()
                    onAccountDisconnected()
                },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) { Text("Effacer toutes les données") }
        }

        // ---------- À propos ----------
        SettingCard("À propos") {
            Text("Nyx — messagerie de la nuit", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Client Windows v${ServiceLocator.VERSION} · Tor 0.4.9 (embarqué) · " +
                    "Chiffrement de bout en bout libsodium (X25519 + Ed25519 + AES-GCM).\n" +
                    "Android v0.2.53 · Console v0.8.12 · Serveur v0.2.38.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Le code du client est publié sous AGPL-3.0. Les conversations ne transitent " +
                    "jamais en clair sur le réseau.",
                fontSize = 10.sp, color = MaterialTheme.colorScheme.outline
            )
        }

        message?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ---------------- cartes ----------------

@Composable
fun SettingCard(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(4.dp))
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)).padding(14.dp)
        ) { content() }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, fontSize = 12.sp)
    }
}

/** Mots de passe NORMAL/PANIC/DESTRUCTION (miroir PasswordsCard Android v0.2.18). */
@Composable
private fun PasswordsCard() {
    var editing by remember { mutableStateOf<DesktopVault.Mode?>(null) }
    var pwd1 by remember { mutableStateOf("") }
    var pwd2 by remember { mutableStateOf("") }
    var oldPwd by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    SettingCard("Mots de passe") {
        Text(
            "NORMAL déverrouille l'application. PANIC ouvre une session LEURRE (données vides — " +
                "coercition). DESTRUCTION efface TOUT sur-le-champ.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        for (mode in DesktopVault.Mode.entries) {
            val set = DesktopVault.isSet(mode)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (mode) {
                        DesktopVault.Mode.NORMAL -> "Mot de passe normal"
                        DesktopVault.Mode.PANIC -> "Mot de passe panique (leurre)"
                        DesktopVault.Mode.DESTRUCTION -> "Mot de passe destruction"
                    },
                    Modifier.weight(1f), fontSize = 12.sp
                )
                if (!set) {
                    TextButton(onClick = { editing = mode; pwd1 = ""; pwd2 = ""; oldPwd = ""; error = null }) { Text("Créer") }
                } else {
                    TextButton(onClick = { editing = mode; pwd1 = ""; pwd2 = ""; oldPwd = ""; error = null }) { Text("Changer") }
                    TextButton(onClick = {
                        DesktopVault.remove(mode)
                        info = "Mot de passe ${mode.name.lowercase()} retiré."
                    }) { Text("Retirer") }
                }
            }
        }
        info?.let { Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.tertiary) }
    }

    editing?.let { mode ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = {
                Text(
                    when (mode) {
                        DesktopVault.Mode.NORMAL -> "Mot de passe normal"
                        DesktopVault.Mode.PANIC -> "Mot de passe panique"
                        DesktopVault.Mode.DESTRUCTION -> "Mot de passe destruction"
                    }
                )
            },
            text = {
                Column {
                    if (DesktopVault.isSet(mode)) {
                        PasswordField(value = oldPwd, onValueChange = { oldPwd = it }, label = "Ancien mot de passe")
                        Spacer(Modifier.height(6.dp))
                    }
                    PasswordField(value = pwd1, onValueChange = { pwd1 = it }, label = "Mot de passe (6 caractères minimum)")
                    Spacer(Modifier.height(6.dp))
                    PasswordField(value = pwd2, onValueChange = { pwd2 = it }, label = "Confirmer")
                    ErrorLine(error)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (pwd1.length < 6) { error = "6 caractères minimum"; return@TextButton }
                    if (pwd1 != pwd2) { error = "Les deux mots de passe diffèrent"; return@TextButton }
                    try {
                        if (DesktopVault.isSet(mode)) {
                            DesktopVault.change(mode, oldPwd.toCharArray(), pwd1.toCharArray())
                        } else {
                            DesktopVault.set(mode, pwd1.toCharArray())
                        }
                        info = "Mot de passe ${mode.name.lowercase()} enregistré."
                        error = null; editing = null
                    } catch (e: Exception) {
                        error = e.message
                    }
                }) { Text("Valider") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Annuler") } }
        )
    }
}

/** LISTE des réplicas personnalisés (miroir ReplicasListCard Android). */
@Composable
private fun CustomReplicasCard() {
    val customs = ServiceLocator.settings.get().customReplicas
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    SettingCard("Répliques personnalisés") {
        Text(
            "Ajoutez l'adresse d'un réplica vivant de la flotte (console) pour amorcer la découverte.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        customs.forEach { addr ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(addr, Modifier.weight(1f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                TextButton(onClick = {
                    ServiceLocator.setCustomReplicas(customs - addr)
                }) { Text("Retirer") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                placeholder = { Text("onion:80", fontSize = 11.sp) },
                modifier = Modifier.weight(1f), singleLine = true,
                textStyle = TextStyle(fontSize = 12.sp)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val addr = input.trim().lowercase()
                if (!net.nyx.messenger.net.BootstrapReplicas.ENTRY.matches(addr)) {
                    error = "adresse invalide (56 caractères .onion + :port)"
                } else {
                    ServiceLocator.setCustomReplicas(customs + addr)
                    input = ""; error = null
                }
            }) { Text("Ajouter") }
        }
        error?.let { Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.error) }
    }
}

/** Sauvegarde de compte (miroir BackupCard : clé + export chiffré). */
@Composable
private fun BackupCard() {
    val recovery = remember { ServiceLocator.recoveryKey() }
    var reveal by remember { mutableStateOf(false) }
    var pwd1 by remember { mutableStateOf("") }
    var pwd2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    SettingCard("Sauvegarde de compte") {
        Text(
            "La clé de récupération restaure votre compte ENTIER (même adresse onion). " +
                "L'export est chiffré par mot de passe — lisible par l'appli téléphone.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        if (recovery != null) {
            Text(
                if (reveal) recovery else "nyx1•••••••••••••••••••••••••••",
                fontSize = 11.sp, fontFamily = FontFamily.Monospace
            )
            Row {
                TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Masquer" else "Afficher") }
                TextButton(onClick = { copyToClipboard(recovery); info = "Clé copiée." }) { Text("Copier") }
            }
        } else {
            Text("indisponible (identité protégée par mot de passe)", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
        }
        Spacer(Modifier.height(8.dp))
        PasswordField(value = pwd1, onValueChange = { pwd1 = it }, label = "Mot de passe du fichier")
        Spacer(Modifier.height(4.dp))
        PasswordField(value = pwd2, onValueChange = { pwd2 = it }, label = "Confirmer")
        error?.let { Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(6.dp))
        Button(onClick = {
            if (pwd1.length < 6) { error = "6 caractères minimum"; return@Button }
            if (pwd1 != pwd2) { error = "Les deux mots de passe diffèrent"; return@Button }
            val chooser = JFileChooser()
            chooser.selectedFile = File("nyx-sauvegarde-${yyyyMMDDHHmm()}.txt")
            if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                val envelope = PasswordCrypto.encrypt(
                    ServiceLocator.exportBackupJson().toByteArray(Charsets.UTF_8), pwd1.toCharArray()
                )
                chooser.selectedFile.writeText(envelope)
                error = null
                info = "Sauvegarde écrite : ${chooser.selectedFile.name}"
            }
        }) { Text("Exporter la sauvegarde…") }
        info?.let { Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.tertiary) }
    }
}

/** Export / import des conversations (miroir ConversationsCard v0.2.26). */
@Composable
private fun ConversationsCard() {
    val scope = rememberCoroutineScope()
    var selectOpen by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var pwd1 by remember { mutableStateOf("") }
    var pwd2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    SettingCard("Export / import des conversations") {
        Text(
            "Exportez les conversations choisies dans un fichier chiffré par mot de passe, " +
                "ré-importable sur n'importe quelle installation (textes ; les fichiers joints ne partent pas en binaire).",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Row {
            Button(onClick = { selectOpen = true }) { Text("Exporter") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val chooser = JFileChooser()
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            val content = chooser.selectedFile.readText()
                            val text = if (PasswordCrypto.isEncrypted(content)) {
                                // Le mot de passe est demandé via le champ ci-dessous.
                                if (pwd1.isBlank()) error("mot de passe requis (remplissez le champ puis réessayez)")
                                PasswordCrypto.decrypt(content, pwd1.toCharArray())
                                    ?.toString(Charsets.UTF_8) ?: error("Mot de passe incorrect")
                            } else content
                            val n = ServiceLocator.importConversationsJson(text)
                            info = "$n message(s) importé(s)."
                            error = null
                        }.onFailure { error = it.message }
                    }
                }
            }) { Text("Importer") }
        }
        Spacer(Modifier.height(4.dp))
        PasswordField(value = pwd1, onValueChange = { pwd1 = it }, label = "Mot de passe du fichier (import)")
        error?.let { Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.error) }
        info?.let { Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.tertiary) }
    }

    if (selectOpen) {
        val accepted = ServiceLocator.store.contacts().filter { it.status == net.nyx.messenger.desktop.DesktopStore.ContactStatus.ACCEPTED }
        AlertDialog(
            onDismissRequest = { selectOpen = false },
            title = { Text("Choisir les conversations") },
            text = {
                if (accepted.isEmpty()) Text("Aucun contact accepté")
                else Column {
                    accepted.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(
                                checked = c.onion in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + c.onion else selected - c.onion
                                }
                            )
                            Text(c.alias.ifBlank { c.onion.take(10) + "…" }, fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    PasswordField(value = pwd2, onValueChange = { pwd2 = it }, label = "Mot de passe du fichier")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (pwd2.length < 6) { error = "6 caractères minimum"; return@TextButton }
                    val chooser = JFileChooser()
                    chooser.selectedFile = File("nyx-conversations-${System.currentTimeMillis() / 1000}.json")
                    if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                        val envelope = PasswordCrypto.encrypt(
                            ServiceLocator.exportConversationsJson(selected.toList()).toByteArray(Charsets.UTF_8),
                            pwd2.toCharArray()
                        )
                        chooser.selectedFile.writeText(envelope)
                        info = "Export écrit : ${chooser.selectedFile.name}"
                    }
                    selectOpen = false
                }) { Text("Continuer") }
            },
            dismissButton = { TextButton(onClick = { selectOpen = false }) { Text("Annuler") } }
        )
    }
}

/** Compte (miroir AccountCard — « Déconnecter le compte » vérifié par mot de passe). */
@Composable
private fun AccountCard(onAccountDisconnected: () -> Unit) {
    var pwd by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val alias = ServiceLocator.identity.alias ?: ""
    val onion = ServiceLocator.identity.onionAddress ?: ""

    SettingCard("Compte") {
        Text("Pseudo : ${if (alias.isBlank()) "(aucun)" else "@$alias"}", fontSize = 12.sp)
        Text(onion, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text(
            "Déconnecte le compte de cet appareil : tout est effacé (identité, conversations, " +
                "mots de passe) et l'application revient à la création de compte.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(4.dp))
        PasswordField(value = pwd, onValueChange = { pwd = it }, label = "Mot de passe du compte (vérification)")
        error?.let { Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = {
                if (ServiceLocator.disconnectAccount(pwd.toCharArray())) {
                    onAccountDisconnected()
                } else {
                    error = "Mot de passe incorrect."
                }
            },
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            )
        ) { Text("Déconnecter le compte") }
    }
}

private fun yyyyMMDDHHmm(): String =
    java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
