package com.stripe.example

import android.app.Application
import android.os.StrictMode
import com.stripe.stripeterminal.TerminalApplicationDelegate
import timber.log.Timber

class StripeTerminalApplication : Application() {
    override fun onCreate() {
        // Should happen before super.onCreate()
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectAll()
                .penaltyLog()
                .build()
        )

        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .penaltyLog()
                .build()
        )

        super.onCreate()

        // Local logcat output (debug builds only)
        Timber.plant(Timber.DebugTree())

        // Crashlytics: forwards WARN/ERROR Timber calls as non-fatals + breadcrumb trail
        // View reports at: Firebase Console → Crashlytics
        Timber.plant(CrashlyticsTree())

        // Set static app-level keys so every crash report shows the build config
        CrashContext.setAppContext(useSimulatedReader = BuildConfig.USE_SIMULATED_READER)

        TerminalApplicationDelegate.onCreate(this)

        // Fires APP_START to Firebase Realtime Database on every launch.
        // Open Firebase Console → Realtime Database → pos_logs to confirm it arrives.
        PaymentLogger.appStart()
    }
}
