package net.nyx.messenger.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.nyx.messenger.desktop.DesktopLog
import net.nyx.messenger.desktop.ServiceLocator
import net.nyx.messenger.security.DesktopVault

/**
 * v0.3.4 « parité Android » — ÉCRAN DE VERROU (miroir LockScreen Android
 * v0.2.18) : couche OPAQUE par-dessus l'app quand le verrou est armé.
 *
 *  - mot de passe NORMAL → déverrouille (ou sort de la session leurre) ;
 *  - mot de passe PANIC → session LEURRE (bascule + retour onboarding — les
 *    données réelles restent intactes au disque) ;
 *  - mot de passe DESTRUCTION → TOUT est effacé sur-le-champ (sans
 *    confirmation — c'est le sens même de ce mot de passe), l'app se referme.
 *
 * Et PASSE-PORTAIL (miroir PasswordGate Android) : création FORCÉE des mots
 * de passe NORMAL puis DESTRUCTION quand l'identité existe sans coffre —
 * libellés Android exacts, sans échappatoire (comme l'Android).
 */
@Composable
fun LockScreen(onDestroyed: () -> Unit) {
    var pwd by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(Modifier.padding(horizontal = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) { Text("☾", color = MaterialTheme.colorScheme.onPrimary, fontSize = 18.sp) }
            Spacer(Modifier.height(14.dp))
            Text("Nyx garde vos conversations", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Saisissez votre mot de passe pour continuer.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            PasswordField(value = pwd, onValueChange = { pwd = it }, label = "Mot de passe")
            ErrorLine(error)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    when (DesktopVault.verify(pwd.toCharArray())) {
                        DesktopVault.Mode.NORMAL -> {
                            // Session leurre active ? retour à la session RÉELLE.
                            if (ServiceLocator.sessionMode == ServiceLocator.SessionMode.PANIC) {
                                ServiceLocator.switchSessionMode(DesktopVault.Mode.NORMAL)
                            }
                            ServiceLocator.unlock()
                        }
                        DesktopVault.Mode.PANIC -> {
                            DesktopLog.w("nyx-vault", "mot de passe PANIC → session leurre")
                            ServiceLocator.switchSessionMode(DesktopVault.Mode.PANIC)
                            ServiceLocator.unlock()
                        }
                        DesktopVault.Mode.DESTRUCTION -> {
                            DesktopLog.w("nyx-vault", "mot de passe DESTRUCTION → effacement total")
                            ServiceLocator.destructionWipe()
                            onDestroyed()
                        }
                        null -> error = "Mot de passe inconnu."
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Valider") }
        }
    }
}

/** Passe-portail (miroir PasswordGate Android — libellés exacts, 2 étapes, sans échappatoire). */
@Composable
fun PasswordGate(onDone: () -> Unit) {
    var step by remember { mutableStateOf(0) }   // 0 = NORMAL, 1 = DESTRUCTION
    var pwd1 by remember { mutableStateOf("") }
    var pwd2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(Modifier.padding(horizontal = 48.dp)) {
            Text(
                if (step == 0) "Protégez votre compte" else "Mot de passe « destruction »",
                fontSize = 18.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (step == 0)
                    "Le mot de passe protège désormais LE COMPTE : les sauvegardes sont chiffrées avec lui et son importation l'exige. Créez-le (6 caractères minimum)."
                else
                    "Si vous saisissez CE mot de passe lors du déverrouillage, tout est EFFACÉ sur-le-champ (protection en cas de contrainte). Créez-le (différent du mot de passe normal).",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            PasswordField(value = pwd1, onValueChange = { pwd1 = it }, label = "Mot de passe")
            Spacer(Modifier.height(6.dp))
            PasswordField(value = pwd2, onValueChange = { pwd2 = it }, label = "Confirmer le mot de passe")
            ErrorLine(error)
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                if (pwd1.length < 6) { error = "6 caractères minimum"; return@Button }
                if (pwd1 != pwd2) { error = "Les deux mots de passe diffèrent"; return@Button }
                val mode = if (step == 0) DesktopVault.Mode.NORMAL else DesktopVault.Mode.DESTRUCTION
                try {
                    DesktopVault.set(mode, pwd1.toCharArray())
                    if (step == 0) {
                        step = 1; pwd1 = ""; pwd2 = ""; error = null
                    } else {
                        onDone()
                    }
                } catch (e: Exception) {
                    error = e.message
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Valider") }
        }
    }
}
