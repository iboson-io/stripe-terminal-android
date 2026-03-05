package com.stripe.example.viewmodel

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.stripe.example.CrashContext
import com.stripe.example.MainActivity
import com.stripe.example.PaymentLogger
import com.stripe.example.TerminalRepository
import timber.log.Timber
import com.stripe.example.model.Event
import com.stripe.example.network.ApiClient
import com.stripe.example.network.TapToPayStatusApi
import com.stripe.stripeterminal.Terminal
import com.stripe.stripeterminal.ktx.retrievePaymentIntent
import com.stripe.stripeterminal.external.models.AllowRedisplay
import com.stripe.stripeterminal.external.models.CollectPaymentIntentConfiguration
import com.stripe.stripeterminal.external.models.CollectRefundConfiguration
import com.stripe.stripeterminal.external.models.CollectSetupIntentConfiguration
import com.stripe.stripeterminal.external.models.ConfirmPaymentIntentConfiguration
import com.stripe.stripeterminal.external.models.CreateConfiguration
import com.stripe.stripeterminal.external.models.PaymentIntent
import com.stripe.stripeterminal.external.models.PaymentIntentParameters
import com.stripe.stripeterminal.external.models.RefundParameters
import com.stripe.stripeterminal.external.models.SetupIntent
import com.stripe.stripeterminal.external.models.SetupIntentCancellationParameters
import com.stripe.stripeterminal.external.models.SetupIntentParameters
import com.stripe.stripeterminal.external.models.TerminalException
import com.stripe.stripeterminal.external.models.ConnectionStatus
import com.stripe.stripeterminal.ktx.cancelPaymentIntent
import com.stripe.stripeterminal.ktx.cancelSetupIntent
import com.stripe.stripeterminal.ktx.createPaymentIntent
import com.stripe.stripeterminal.ktx.createSetupIntent
import com.stripe.stripeterminal.ktx.processPaymentIntent
import com.stripe.stripeterminal.ktx.processRefund
import com.stripe.stripeterminal.ktx.processSetupIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

class EventViewModel(eventsList: List<Event> = mutableListOf()) : ViewModel() {
    private val eventList: ArrayList<Event> = ArrayList(eventsList)
    var events: MutableLiveData<List<Event>> = MutableLiveData(eventList)
    var isComplete: MutableLiveData<Boolean> = MutableLiveData(false)
    var displayAmount: MutableLiveData<String> = MutableLiveData("")
    var displayCurrency: MutableLiveData<String> = MutableLiveData("")
    var statusMessage: MutableLiveData<String> = MutableLiveData("")
    private val jobs = mutableListOf<Job>()

    fun addEvent(event: Event) {
        eventList.add(event)
        events.postValue(eventList)
    }

    fun cancel() {
        viewModelScope.launch {
            addEvent(Event("Cancel invoked", "viewModel.cancel"))
            jobs.filter { it.isActive }
                .forEach { it.cancel(CancellationException("Cancel invoked")) }
            jobs.joinAll()
            jobs.clear()
        }
    }

    fun takePayment(
        paymentParameters: PaymentIntentParameters,
        createConfiguration: CreateConfiguration?,
        collectConfiguration: CollectPaymentIntentConfiguration,
        confirmConfiguration: ConfirmPaymentIntentConfiguration
    ) {
        // Enable payment only when terminal is connected
        if (Terminal.getInstance().connectionStatus != ConnectionStatus.CONNECTED) {
            statusMessage.postValue("Please wait for the reader to connect.")
            addEvent(Event("Reader not connected", "viewModel.takePayment"))
            PaymentLogger.setOutcome(false, "reader not connected at takePayment")
            PaymentLogger.mark("payment_complete", "aborted — reader not connected")
            PaymentLogger.flush()
            isComplete.postValue(true)
            return
        }

        // Check if deep link data exists - use backend flow for M2 readers with metadata
        val deepLinkAmount = MainActivity.deepLinkAmount
        if (deepLinkAmount != null) {
            takePaymentWithBackend(
                amount = deepLinkAmount,
                currency = MainActivity.deepLinkCurrency.lowercase(),
                collectConfiguration = collectConfiguration,
                confirmConfiguration = confirmConfiguration
            )
            return
        }
        
        // Standard SDK flow for local mobile/NFC readers
        viewModelScopeSafeLaunch {
            Terminal.getInstance().run {
                PaymentLogger.startStep("create_pi")
                val createdPI = createPaymentIntent(paymentParameters, createConfiguration)
                PaymentLogger.endStep("create_pi", true)
                addEvent(Event("Created PaymentIntent", "terminal.createPaymentIntent"))
                // Track created PaymentIntents, for cancellation from the UI
                TerminalRepository.addPaymentIntent(createdPI)
                // Using a single step processPaymentIntent for collect and confirm,
                // Alternatively you could perform collect and confirm as separate steps
                // especially if you have business logic to perform between collect and confirm.
                PaymentLogger.startStep("collect_confirm")
                val processedPI = processPaymentIntent(
                    intent = createdPI, collectConfig = collectConfiguration, confirmConfig = confirmConfiguration
                )
                PaymentLogger.setPaymentIntentId(processedPI.id ?: "")
                PaymentLogger.endStep("collect_confirm", true)
                addEvent(Event("Processed PaymentIntent", "terminal.processPaymentIntent"))
                TerminalRepository.addPaymentIntent(processedPI)

                // Auto-capture the payment intent on backend
                PaymentLogger.startStep("capture")
                processedPI.id?.let { paymentIntentId ->
                    try {
                        ApiClient.capturePaymentIntent(paymentIntentId)
                        PaymentLogger.endStep("capture", true)
                        PaymentLogger.setOutcome(true, "", paymentIntentId)
                        addEvent(Event("Captured PaymentIntent", "backend.capturePaymentIntent"))
                    } catch (e: Exception) {
                        PaymentLogger.endStep("capture", false, e.message ?: "")
                        addEvent(Event("Error capturing: ${e.message}", "backend.capturePaymentIntent"))
                    }
                }
            }
        }.also(jobs::add)
    }

