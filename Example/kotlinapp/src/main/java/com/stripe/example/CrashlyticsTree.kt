package com.stripe.example

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import timber.log.Timber

/**
 * Timber tree that forwards log events to Firebase Crashlytics.
 *
 * ┌─────────────┬──────────────────────────────────────────────────────────────┐
 * │ Priority    │ What happens in Crashlytics                                  │
 * ├─────────────┼──────────────────────────────────────────────────────────────┤
 * │ VERBOSE/    │ Ignored — too noisy for crash reports                        │
 * │ DEBUG       │                                                              │
 * ├─────────────┼──────────────────────────────────────────────────────────────┤
 * │ INFO        │ Added as a breadcrumb log line. Visible in the "Logs" tab    │
 * │             │ of every crash/non-fatal report to show the event timeline.  │
 * ├─────────────┼──────────────────────────────────────────────────────────────┤
 * │ WARN        │ Breadcrumb log + if a Throwable is attached it is recorded   │
 * │             │ as a non-fatal issue.                                        │
 * ├─────────────┼──────────────────────────────────────────────────────────────┤
 * │ ERROR /     │ Breadcrumb log + Throwable (or a synthetic one when none is  │
 * │ ASSERT      │ attached) is always recorded as a non-fatal issue so you     │
 * │             │ see it in the "Non-fatals" section of the dashboard.         │
 * └─────────────┴──────────────────────────────────────────────────────────────┘
 *
 * Because PaymentLogger uses Timber for all its log calls, every payment event
 * (SESSION_START, bt_discovery, create_pi, OUTCOME, etc.) automatically appears
 * as a breadcrumb trail in every crash/non-fatal report.
 */
class CrashlyticsTree : Timber.Tree() {

    private val crashlytics = FirebaseCrashlytics.getInstance()

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        when (priority) {
            Log.VERBOSE, Log.DEBUG -> return   // skip noise from SDK internals

            Log.INFO -> {
                // Breadcrumb only — payment milestones, step starts/ends
                crashlytics.log(formatLine("I", tag, message))
            }

            Log.WARN -> {
                crashlytics.log(formatLine("W", tag, message))
                t?.let { crashlytics.recordException(it) }
            }

            Log.ERROR, Log.ASSERT -> {
                crashlytics.log(formatLine("E", tag, message))
                // Always report an exception — create a synthetic one if none was passed
                // so the full stack trace and custom keys are captured
                val exception = t ?: RuntimeException("$tag: $message")
                crashlytics.recordException(exception)
            }
        }
    }

    private fun formatLine(level: String, tag: String?, message: String): String {
        return if (tag != null) "$level/$tag: $message" else "$level: $message"
    }
}
