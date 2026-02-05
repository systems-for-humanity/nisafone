package app.s4h.fomovoi.android

import android.app.Application
import app.s4h.fomovoi.app.di.androidModule
import app.s4h.fomovoi.app.di.commonModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class FomovoiApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidLogger(Level.DEBUG)
            androidContext(this@FomovoiApplication)
            modules(commonModule, androidModule)
        }
    }
}