    /**
     * Take payment using a backend-created PaymentIntent with all deep link metadata.
     *
     * The entire flow — backend PI creation, Stripe retrieve, card collect/confirm, capture —
     * runs inside a SINGLE [viewModelScopeSafeLaunch] coroutine. This means:
     *
     *  1. The Retrofit call for PI creation is wrapped in suspendCancellableCoroutine so it is
     *     properly cancelled when the ViewModel is cleared (e.g. because a new deep link arrived
     *     and replaced this Fragment). The old callback-based approach kept the HTTP call alive
     *     even after the Fragment was gone, allowing a stale response to launch new coroutines.
     *
     *  2. A session-ID guard before the capture step prevents the stale coroutine from capturing
     *     a PaymentIntent that belongs to a previous order when the session has already moved on.
     *     This was the root cause of the wrong-order metadata appearing on Stripe.
     *
     *  3. clearDeepLinkData() is NOT called from here — the Activity manages its own deep link
     *     state via handleDeepLink(); calling it from the ViewModel caused Order B's metadata
     *     to be wiped before it could be used.
     */
    private fun takePaymentWithBackend(
        amount: Long,
        currency: String,
        collectConfiguration: CollectPaymentIntentConfiguration,
        confirmConfiguration: ConfirmPaymentIntentConfiguration
    ) {
        if (Terminal.getInstance().connectionStatus != ConnectionStatus.CONNECTED) {
            statusMessage.postValue("Please wait for the reader to connect.")
            addEvent(Event("Reader not connected", "backend.takePaymentWithBackend"))
            PaymentLogger.setOutcome(false, "reader not connected at takePaymentWithBackend")
            PaymentLogger.mark("payment_complete", "aborted — reader not connected")
            PaymentLogger.flush()
            isComplete.postValue(true)
            return
        }

        // Snapshot the session ID so we can detect if a new deep link starts a new session
        // while this coroutine is suspended waiting for a card tap.
        val mySessionId = PaymentLogger.currentSessionId

        viewModelScopeSafeLaunch {
            // ── Step 1: Create PaymentIntent on backend (now a cancellable suspend call) ──
            TapToPayStatusApi.send(
                MainActivity.deepLinkId,
                MainActivity.deepLinkToken,
                TapToPayStatusApi.Status.APP_STARTED
            )
            statusMessage.postValue("Connecting to server...")
            addEvent(Event("Creating PaymentIntent on backend...", "backend.createPaymentIntent"))
            PaymentLogger.startStep("create_pi")
            PaymentLogger.logCreatePiRequest(
                amountCents   = amount,
                currency      = currency,
                customerId    = MainActivity.deepLinkCustomerId,
                orderId       = MainActivity.deepLinkId ?: MainActivity.deepLinkOrderId,
                locationId    = MainActivity.deepLinkLocationId,
                email         = MainActivity.deepLinkEmail,
                adminUserId   = MainActivity.deepLinkAdminUserId,
                washType      = MainActivity.deepLinkWashType,
                packageId     = MainActivity.deepLinkPackageId,
                vehicleId     = MainActivity.deepLinkVehicleId,
                source        = MainActivity.deepLinkSource,
                publicOrderId = MainActivity.deepLinkPublicOrderId
            )

            // Uses suspendCancellableCoroutine — automatically cancelled if ViewModel is cleared
            val response = ApiClient.createPaymentIntentSuspend(
                amount          = amount,
                currency        = currency,
                customerId      = MainActivity.deepLinkCustomerId,
                orderId         = MainActivity.deepLinkOrderId,
                locationId      = MainActivity.deepLinkLocationId,
                email           = MainActivity.deepLinkEmail,
                id              = MainActivity.deepLinkId,
                adminUserId     = MainActivity.deepLinkAdminUserId,
                washType        = MainActivity.deepLinkWashType,
                packageId       = MainActivity.deepLinkPackageId,
                vehicleId       = MainActivity.deepLinkVehicleId,
                source          = MainActivity.deepLinkSource,
                publicOrderId   = MainActivity.deepLinkPublicOrderId,
                stripeAccountId = MainActivity.deepLinkStripeAccountId
            )

            val secret = response.secret
            if (secret.isNullOrEmpty()) {
                Log.e("EventViewModel", "PaymentIntent secret is null or empty")
                PaymentLogger.endStep("create_pi", false, "empty client secret from backend")
                PaymentLogger.setOutcome(false, "empty client secret from backend")
                statusMessage.postValue("Invalid response from server. Please try again.")
                addEvent(Event("Invalid payment response from server", "backend.createPaymentIntent"))
                return@viewModelScopeSafeLaunch
            }

            PaymentLogger.endStep("create_pi", true)
            PaymentLogger.logCreatePiResponse(
                intentId             = response.intent,
                clientSecretReceived = true
            )
            statusMessage.postValue("Connected. Processing payment...")
            addEvent(Event("PaymentIntent created on backend", "backend.createPaymentIntent"))

            Terminal.getInstance().run {
                // ── Step 2: Retrieve PaymentIntent from Stripe ───────────────────────────
                statusMessage.postValue("Connected. Retrieving payment...")
                PaymentLogger.startStep("retrieve_pi")
                val retrievedPI = retrievePaymentIntent(secret)
                PaymentLogger.endStep("retrieve_pi", true)
                PaymentLogger.logRetrievedPi(
                    piId        = retrievedPI.id ?: "",
                    amountCents = retrievedPI.amount,
                    currency    = retrievedPI.currency ?: "",
                    status      = retrievedPI.status?.name ?: "unknown"
                )
                addEvent(Event("Retrieved PaymentIntent", "terminal.retrievePaymentIntent"))
                TerminalRepository.addPaymentIntent(retrievedPI)

                // ── Step 3: Collect card and confirm payment ──────────────────────────────
                TapToPayStatusApi.send(
                    MainActivity.deepLinkId,
                    MainActivity.deepLinkToken,
                    TapToPayStatusApi.Status.PAYMENT_WAITING
                )
                statusMessage.postValue("Connected. Waiting for card...")
                PaymentLogger.startStep("collect_confirm")
                val processedPI = processPaymentIntent(
                    intent        = retrievedPI,
                    collectConfig = collectConfiguration,
                    confirmConfig = confirmConfiguration
                )
                PaymentLogger.setPaymentIntentId(processedPI.id ?: "")
                PaymentLogger.endStep("collect_confirm", true)
                val card = processedPI.paymentMethod?.cardPresentDetails
                PaymentLogger.logProcessedPi(
                    piId    = processedPI.id ?: "",
                    status  = processedPI.status?.name ?: "unknown",
                    last4   = card?.last4,
                    brand   = card?.brand,
                    funding = null
                )
                addEvent(Event("Processed PaymentIntent", "terminal.processPaymentIntent"))
                TerminalRepository.addPaymentIntent(processedPI)

                // ── Step 4: Capture ───────────────────────────────────────────────────────
                // CRITICAL GUARD: If a new deep link arrived while we were suspended waiting
                // for the card tap, the PaymentLogger session has changed. The PI we just
                // collected belongs to the PREVIOUS order — capturing it here would charge the
                // wrong order and record the wrong metadata on Stripe. Abort instead.
                if (PaymentLogger.currentSessionId != mySessionId) {
                    addEvent(Event(
                        "Session superseded by new payment — capture aborted to prevent wrong-order charge",
                        "backend.capturePaymentIntent"
                    ))
                    PaymentLogger.setOutcome(false, "session_superseded")
                    return@viewModelScopeSafeLaunch
                }

                statusMessage.postValue("Connected. Finalizing payment...")
                PaymentLogger.startStep("capture")
                processedPI.id?.let { paymentIntentId ->
                    try {
                        PaymentLogger.logCaptureRequest(paymentIntentId)
                        ApiClient.capturePaymentIntent(paymentIntentId)
                        PaymentLogger.logCaptureResponse(paymentIntentId, success = true)
                        PaymentLogger.endStep("capture", true)
                        PaymentLogger.setOutcome(true, "", paymentIntentId)
                        TapToPayStatusApi.send(
                            MainActivity.deepLinkId,
                            MainActivity.deepLinkToken,
                            TapToPayStatusApi.Status.PAYMENT_SUCCESS
                        )
                        addEvent(Event("Captured PaymentIntent", "backend.capturePaymentIntent"))
                        statusMessage.postValue("Payment successful!")
                    } catch (e: Exception) {
                        PaymentLogger.logCaptureResponse(paymentIntentId, success = false, detail = e.message ?: "exception")
                        PaymentLogger.endStep("capture", false, e.message ?: "")
                        TapToPayStatusApi.send(
                            MainActivity.deepLinkId,
                            MainActivity.deepLinkToken,
                            TapToPayStatusApi.Status.PAYMENT_FAILED
                        )
                        addEvent(Event("Error capturing: ${e.message}", "backend.capturePaymentIntent"))
                        statusMessage.postValue("Payment processed, but capture failed. Please check with support.")
                    }
                }
            }
        }
    }

