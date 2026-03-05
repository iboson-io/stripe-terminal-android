package com.stripe.example

import com.google.firebase.database.FirebaseDatabase
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Real-time payment timing and outcome logger → Firebase Realtime Database.
 *
 * Every event is pushed immediately to Firebase so you can watch two scenarios live:
 *
 *  SCENARIO 1 — Fresh launch (reader not connected):
 *    APP_START → SESSION_START → bt_discovery → bt_connect → reader_connected
 *    → create_pi → retrieve_pi → ready_for_tap → collect_confirm → capture
 *    → payment_complete → PAYMENT_SUMMARY
 *
 *  SCENARIO 2 — Already connected:
 *    SESSION_START (reader_was_connected=true) → reader_connected → create_pi
 *    → retrieve_pi → ready_for_tap → collect_confirm → capture
 *    → payment_complete → PAYMENT_SUMMARY
 *
 * PAYMENT_SUMMARY in Firebase shows every step's duration and the total time
 * from both the deep link and from the initial app launch.
 *
 * View at: Firebase Console → Realtime Database → pos_logs
 * Local:   adb logcat -s PaymentTiming
 */
object PaymentLogger {

    private const val TAG = "PaymentTiming"
    private const val DB_PATH = "pos_logs"

    // ── Global (survives across sessions) ─────────────────────────────────────
    @Volatile private var appStartMs: Long = 0L   // set once in appStart()

    // ── Per-session state ──────────────────────────────────────────────────────
    @Volatile private var sessionId: String = ""
    @Volatile private var sessionStartMs: Long = 0L
    private val steps = mutableListOf<Step>()
    @Volatile private var outcome: String = "unknown"
    @Volatile private var failureReason: String = ""
    @Volatile private var paymentIntentId: String = ""
    @Volatile private var orderId: String = ""
    @Volatile private var publicOrderId: String = ""
    @Volatile private var amountCents: Long = 0L
    @Volatile private var currency: String = ""
    @Volatile private var readerWasConnected: Boolean = false
    @Volatile private var flushed: Boolean = false

    private val db by lazy {
        FirebaseDatabase.getInstance().getReference(DB_PATH)
    }

    data class Step(
        val name: String,
        val startMs: Long,
        var endMs: Long = 0L,
        var status: String = "pending",
        var detail: String = ""
    )

    val isSessionActive: Boolean get() = sessionId.isNotEmpty() && !flushed

    /** Snapshot of the current session ID — used by callers to detect session changes. */
    val currentSessionId: String get() = sessionId

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Fire APP_START on every app launch.
     * Captures the launch timestamp used later to calculate total time-to-payment.
     * Look for event="APP_START" in Firebase to confirm connectivity.
     */
    fun appStart() {
        appStartMs = System.currentTimeMillis()
        Timber.tag(TAG).d("=== APP_START ===")
        pushRaw(
            mapOf(
                "dt" to isoTimestamp(appStartMs),
                "level" to "info",
                "event" to "APP_START",
                "message" to "App launched"
            )
        )
    }

    /**
     * Begin a new payment session when a deep link is received.
     * [readerAlreadyConnected] = true means this is scenario 2 (no BT scan needed).
     */
    fun startSession(
        orderId: String = "",
        publicOrderId: String = "",
        amountCents: Long = 0,
        currency: String = "",
        readerAlreadyConnected: Boolean = false
    ) {
        synchronized(this) {
            sessionId = UUID.randomUUID().toString()
            sessionStartMs = System.currentTimeMillis()
            synchronized(steps) { steps.clear() }
            outcome = "unknown"
            failureReason = ""
            paymentIntentId = ""
            flushed = false
            this.orderId = orderId
            this.publicOrderId = publicOrderId
            this.amountCents = amountCents
            this.currency = currency
            this.readerWasConnected = readerAlreadyConnected
        }

        val launchAgo = if (appStartMs > 0) sessionStartMs - appStartMs else -1L
        Timber.tag(TAG).d(
            "=== SESSION START [$sessionId] order=$orderId amount=$amountCents $currency " +
            "reader_connected=$readerAlreadyConnected launch_ago=${launchAgo}ms ==="
        )

        // Sync to Crashlytics so every crash in this session is labelled with order/amount/step
        CrashContext.setPaymentSession(
            sessionId        = sessionId,
            orderId          = orderId,
            publicOrderId    = publicOrderId,
            amountCents      = amountCents,
            currency         = currency,
            readerAlreadyConnected = readerAlreadyConnected
        )

        sendEvent(
            level = "info",
            event = "SESSION_START",
            message = "Payment session started — order=$orderId amount=$amountCents $currency",
            extra = buildMap {
                put("reader_was_connected", readerAlreadyConnected)
                if (launchAgo >= 0) put("ms_since_app_launch", launchAgo)
            }
        )
    }

