package com.stripe.example

import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Keeps Firebase Crashlytics custom keys in sync with the live state of the app.
 *
 * Every crash report in the Firebase Console → Crashlytics → "Keys" tab will show:
 *
 *   payment_step          → the exact step running when the crash happened
 *                           (bt_discovery / bt_connect / create_pi / retrieve_pi /
 *                            collect_confirm / capture / idle)
 *   payment_order_id      → the order being processed
 *   payment_amount_cents  → amount in cents (e.g. 2500 = $25.00)
 *   payment_currency      → e.g. "USD"
 *   payment_session_id    → UUID of the active PaymentLogger session
 *   payment_outcome       → in_progress / success / failure / cancelled / complete
 *   reader_serial         → last 6 chars of the M2 reader serial number
 *   reader_device_type    → e.g. "STRIPE_M2"
 *   app_use_simulated     → true when running against simulated reader (staging)
 *
 * This means even if the crash happens deep inside the Stripe SDK or a coroutine,
 * you can see immediately which payment step caused it.
 */
object CrashContext {

    private val crashlytics: FirebaseCrashlytics
        get() = FirebaseCrashlytics.getInstance()

    // ── Called once at app launch ──────────────────────────────────────────────

    fun setAppContext(useSimulatedReader: Boolean) {
        crashlytics.setCustomKey("app_use_simulated", useSimulatedReader)
        crashlytics.setCustomKey("payment_step", "idle")
        crashlytics.setCustomKey("payment_outcome", "idle")
        crashlytics.setCustomKey("payment_order_id", "")
        crashlytics.setCustomKey("payment_session_id", "")
        crashlytics.setCustomKey("reader_serial", "unknown")
        crashlytics.setCustomKey("reader_device_type", "unknown")
    }

    // ── Called whenever a payment session starts ───────────────────────────────

    fun setPaymentSession(
        sessionId: String,
        orderId: String,
        publicOrderId: String,
        amountCents: Long,
        currency: String,
        readerAlreadyConnected: Boolean
    ) {
        crashlytics.setCustomKey("payment_session_id", sessionId)
        crashlytics.setCustomKey("payment_order_id", orderId)
        crashlytics.setCustomKey("payment_public_order_id", publicOrderId)
        crashlytics.setCustomKey("payment_amount_cents", amountCents)
        crashlytics.setCustomKey("payment_currency", currency)
        crashlytics.setCustomKey("payment_reader_was_connected", readerAlreadyConnected)
        crashlytics.setCustomKey("payment_step", "session_start")
        crashlytics.setCustomKey("payment_outcome", "in_progress")
        // Log to breadcrumb trail
        crashlytics.log("SESSION_START: order=$orderId amount=$amountCents $currency reader_connected=$readerAlreadyConnected")
    }

    // ── Called as the payment moves through each step ─────────────────────────

    /**
     * Update the current step so the crash report shows exactly which step was
     * running when the crash occurred (e.g. "bt_connect", "create_pi", "capture").
     */
    fun setPaymentStep(step: String, detail: String = "") {
        crashlytics.setCustomKey("payment_step", step)
        val log = if (detail.isEmpty()) "STEP: $step" else "STEP: $step — $detail"
        crashlytics.log(log)
    }

    fun markPaymentStep(name: String, detail: String = "") {
        val log = if (detail.isEmpty()) "MARK: $name" else "MARK: $name — $detail"
        crashlytics.log(log)
    }

    // ── Called when the outcome is known ──────────────────────────────────────

    fun setPaymentOutcome(outcome: String, reason: String = "", piId: String = "") {
        crashlytics.setCustomKey("payment_outcome", outcome)
        val log = buildString {
            append("OUTCOME: $outcome")
            if (reason.isNotEmpty()) append(" — $reason")
            if (piId.isNotEmpty()) append(" [pi=$piId]")
        }
        crashlytics.log(log)
    }

    // ── Called when the session is finalised ──────────────────────────────────

    fun clearPaymentSession(totalMs: Long) {
        crashlytics.setCustomKey("payment_step", "idle")
        crashlytics.setCustomKey("payment_outcome", "complete")
        crashlytics.log("SESSION_END: total_ms=$totalMs")
    }

    // ── Called when the reader connects ───────────────────────────────────────

    fun setReaderInfo(serialNumber: String?, deviceType: String?) {
        val serial = serialNumber?.takeLast(8) ?: "unknown"
        val type   = deviceType ?: "unknown"
        crashlytics.setCustomKey("reader_serial", serial)
        crashlytics.setCustomKey("reader_device_type", type)
        crashlytics.log("READER_CONNECTED: type=$type serial=…$serial")
    }

    // ── Called when a non-crash exception should be surfaced in Crashlytics ───

    /**
     * Records a caught exception as a non-fatal issue.
     * It will appear under "Non-fatals" in the Crashlytics dashboard with the
     * breadcrumb trail showing exactly what the payment was doing at the time.
     */
    fun recordNonFatal(throwable: Throwable, context: String = "") {
        if (context.isNotEmpty()) crashlytics.log("NON_FATAL context: $context")
        crashlytics.recordException(throwable)
    }
}