    fun saveCard(
        setupIntentParameters: SetupIntentParameters,
        allowRedisplay: AllowRedisplay,
        collectConfiguration: CollectSetupIntentConfiguration,
    ) {
        viewModelScopeSafeLaunch {
            Terminal.getInstance().run {
                val createdSI = createSetupIntent(setupIntentParameters)
                addEvent(Event("Created SetupIntent", "terminal.createSetupIntent"))
                // Keep track of created SetupIntents, for cancellation from the UI
                TerminalRepository.addSetupIntent(createdSI)
                // Using a single step process for collecting and confirming the setup intent.
                // Alternatively you could call collect and confirm separately if your integration has business logic
                // to perform between collect and confirm.
                val processedSI = processSetupIntent(
                    intent = createdSI,
                    allowRedisplay = allowRedisplay,
                    collectConfig = collectConfiguration,
                )
                TerminalRepository.addSetupIntent(processedSI)
                addEvent(Event("Processed SetupIntent", "terminal.processSetupIntent"))
            }
        }
    }

    /**
     * Safely launch a coroutine in the viewModelScope invoking [block].
     *
     * Catches TerminalException, CancellationException, and generic Exception.
     *
     * The finally block contains a session-ID guard: if a new deep link arrived while this
     * coroutine was running (starting a new PaymentLogger session), we skip the flush so we
     * don't corrupt the new session's PAYMENT_SUMMARY with stale data from this old coroutine.
     */
    private fun viewModelScopeSafeLaunch(block: suspend () -> Unit): Job {
        // Snapshot the active session at launch time so the finally block can detect a change.
        val launchSessionId = PaymentLogger.currentSessionId
        return viewModelScope.launch(Dispatchers.Default) {
            try {
                block()
            } catch (e: TerminalException) {
                val userMessage = when {
                    e.errorMessage?.contains("network", ignoreCase = true) == true -> "Network error. Please try again."
                    e.errorMessage?.contains("timeout", ignoreCase = true) == true -> "Connection timeout. Please try again."
                    e.errorMessage?.contains("cancel", ignoreCase = true) == true -> "Payment canceled."
                    else -> "Payment failed. Please try again."
                }
                statusMessage.postValue(userMessage)
                addEvent(Event("${e.errorCode}", e.errorMessage))
                val reason = "${e.errorCode}: ${e.errorMessage ?: ""}"
                Timber.e(e, "TerminalException during payment: $reason")
                CrashContext.recordNonFatal(e, "payment step=${reason}")
                PaymentLogger.endAllOpenSteps(reason)
                PaymentLogger.setOutcome(false, reason)
                TapToPayStatusApi.send(
                    MainActivity.deepLinkId,
                    MainActivity.deepLinkToken,
                    TapToPayStatusApi.Status.PAYMENT_FAILED
                )
            } catch (e: CancellationException) {
                val isUserCancelled = e.message == "Cancel invoked"
                val reason = if (isUserCancelled) "cancelled by user" else "interrupted: ${e.message ?: "scope cancelled"}"
                statusMessage.postValue(if (isUserCancelled) "Payment canceled." else "Payment interrupted.")
                addEvent(Event(if (isUserCancelled) "Canceled" else "Interrupted", "viewModel.cancelIntents"))
                PaymentLogger.endAllOpenSteps(reason)
                PaymentLogger.setOutcome(false, reason)
                throw e
            } catch (e: Exception) {
                // Handles network errors from the suspend Retrofit call and any other unexpected errors
                val userMessage = when (e) {
                    is java.net.UnknownHostException -> "No internet connection. Please check your network."
                    is java.net.SocketTimeoutException -> "Connection timeout. Please try again."
                    is java.io.IOException -> "Unable to connect to server. Please try again."
                    else -> "Payment failed. Please try again."
                }
                statusMessage.postValue(userMessage)
                addEvent(Event("Error: ${e.message}", "viewModel.exception"))
                val reason = e.message ?: "Unknown error"
                Timber.e(e, "Exception during payment: $reason")
                CrashContext.recordNonFatal(e, "payment exception: $reason")
                PaymentLogger.endAllOpenSteps(reason)
                PaymentLogger.setOutcome(false, reason)
                TapToPayStatusApi.send(
                    MainActivity.deepLinkId,
                    MainActivity.deepLinkToken,
                    TapToPayStatusApi.Status.PAYMENT_FAILED
                )
            } finally {
                // Only flush our own session. If the session ID changed it means a new deep link
                // arrived and started a new session while this coroutine was still in-flight.
                // Writing payment_complete + flush in that case would corrupt the new session.
                if (PaymentLogger.currentSessionId == launchSessionId) {
                    PaymentLogger.mark("payment_complete")
                    PaymentLogger.flush()
                }
                isComplete.postValue(true)
            }
        }.also(jobs::add)
    }