    /** Record a named instant event (no duration). */
    fun mark(name: String, detail: String = "") {
        if (!isSessionActive) return
        val now = System.currentTimeMillis()
        synchronized(steps) { steps.add(Step(name, now, now, "ok", detail)) }
        val offsetMs = now - sessionStartMs
        CrashContext.markPaymentStep(name, detail)
        Timber.tag(TAG).d("  [$name] +${offsetMs}ms${if (detail.isNotEmpty()) " | $detail" else ""}")
        sendEvent(
            level = "info",
            event = name,
            message = "$name at +${offsetMs}ms${if (detail.isNotEmpty()) " — $detail" else ""}",
            extra = buildMap {
                put("offset_ms", offsetMs)
                if (detail.isNotEmpty()) put("detail", detail)
            }
        )
    }

    /** Start timing a named step. Always pair with [endStep]. */
    fun startStep(name: String) {
        if (!isSessionActive) return
        val now = System.currentTimeMillis()
        synchronized(steps) { steps.add(Step(name, now)) }
        val offsetMs = now - sessionStartMs
        // Keep Crashlytics key updated so crash reports show which step was running
        CrashContext.setPaymentStep(name)
        Timber.tag(TAG).d("  [$name] START +${offsetMs}ms")
        sendEvent(
            level = "info",
            event = "${name}_start",
            message = "$name started at +${offsetMs}ms",
            extra = mapOf("offset_ms" to offsetMs, "step" to name)
        )
    }

    /** End a previously started step. Safe to call if step was never started. */
    fun endStep(name: String, success: Boolean, detail: String = "") {
        if (!isSessionActive) return
        val now = System.currentTimeMillis()
        val durationMs: Long
        synchronized(steps) {
            val step = steps.lastOrNull { it.name == name && it.endMs == 0L } ?: return
            step.endMs = now
            step.status = if (success) "ok" else "fail"
            step.detail = detail
            durationMs = step.endMs - step.startMs
        }
        val offsetMs = now - sessionStartMs
        Timber.tag(TAG).d(
            "  [$name] ${if (success) "✓" else "✗"} ${durationMs}ms" +
            "${if (detail.isNotEmpty()) " | $detail" else ""}"
        )
        sendEvent(
            level = if (success) "info" else "error",
            event = "${name}_end",
            message = "$name ${if (success) "done" else "FAILED"} in ${durationMs}ms" +
                      "${if (detail.isNotEmpty()) " — $detail" else ""}",
            extra = buildMap {
                put("duration_ms", durationMs)
                put("offset_ms", offsetMs)
                put("status", if (success) "ok" else "fail")
                put("step", name)
                if (detail.isNotEmpty()) put("detail", detail)
            }
        )
    }

    /** Close every still-open step with status=fail (call from exception handlers). */
    fun endAllOpenSteps(detail: String) {
        if (sessionId.isEmpty()) return
        val now = System.currentTimeMillis()
        synchronized(steps) {
            steps.filter { it.endMs == 0L }.forEach { step ->
                step.endMs = now
                step.status = "fail"
                step.detail = detail
                val durationMs = step.endMs - step.startMs
                Timber.tag(TAG).d("  [${step.name}] ✗ INTERRUPTED after ${durationMs}ms | $detail")
                sendEvent(
                    level = "error",
                    event = "${step.name}_end",
                    message = "${step.name} INTERRUPTED after ${durationMs}ms — $detail",
                    extra = mapOf(
                        "duration_ms" to durationMs,
                        "status" to "fail",
                        "step" to step.name,
                        "detail" to detail
                    )
                )
            }
        }
    }

