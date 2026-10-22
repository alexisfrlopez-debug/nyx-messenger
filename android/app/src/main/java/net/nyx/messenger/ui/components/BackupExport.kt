package net.nyx.messenger.ui.components

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.nyx.messenger.crypto.PasswordCrypto
import net.nyx.messenger.di.ServiceLocator

/**
 * v0.2.21 — écrit la sauvegarde v2 du compte (dépôt —
 * [net.nyx.messenger.repo.NyxRepository.exportBackupJson] : clé + alias +
 * profil + contacts + curseurs inbox) dans l'uri SAF choisi.
 *
 * UN SEUL point d'écriture, partagé par l'onboarding (carte clé de
 * récupération) et Paramètres (sauvegarde de l'identité) — l'audit RED-DUP
 * interdit deux copies divergentes de la même mécanique.
 *
 * v0.2.26 — FICHIER CHIFFRÉ PAR MOT DE PASSE (demande opérateur : « le mot de
 * passe protège l'application mais pas le compte ») : le JSON n'est plus
 * écrit en clair — enveloppe [PasswordCrypto] scellée avec le mot de passe
 * NORMAL du compte (vérifié AVANT l'écriture). L'importation exige le mot de
 * passe (RestoreDialog) ; la saisie du mot de passe DESTRUCTION efface le
 * fichier importé. Rétrocompatibilité : l'import accepte encore les
 * sauvegardes v1/v2 en clair des versions antérieures (après vérification du
 * mot de passe du compte quand il existe).
 */
suspend fun writeBackupToUri(context: Context, uri: Uri, password: String): Result<Unit> =
    withContext(Dispatchers.IO) {
        runCatching {
            require(password.isNotBlank()) { "mot de passe requis" }
            val json = ServiceLocator.repository.exportBackupJson()
            val envelope = PasswordCrypto.encrypt(json.toByteArray(Charsets.UTF_8), password.toCharArray())
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(envelope.toByteArray(Charsets.UTF_8))
            } ?: error("flux d'écriture indisponible")
        }
    }
