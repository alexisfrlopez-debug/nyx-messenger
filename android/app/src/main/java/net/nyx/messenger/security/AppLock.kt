package net.nyx.messenger.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * État du verrou d'accès de l'application (biométrie opt-in — ADR-010 §3).
 *
 * Principes :
 *  - `locked` initial = vrai à froid si l'opt-in est actif (aucun contenu avant vérif) ;
 *  - chaque passage en arrière-plan ([onBackgrounded]) re-verrouille ;
 *  - EXCEPTION (v0.2.1) : un flux système à résultat (photo picker, feuille de
 *    partage…) met l'activité en pause SANS être un vrai passage en arrière-plan
 *    — [suspendNextBackground] consomme le PROCHAIN [onBackgrounded] pour que
 *    choisir une photo ne verrouille pas l'application ;
 *  - le déverrouillage est effectué par l'OS (BiometricPrompt) — ici nous ne
 *    gérons que l'ÉTAT : l'application ne voit jamais de donnée biométrique ;
 *  - l'alias « sessionActive » permet d'activer le verrou depuis l'onboarding
 *    sans re-demander immédiatement la vérification (l'utilisateur vient de
 *    confirmer sa biométrie pour activer l'option).
 */
object AppLock {

    private val _locked = MutableStateFlow(true)

    /** Drapeau one-shot : le prochain onBackgrounded est attendu (flux système). */
    private var backgroundSuspension = false

    /** Vrai tant que l'utilisateur n'a pas prouvé sa présence via l'OS. */
    val locked: StateFlow<Boolean> = _locked

    /** Opt-in actif (règle le comportement des re-verrouillages en arrière-plan). */
    var enabled: Boolean = false
        private set

    /** Initialise l'état au démarrage de l'activité (à froid : verrouillé si opt-in). */
    fun init(enabledNow: Boolean) {
        enabled = enabledNow
        backgroundSuspension = false
        _locked.value = enabledNow
    }

    /**
     * Annonce un passage en arrière-plan ATTENDU (lancement d'un flux système à
     * résultat : photo picker, feuille de partage, etc.). Le prochain
     * [onBackgrounded] NE verrouille PAS — le drapeau est consommé une seule
     * fois, tout passage en arrière-plan suivant re-verrouille normalement.
     */
    fun suspendNextBackground() {
        backgroundSuspension = true
    }

    /** Passage en arrière-plan — re-verrouillage sauf suspension attendue. */
    fun onBackgrounded() {
        if (!enabled) return
        if (backgroundSuspension) {
            backgroundSuspension = false
            return
        }
        _locked.value = true
    }

    /** Déverrouillage (après succès de la vérification OS). */
    fun unlock() {
        _locked.value = false
    }

    /** Activation de l'opt-in (le device vient de confirmer la biométrie). */
    fun enable(sessionActive: Boolean) {
        enabled = true
        backgroundSuspension = false
        _locked.value = !sessionActive
    }

    /** Désactivation de l'opt-in (depuis l'onboarding). */
    fun disable() {
        enabled = false
        backgroundSuspension = false
        _locked.value = false
    }
}