    /** Record the final payment outcome. */
    fun setOutcome(success: Boolean, reason: String = "", piId: String = "") {
        if (sessionId.isEmpty()) return
        outcome = when {
            success -> "success"
            reason.contains("cancel", ignoreCase = true) -> "cancelled"
            else -> "failure"
        }
        if (reason.isNotEmpty()) failureReason = reason
        if (piId.isNotEmpty()) paymentIntentId = piId
        CrashContext.setPaymentOutcome(outcome, reason, piId)
        Timber.tag(TAG).d(
            "  [outcome] $outcome" +
            "${if (reason.isNotEmpty()) " | $reason" else ""}" +
            "${if (piId.isNotEmpty()) " | pi=$piId" else ""}"
        )
        sendEvent(
            level = if (success) "info" else "warn",
            event = "OUTCOME",
            message = "Payment $outcome${if (reason.isNotEmpty()) " — $reason" else ""}",
            extra = buildMap {
                put("outcome", outcome)
                if (reason.isNotEmpty()) put("failure_reason", reason)
                if (piId.isNotEmpty()) put("payment_intent_id", piId)
            }
        )
    }

    fun setPaymentIntentId(piId: String) {
        if (piId.isNotEmpty()) {
            paymentIntentId = piId
            Timber.tag(TAG).d("  [pi_id] $piId")
        }
    }

    // ── Detailed data logs ────────────────────────────────────────────────────

    /**
     * Log every parameter that arrived in the deep link.
     * Visible in Firebase as event="DEEP_LINK_PARAMS".
     * Phone number is masked to last 4 digits for privacy.
     */
    fun logDeepLinkParams(
        amountRaw: String,
        amountCents: Long,
        currency: String,
        id: String?,
        orderId: String?,
        publicOrderId: String?,
        customerId: String?,
        locationId: String?,
        email: String?,
        adminUserId: String?,
        washType: String?,
        packageId: String?,
        vehicleId: String?,
        source: String?,
        phone: String?
    ) {
        if (!isSessionActive) return
        Timber.tag(TAG).d("  [DEEP_LINK_PARAMS] amount=$amountRaw currency=$currency order=$orderId")
        sendEvent(
            level = "info",
            event = "DEEP_LINK_PARAMS",
            message = "Deep link received — amount=$amountRaw $currency order=$orderId",
            extra = buildMap {
                put("amount_raw", amountRaw)
                put("amount_cents", amountCents)
                put("currency", currency)
                if (!id.isNullOrEmpty())           put("id", id)
                if (!orderId.isNullOrEmpty())      put("order_id", orderId)
                if (!publicOrderId.isNullOrEmpty()) put("public_order_id", publicOrderId)
                if (!customerId.isNullOrEmpty())   put("customer_id", customerId)
                if (!locationId.isNullOrEmpty())   put("location_id", locationId)
                if (!email.isNullOrEmpty())        put("email", email)
                if (!adminUserId.isNullOrEmpty())  put("admin_user_id", adminUserId)
                if (!washType.isNullOrEmpty())     put("wash_type", washType)
                if (!packageId.isNullOrEmpty())    put("package_id", packageId)
                if (!vehicleId.isNullOrEmpty())    put("vehicle_id", vehicleId)
                if (!source.isNullOrEmpty())       put("source", source)
                if (!phone.isNullOrEmpty())        put("phone_masked", maskPhone(phone))
            }
        )
    }

    /**
     * Log the exact payload sent to the backend to create the PaymentIntent.
     * Visible in Firebase as event="create_pi_request".
     */
    fun logCreatePiRequest(
        amountCents: Long,
        currency: String,
        customerId: String?,
        orderId: String?,
        locationId: String?,
        email: String?,
        adminUserId: String?,
        washType: String?,
        packageId: String?,
        vehicleId: String?,
        source: String?,
        publicOrderId: String?
    ) {
        if (!isSessionActive) return
        Timber.tag(TAG).d("  [create_pi_request] amount=$amountCents $currency order=$orderId")
        sendEvent(
            level = "info",
            event = "create_pi_request",
            message = "Sending create PaymentIntent to backend — amount=$amountCents $currency",
            extra = buildMap {
                put("amount_cents", amountCents)
                put("currency", currency)
                put("payment_mode", "terminal")
                if (!customerId.isNullOrEmpty())   put("customer_id", customerId)
                if (!orderId.isNullOrEmpty())      put("order_id", orderId)
                if (!locationId.isNullOrEmpty())   put("location_id", locationId)
                if (!email.isNullOrEmpty())        put("email", email)
                if (!adminUserId.isNullOrEmpty())  put("admin_user_id", adminUserId)
                if (!washType.isNullOrEmpty())     put("wash_type", washType)
                if (!packageId.isNullOrEmpty())    put("package_id", packageId)
                if (!vehicleId.isNullOrEmpty())    put("vehicle_id", vehicleId)
                if (!source.isNullOrEmpty())       put("source", source)
                if (!publicOrderId.isNullOrEmpty()) put("public_order_id", publicOrderId)
            }
        )
    }

