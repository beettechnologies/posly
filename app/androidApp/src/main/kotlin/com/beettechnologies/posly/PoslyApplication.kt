package com.beettechnologies.posly

import android.app.Application
import com.beettechnologies.posly.auth.authModule
import com.beettechnologies.posly.auth.platformAuthModule
import com.beettechnologies.posly.auth.sharedUiModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class PoslyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@PoslyApplication)
            modules(authModule, platformAuthModule(), sharedUiModule)
        }
    }
}