    private fun cancelPaymentIntent(paymentIntent: PaymentIntent) {
        viewModelScopeSafeLaunch {
            val cancelledPI = Terminal.getInstance().cancelPaymentIntent(paymentIntent)
            addEvent(Event("Cancelled PaymentIntent", "terminal.cancelPaymentIntent"))
            TerminalRepository.addPaymentIntent(cancelledPI)
        }.also(jobs::add)
    }

    private fun refundPayment(paymentIntent: PaymentIntent) {
        viewModelScopeSafeLaunch {
            val refundParameters = RefundParameters.ByPaymentIntentId(
                id = paymentIntent.id!!,
                clientSecret = paymentIntent.clientSecret!!,
                amount = paymentIntent.amount,
                currency = paymentIntent.currency!!
            ).setMetadata(TerminalRepository.genMetaData()).build()
            val refund = Terminal.getInstance().processRefund(
                parameters = refundParameters, collectConfig = CollectRefundConfiguration.Builder().build()
            )
            addEvent(Event("Processed Refund", "terminal.processRefund"))
            TerminalRepository.addRefund(refund)
        }
    }

    private fun cancelSetupIntent(setupIntent: SetupIntent) {
        viewModelScopeSafeLaunch {
            val cancelledSI = Terminal.getInstance().cancelSetupIntent(
                params = SetupIntentCancellationParameters.NULL, setupIntent = setupIntent
            )
            addEvent(Event("Cancelled SetupIntent", "terminal.cancelSetupIntent"))
            TerminalRepository.addSetupIntent(cancelledSI)
        }
    }