    /**
     * Log what the backend returned when creating the PaymentIntent.
     * Logs the PI ID (safe) — never logs the full client secret.
     * Visible in Firebase as event="create_pi_response".
     */
    fun logCreatePiResponse(intentId: String, clientSecretReceived: Boolean) {
        if (!isSessionActive) return
        Timber.tag(TAG).d("  [create_pi_response] pi_id=$intentId secret_received=$clientSecretReceived")
        sendEvent(
            level = "info",
            event = "create_pi_response",
            message = "Backend created PaymentIntent — pi_id=$intentId",
            extra = mapOf(
                "stripe_pi_id" to intentId,
                "client_secret_received" to clientSecretReceived
            )
        )
    }

    /**
     * Log the PaymentIntent data returned from Stripe after retrieval.
     * Visible in Firebase as event="retrieve_pi_response".
     */
    fun logRetrievedPi(piId: String, amountCents: Long, currency: String, status: String) {
        if (!isSessionActive) return
        Timber.tag(TAG).d("  [retrieve_pi_response] pi_id=$piId amount=$amountCents status=$status")
        sendEvent(
            level = "info",
            event = "retrieve_pi_response",
            message = "PaymentIntent retrieved from Stripe — pi_id=$piId status=$status",
            extra = mapOf(
                "stripe_pi_id" to piId,
                "amount_cents" to amountCents,
                "currency" to currency,
                "stripe_status" to status
            )
        )
    }

    /**
     * Log the PaymentIntent state after the customer taps/inserts their card.
     * Logs last 4 digits and card brand (safe — never logs full card number).
     * Visible in Firebase as event="process_pi_response".
     */
    fun logProcessedPi(
        piId: String,
        status: String,
        last4: String?,
        brand: String?,
        funding: String?
    ) {
        if (!isSessionActive) return
        val cardSummary = "${brand ?: "?"} ****${last4 ?: "????"}"
        Timber.tag(TAG).d("  [process_pi_response] pi_id=$piId status=$status card=$cardSummary")
        sendEvent(
            level = "info",
            event = "process_pi_response",
            message = "Card tapped — pi_id=$piId status=$status card=$cardSummary",
            extra = buildMap {
                put("stripe_pi_id", piId)
                put("stripe_status", status)
                last4?.let    { put("card_last4", it) }
                brand?.let    { put("card_brand", it) }
                funding?.let  { put("card_funding", it) }
            }
        )
    }

    /**
     * Log the capture request sent to the backend.
     * Visible in Firebase as event="capture_request".
     */
    fun logCaptureRequest(piId: String) {
        if (!isSessionActive) return
        Timber.tag(TAG).d("  [capture_request] pi_id=$piId")
        sendEvent(
            level = "info",
            event = "capture_request",
            message = "Sending capture request to backend — pi_id=$piId",
            extra = mapOf("stripe_pi_id" to piId)
        )
    }

