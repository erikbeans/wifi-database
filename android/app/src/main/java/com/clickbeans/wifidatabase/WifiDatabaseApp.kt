package com.clickbeans.wifidatabase

import android.app.Application
import com.clickbeans.wifidatabase.data.crash.CrashReporter
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class WifiDatabaseApp : Application() {

    @Inject
    lateinit var crashReporter: CrashReporter

    override fun onCreate() {
        super.onCreate()
        crashReporter.install()
    }
}
