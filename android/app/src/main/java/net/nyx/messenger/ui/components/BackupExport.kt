package net.nyx.messenger.ui.components

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.nyx.messenger.di.ServiceLocator

/**
 * v0.2.21 — écrit la sauvegarde v2 du compte (dépôt —
 * [net.nyx.messenger.repo.NyxRepository.exportBackupJson] : clé + alias +
 * profil + contacts + curseurs inbox) dans l'uri SAF choisi.
 *
 * UN SEUL point d'écriture, partagé par l'onboarding (carte clé de
 * récupération) et Paramètres (sauvegarde de l'identité) — l'audit RED-DUP
 * interdit deux copies divergentes de la même mécanique.
 */
suspend fun writeBackupToUri(context: Context, uri: Uri): Result<Unit> =
    withContext(Dispatchers.IO) {
        runCatching {
            val json = ServiceLocator.repository.exportBackupJson()
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(json.toByteArray(Charsets.UTF_8))
            } ?: error("flux d'écriture indisponible")
        }
    }