    fun cancelTransaction(transactionId: String?) {
        if (transactionId == null) {
            addEvent(Event("No transactionId provided to cancel", "viewModel.cancelTransaction"))
            isComplete.postValue(true)
            return
        }
        // Look for a created PaymentIntent with the matching transactionId
        TerminalRepository.getPaymentIntentByTransactionId(transactionId)?.let { pi ->
            cancelPaymentIntent(pi)
            return
        }
        // Look for a created SetupIntent with the matching transactionId
        TerminalRepository.getSetupIntentByTransactionId(transactionId)?.let { si ->
            cancelSetupIntent(si)
            return
        }
        addEvent(Event("No matching PaymentIntent or SetupIntent found to cancel", "viewModel.cancelTransaction"))
        isComplete.postValue(true)
    }

    fun refundTransaction(transactionId: String?) {
        if (transactionId == null) {
            addEvent(Event("No transactionId provided to refund", "viewModel.refundTransaction"))
            isComplete.postValue(true)
            return
        }
        // Look for a created PaymentIntent with the matching transactionId
        TerminalRepository.getPaymentIntentByTransactionId(transactionId)?.let { pi ->
            refundPayment(pi)
            return
        }
        addEvent(Event("No matching PaymentIntent found to refund", "viewModel.refundTransaction"))
        isComplete.postValue(true)
    }

    override fun onCleared() {
        super.onCleared()
        jobs.clear()
    }
}
