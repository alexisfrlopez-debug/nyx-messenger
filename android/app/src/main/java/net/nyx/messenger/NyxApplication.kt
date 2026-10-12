package net.nyx.messenger

import android.app.Application
import net.nyx.messenger.di.ServiceLocator

/**
 * Application Nyx — initialise le localisateur de services (DI manuelle volontaire :
 * le projet reste petit et auditable ; passage à Hilt si la surface l'exige).
 */
class NyxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
