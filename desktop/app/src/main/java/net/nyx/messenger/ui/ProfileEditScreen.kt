package net.nyx.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.nyx.messenger.desktop.DesktopStore
import net.nyx.messenger.desktop.ServiceLocator
import net.nyx.messenger.media.MediaIngest
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * v0.3.4 « parité Android » — ÉCRAN « Mon profil », MIROIR de
 * ProfileEditScreen Android : photo (carrée centrale 256×256 JPEG — miroir
 * AvatarCodec), champs Nom affiché / Bio / Site / Localisation CHACUN avec
 * interrupteur PUBLIC↔PRIVÉ (ADR-010 : rien n'est public par défaut, les
 * champs privés ne quittent JAMAIS cet appareil), « Enregistrer et publier »
 * (miroir du libellé Android) + repli local si le réplica est injoignable.
 */
@Composable
fun ProfileEditScreen(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val initial = remember { ServiceLocator.store.myProfile() }
    var avatarB64 by remember { mutableStateOf(initial.avatarB64) }
    var avatarPublic by remember { mutableStateOf(initial.avatarPublic) }
    var displayName by remember { mutableStateOf(initial.displayName) }
    var displayNamePublic by remember { mutableStateOf(initial.displayNamePublic) }
    var bio by remember { mutableStateOf(initial.bio) }
    var bioPublic by remember { mutableStateOf(initial.bioPublic) }
    var website by remember { mutableStateOf(initial.website) }
    var websitePublic by remember { mutableStateOf(initial.websitePublic) }
    var location by remember { mutableStateOf(initial.location) }
    var locationPublic by remember { mutableStateOf(initial.locationPublic) }
    var message by remember { mutableStateOf<String?>(null) }
    var avatarError by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Mon profil", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Chaque champ est PRIVÉ (il reste uniquement sur cet appareil) ou PUBLIC (diffusé sur le réseau avec votre identité, visible par tous). Rien n'est public par défaut.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        // ---------- Photo de profil ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(displayName.ifBlank { "Nyx" }, avatarB64, 56)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Photo de profil", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row {
                    TextButton(onClick = {
                        val chooser = JFileChooser()
                        chooser.fileFilter = FileNameExtensionFilter("Images", "jpg", "jpeg", "png", "webp", "bmp")
                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                            val b64 = runCatching {
                                MediaIngest.downscaleToBase64(chooser.selectedFile.readBytes())
                            }.getOrNull()
                            if (b64 == null) avatarError = "Image illisible ou trop grande après réduction."
                            else { avatarB64 = b64; avatarError = null }
                        }
                    }) { Text("Choisir une photo…") }
                    if (avatarB64.isNotBlank()) {
                        TextButton(onClick = { avatarB64 = "" }) { Text("Retirer") }
                    }
                }
                avatarError?.let { Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.error) }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Switch(checked = avatarPublic, onCheckedChange = { avatarPublic = it })
                Text(
                    if (avatarPublic) "Public" else "Privé",
                    fontSize = 9.sp, color = MaterialTheme.colorScheme.outline
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        ProfileField("Nom affiché", displayName, displayNamePublic,
            { displayName = it }, { displayNamePublic = it }, 64)
        ProfileField("Bio (à propos de vous)", bio, bioPublic,
            { bio = it }, { bioPublic = it }, 280, multiline = true)
        ProfileField("Site web", website, websitePublic,
            { website = it }, { websitePublic = it }, 128)
        ProfileField("Localisation", location, locationPublic,
            { location = it }, { locationPublic = it }, 64)

        Spacer(Modifier.height(8.dp))
        Text(
            "Les champs privés ne quittent JAMAIS cet appareil. L'avatar publié est réduit à 256×256 avant diffusion.",
            fontSize = 10.sp, color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(14.dp))

        Button(
            onClick = {
                ServiceLocator.store.saveMyProfile(
                    DesktopStore.MyProfile(
                        displayName = displayName.trim(), bio = bio.trim(),
                        website = website.trim(), location = location.trim(),
                        avatarB64 = avatarB64,
                        displayNamePublic = displayNamePublic, bioPublic = bioPublic,
                        websitePublic = websitePublic, locationPublic = locationPublic,
                        avatarPublic = avatarPublic,
                    )
                )
                scope.launch {
                    ServiceLocator.repository.publishProfile().fold(
                        onSuccess = { message = "Profil enregistré et publié (champs publics)." },
                        onFailure = {
                            message = "Profil enregistré localement — publication dès qu'un réplica sera joignable."
                        }
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Enregistrer et publier") }
        message?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
        }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Retour") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ProfileField(
    label: String,
    value: String,
    isPublic: Boolean,
    onChange: (String) -> Unit,
    onPublicChange: (Boolean) -> Unit,
    maxChars: Int,
    multiline: Boolean = false,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Switch(checked = isPublic, onCheckedChange = onPublicChange)
                Text(
                    if (isPublic) "Public — diffusé avec votre adresse" else "Privé — reste sur cet appareil",
                    fontSize = 8.sp, color = MaterialTheme.colorScheme.outline
                )
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = { if (it.length <= maxChars) onChange(it) },
            modifier = Modifier.fillMaxWidth(),
            maxLines = if (multiline) 4 else 1,
            textStyle = TextStyle(fontSize = 13.sp)
        )
    }
}
