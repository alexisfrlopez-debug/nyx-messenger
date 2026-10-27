package net.nyx.messenger.desktop

import net.nyx.messenger.crypto.AliasValidator
import net.nyx.messenger.crypto.IdentityBackup
import net.nyx.messenger.crypto.RecoveryKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.3.2 — Sauvegarde/restauration de compte desktop : MIROIR de
 * NyxRepository.exportBackupJson + doRestore Android (v0.2.21/v0.2.26),
 * extraits dans un objet SANS état pour être testables isolément
 * (identité + magasin injectés — tests BackupRestoreTest).
 *
 * EXPORT : JSON v2 « nyx-identity-backup » — clé de récupération + alias +
 * carte de profil propre (publics ET privés) + contacts + curseurs inbox ;
 * l'appelant scelle ce JSON dans une enveloppe [net.nyx.messenger.crypto.PasswordCrypto]
 * (mot de passe choisi à l'export) → fichier importable par le TÉLÉPHONE
 * (miroir exact de writeBackupToUri Android) comme par un autre PC.
 *
 * RESTAURATION (sémantique Android inchangée) :
 *  - identité : re-dérivation depuis la graine (même onion, checksum clé) ;
 *  - alias : normalisé, ré-claim différé (idempotent) ;
 *  - profil : fusion directe (publics + privés — locaux à l'appareil) ;
 *  - contacts : JAMAIS régresser l'état local — absent → insertion telle
 *    quelle (flags compris) ; présent + non archivé + sauvegarde
 *    « accepted » → mise à niveau (répare l'invitation « incoming » recréée
 *    quand la boîte réplica relivre les anciens événements — le cas exact
 *    du retour terrain Android v0.2.21) ; archivé local → INTACT (action
 *    délibérée post-export) ;
 *  - curseurs inbox : insertion SEULEMENT si absent (un curseur local est
 *    par définition plus récent — ne jamais reculer).
 */
object BackupRestore {

    /** Statut Android (chaîne) → statut desktop ; inconnu/vide → PENDING. */
    private fun mapStatus(s: String): DesktopStore.ContactStatus = when (s.trim().lowercase()) {
        "accepted" -> DesktopStore.ContactStatus.ACCEPTED
        "incoming" -> DesktopStore.ContactStatus.INCOMING
        "declined" -> DesktopStore.ContactStatus.DECLINED
        "removed", "removed_by_me" -> DesktopStore.ContactStatus.REMOVED
        else -> DesktopStore.ContactStatus.PENDING
    }

    private fun archived(c: DesktopStore.Contact): Boolean =
        c.status == DesktopStore.ContactStatus.DECLINED || c.status == DesktopStore.ContactStatus.REMOVED

    /**
     * Génère le JSON de sauvegarde v2 du compte local (clé + alias + profil
     * + contacts + curseurs). Miroir de NyxRepository.exportBackupJson —
     * UN SEUL point de génération partagé onboarding/Paramètres (RED-DUP).
     */
    fun exportBackupJson(identity: DesktopIdentity, store: DesktopStore): String {
        val me = identity.onionAddress ?: error("identité absente")
        val priv = identity.secretKey()
        val key = RecoveryKey.encode(priv.copyOfRange(0, 32))

        // Contacts — instantané brut (tous, y compris archivés : la sauvegarde
        // doit restituer l'état tel quel).
        val contactsArr = JSONArray()
        for (c in store.contacts()) {
            contactsArr.put(
                JSONObject()
                    .put("onion", c.onion)
                    .put("alias", c.alias)
                    .put("status", c.status.name.lowercase())
                    .put("addedAt", c.addedAt)
                    .put("notifiedAt", c.notifiedAt)
                    .put("notifyPending", c.notifyPending)
                    .put("ackPending", 0) // non suivi côté desktop (miroir ; le téléphone re-fuse par ses boucles)
            )
        }

        // Curseurs inbox par réplica (le poll reprend où l'export l'a laissé).
        val cursorsObj = JSONObject()
        for ((replica, cursor) in store.cursors()) {
            cursorsObj.put(replica, cursor)
        }

        // Carte de profil PROPRE (publics + privés — format ProfileBody §7.2 :
        // display_name/bio publics comme la carte diffusée par publishProfile,
        // website/location/avatar privés par défaut ADR-010).
        val p = store.myProfile()
        val fields = JSONObject()
        fun field(key: String, value: String, public: Boolean) {
            if (value.isNotBlank()) fields.put(key, JSONObject().put("value", value).put("public", public))
        }
        field("display_name", p.displayName, true)
        field("bio", p.bio, true)
        field("website", p.website, false)
        field("location", p.location, false)
        field("avatar", p.avatarB64, false)
        val profileJson = JSONObject().put("fields", fields).toString()

        return IdentityBackup.toJson(me, key, identity.alias, profileJson,
            contactsArr.toString(), cursorsObj.toString())
    }

    /**
     * Applique une sauvegarde reconnue ([IdentityBackup.extract]) sur
     * l'identité + le magasin locaux : retourne l'adresse onion restaurée.
     * Lève une exception explicite si la clé est invalide (checksum).
     */
    fun applyRestore(
        identity: DesktopIdentity,
        store: DesktopStore,
        data: IdentityBackup.RestoreData,
        identityPassword: CharArray? = null,
    ): String {
        val onion = identity.restoreFromSeed(data.recoveryKey, identityPassword)

        // Alias — normalisé, silencieusement ignoré si invalide (best-effort :
        // une sauvegarde v1 restaure quand même l'identité, miroir Android).
        data.alias?.let { a ->
            runCatching {
                val n = AliasValidator.normalize(a)
                if (AliasValidator.isValid(n)) identity.setAlias(n)
            }
        }

        // Profil propre (publics + privés) — fusion directe dans MyProfile ;
        // la re-publication est manuelle sur desktop (Paramètres), miroir de
        // la carte Android qui se re-publie par la boucle 30 s.
        data.profileJson?.let { pj ->
            runCatching {
                val body = JSONObject(pj).optJSONObject("fields") ?: return@let
                val cur = store.myProfile()
                store.saveMyProfile(
                    cur.copy(
                        displayName = body.optJSONObject("display_name")?.optString("value")?.takeIf { it.isNotBlank() } ?: cur.displayName,
                        bio = body.optJSONObject("bio")?.optString("value")?.takeIf { it.isNotBlank() } ?: cur.bio,
                        website = body.optJSONObject("website")?.optString("value")?.takeIf { it.isNotBlank() } ?: cur.website,
                        location = body.optJSONObject("location")?.optString("value")?.takeIf { it.isNotBlank() } ?: cur.location,
                        avatarB64 = body.optJSONObject("avatar")?.optString("value")?.takeIf { it.isNotBlank() } ?: cur.avatarB64,
                    )
                )
            }
        }

        // Contacts — re-fusion (jamais régresser) : voir l'en-tête.
        if (data.contacts.isNotEmpty()) {
            var inserted = 0
            var upgraded = 0
            for (c in data.contacts) {
                val peer = c.onion.trim().lowercase()
                if (peer == onion || !peer.endsWith(".onion")) continue
                val local = store.contact(peer)
                when {
                    local == null -> {
                        store.upsertContact(
                            DesktopStore.Contact(
                                onion = peer,
                                alias = c.alias,
                                addedAt = c.addedAt,
                                status = mapStatus(c.status),
                                notifiedAt = c.notifiedAt,
                                notifyPending = c.notifyPending,
                            )
                        )
                        inserted++
                    }
                    !archived(local) &&
                        c.status.trim().lowercase() == "accepted" &&
                        local.status != DesktopStore.ContactStatus.ACCEPTED -> {
                        store.updateContact(peer) { it.status = DesktopStore.ContactStatus.ACCEPTED }
                        upgraded++
                    }
                    else -> {}
                }
            }
            DesktopLog.i("nyx-restore", "contacts de la sauvegarde : $inserted inséré(s), $upgraded mis à niveau")
        }

        // Curseurs inbox — insertion seule (jamais reculer).
        if (data.cursors.isNotEmpty()) {
            var applied = 0
            for ((replica, cursor) in data.cursors) {
                if (cursor <= 0) continue
                if (!store.hasCursor(replica)) {
                    store.setCursor(replica, cursor)
                    applied++
                }
            }
            if (applied > 0) DesktopLog.i("nyx-restore", "curseurs inbox restaurés : $applied")
        }

        DesktopLog.i(
            "nyx-restore",
            "restauration : alias=${data.alias != null}, profil=${data.profileJson != null}, " +
                "contacts=${data.contacts.size}, curseurs=${data.cursors.size}"
        )
        return onion
    }
}
