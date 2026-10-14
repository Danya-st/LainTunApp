package com.v2rayez.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Было: Firebase telemetry, Tor restore, WorkManager (Configuration.Provider), GeoAutoConfigurator,
 * PackInstallCoordinator, LocaleHelper, crash-логгер. Всё удалено — остался только вход Hilt.
 */
@HiltAndroidApp
class V2RayApplication : Application()