    /**
     * Log the capture response received from the backend.
     * Visible in Firebase as event="capture_response".
     */
    fun logCaptureResponse(piId: String, success: Boolean, httpCode: Int = 0, detail: String = "") {
        if (!isSessionActive) return
        val msg = if (success) "Capture success — pi_id=$piId"
                  else         "Capture FAILED — pi_id=$piId $detail"
        Timber.tag(TAG).d("  [capture_response] $msg")
        sendEvent(
            level = if (success) "info" else "error",
            event = "capture_response",
            message = msg,
            extra = buildMap {
                put("stripe_pi_id", piId)
                put("success", success)
                if (httpCode > 0)       put("http_code", httpCode)
                if (detail.isNotEmpty()) put("detail", detail)
            }
        )
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun maskPhone(phone: String): String =
        if (phone.length > 4) "****${phone.takeLast(4)}" else "****"

    /**
     * Finalize the session — fires payment_complete then PAYMENT_SUMMARY.
     * Idempotent: only runs once per session. Call from every payment path's finally block.
     *
     * PAYMENT_SUMMARY in Firebase includes:
     *   - outcome, total_session_ms, time_from_app_launch_ms
     *   - reader_was_connected_at_start (true = scenario 2, false = scenario 1)
     *   - steps: map of each step → { duration_ms, status }
     */
    fun flush() {
        if (sessionId.isEmpty() || flushed) return
        synchronized(this) {
            if (flushed) return
            flushed = true
        }

        val completionMs = System.currentTimeMillis()
        val totalSessionMs = completionMs - sessionStartMs
        val totalFromLaunchMs = if (appStartMs > 0) completionMs - appStartMs else -1L

        CrashContext.clearPaymentSession(totalSessionMs)
        Timber.tag(TAG).d(
            "=== SESSION END [$sessionId] session=${totalSessionMs}ms " +
            "from_launch=${totalFromLaunchMs}ms outcome=$outcome ==="
        )

        // Build human-readable steps map for Firebase
        val stepsCopy = synchronized(steps) { steps.toList() }
        val stepsMap = mutableMapOf<String, Any>()
        stepsCopy.forEachIndexed { idx, step ->
            val duration = if (step.endMs > 0) step.endMs - step.startMs else 0L
            val key = "${(idx + 1).toString().padStart(2, '0')}_${step.name}"
            stepsMap[key] = buildMap {
                put("duration_ms", duration)
                put("status", step.status)
                put("offset_ms", step.startMs - sessionStartMs)
                if (step.detail.isNotEmpty()) put("detail", step.detail)
            }
        }

        val extra = buildMap<String, Any> {
            put("outcome", outcome)
            put("total_session_ms", totalSessionMs)
            if (totalFromLaunchMs >= 0) put("time_from_app_launch_ms", totalFromLaunchMs)
            put("reader_was_connected_at_start", readerWasConnected)
            if (failureReason.isNotEmpty()) put("failure_reason", failureReason)
            if (paymentIntentId.isNotEmpty()) put("payment_intent_id", paymentIntentId)
            put("steps", stepsMap)
        }

        // Push PAYMENT_SUMMARY — this is the final record for the session
        val entry = mutableMapOf<String, Any>(
            "dt" to isoTimestamp(completionMs),
            "level" to if (outcome == "success") "info" else "error",
            "event" to "PAYMENT_SUMMARY",
            "message" to buildString {
                append("Payment $outcome in ${totalSessionMs}ms (session)")
                if (totalFromLaunchMs >= 0) append(", ${totalFromLaunchMs}ms (from launch)")
                if (failureReason.isNotEmpty()) append(" — FAIL: $failureReason")
            },
            "session_id" to sessionId,
            "order_id" to orderId,
            "amount_cents" to amountCents,
            "currency" to currency
        )
        if (publicOrderId.isNotEmpty()) entry["public_order_id"] = publicOrderId
        entry.putAll(extra)
        pushRaw(entry)
    }

    // ── Firebase delivery ──────────────────────────────────────────────────────

    private fun sendEvent(
        level: String,
        event: String,
        message: String,
        extra: Map<String, Any> = emptyMap()
    ) {
        if (sessionId.isEmpty()) return
        val entry = mutableMapOf<String, Any>(
            "dt" to isoTimestamp(System.currentTimeMillis()),
            "level" to level,
            "message" to message,
            "event" to event,
            "session_id" to sessionId,
            "order_id" to orderId,
            "amount_cents" to amountCents,
            "currency" to currency
        )
        if (publicOrderId.isNotEmpty()) entry["public_order_id"] = publicOrderId
        entry.putAll(extra)
        pushRaw(entry)
    }

    private fun pushRaw(entry: Map<String, Any>) {
        try {
            db.push().setValue(entry)
                .addOnFailureListener { e ->
                    Timber.tag(TAG).w("Firebase write failed: ${e.message}")
                }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Firebase push error")
        }
    }

    private fun isoTimestamp(ms: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(ms))
    }
}
