package com.stripe.example.viewmodel

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.stripe.example.MainActivity
import com.stripe.example.TerminalRepository
import com.stripe.example.model.Event
import com.stripe.example.model.PaymentIntentCreationResponse
import com.stripe.example.network.ApiClient
import com.stripe.stripeterminal.Terminal
import com.stripe.stripeterminal.ktx.retrievePaymentIntent
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
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
                val createdPI = createPaymentIntent(paymentParameters, createConfiguration)
                addEvent(Event("Created PaymentIntent", "terminal.createPaymentIntent"))
                // Track created PaymentIntents, for cancellation from the UI
                TerminalRepository.addPaymentIntent(createdPI)
                // Using a single step processPaymentIntent for collect and confirm,
                // Alternatively you could perform collect and confirm as separate steps
                // especially if you have business logic to perform between collect and confirm.
                val processedPI = processPaymentIntent(
                    intent = createdPI, collectConfig = collectConfiguration, confirmConfig = confirmConfiguration
                )
                addEvent(Event("Processed PaymentIntent", "terminal.processPaymentIntent"))
                TerminalRepository.addPaymentIntent(processedPI)
                
                // Auto-capture the payment intent on backend
                processedPI.id?.let { paymentIntentId ->
                    try {
                        ApiClient.capturePaymentIntent(paymentIntentId)
                        addEvent(Event("Captured PaymentIntent", "backend.capturePaymentIntent"))
                    } catch (e: Exception) {
                        addEvent(Event("Error capturing: ${e.message}", "backend.capturePaymentIntent"))
                    }
                }
            }
        }.also(jobs::add)
    }

    /**
     * Take payment using backend-created PaymentIntent with all deep link metadata.
     * This flow passes all metadata to Stripe.
     */
    private fun takePaymentWithBackend(
        amount: Long,
        currency: String,
        collectConfiguration: CollectPaymentIntentConfiguration,
        confirmConfiguration: ConfirmPaymentIntentConfiguration
    ) {
        statusMessage.postValue("Connecting to server...")
        addEvent(Event("Creating PaymentIntent on backend...", "backend.createPaymentIntent"))
        
        // Create PaymentIntent on backend with all metadata from deep link
        ApiClient.createPaymentIntent(
            amount = amount,
            currency = currency,
            customerId = MainActivity.deepLinkCustomerId,
            orderId = MainActivity.deepLinkOrderId,
            locationId = MainActivity.deepLinkLocationId,
            email = MainActivity.deepLinkEmail,
            id = MainActivity.deepLinkId,
            adminUserId = MainActivity.deepLinkAdminUserId,
            washType = MainActivity.deepLinkWashType,
            packageId = MainActivity.deepLinkPackageId,
            vehicleId = MainActivity.deepLinkVehicleId,
            callback = object : Callback<PaymentIntentCreationResponse> {
                override fun onResponse(
                    call: Call<PaymentIntentCreationResponse>,
                    response: Response<PaymentIntentCreationResponse>
                ) {
                    if (!response.isSuccessful) {
                        val errorBody = response.errorBody()?.string()
                        Log.e("EventViewModel", "Backend PI creation failed: ${response.code()} - $errorBody")
                        statusMessage.postValue("Connection failed. Please try again.")
                        addEvent(Event("Failed to create PaymentIntent: ${response.message()}", "backend.createPaymentIntent"))
                        isComplete.postValue(true)
                        return
                    }
                    
                    val secret = response.body()?.secret
                    if (secret.isNullOrEmpty()) {
                        Log.e("EventViewModel", "PaymentIntent secret is null or empty")
                        statusMessage.postValue("Invalid response from server. Please try again.")
                        addEvent(Event("Invalid payment response from server", "backend.createPaymentIntent"))
                        isComplete.postValue(true)
                        return
                    }
                    
                    statusMessage.postValue("Connected. Processing payment...")
                    addEvent(Event("PaymentIntent created on backend", "backend.createPaymentIntent"))
                    
                    // Retrieve and process the PaymentIntent
                    retrieveAndProcessPaymentIntent(secret, collectConfiguration, confirmConfiguration)
                }
                
                override fun onFailure(call: Call<PaymentIntentCreationResponse>, t: Throwable) {
                    Log.e("EventViewModel", "Network error creating PaymentIntent", t)
                    val userMessage = when (t) {
                        is java.net.UnknownHostException -> "No internet connection. Please check your network."
                        is java.net.SocketTimeoutException -> "Connection timeout. Please try again."
                        is java.io.IOException -> "Unable to connect to server. Please try again."
                        else -> "Connection error. Please try again."
                    }
                    statusMessage.postValue(userMessage)
                    val errorMessage = when (t) {
                        is java.net.UnknownHostException -> "No internet connection"
                        is java.net.SocketTimeoutException -> "Connection timeout"
                        is java.io.IOException -> "Network error"
                        else -> t.message ?: "Unknown error"
                    }
                    addEvent(Event("Network error: $errorMessage", "backend.createPaymentIntent"))
                    isComplete.postValue(true)
                }
            }
        )
    }
    
    private fun retrieveAndProcessPaymentIntent(
        clientSecret: String,
        collectConfiguration: CollectPaymentIntentConfiguration,
        confirmConfiguration: ConfirmPaymentIntentConfiguration
    ) {
        viewModelScopeSafeLaunch {
            Terminal.getInstance().run {
                // Retrieve the PaymentIntent using the client secret from backend
                statusMessage.postValue("Connected. Retrieving payment...")
                val retrievedPI = retrievePaymentIntent(clientSecret)
                addEvent(Event("Retrieved PaymentIntent", "terminal.retrievePaymentIntent"))
                TerminalRepository.addPaymentIntent(retrievedPI)
                
                // Process the payment (collect and confirm)
                statusMessage.postValue("Connected. Waiting for card...")
                val processedPI = processPaymentIntent(
                    intent = retrievedPI,
                    collectConfig = collectConfiguration,
                    confirmConfig = confirmConfiguration
                )
                addEvent(Event("Processed PaymentIntent", "terminal.processPaymentIntent"))
                TerminalRepository.addPaymentIntent(processedPI)
                
                // Auto-capture the payment intent on backend
                statusMessage.postValue("Connected. Finalizing payment...")
                processedPI.id?.let { paymentIntentId ->
                    try {
                        ApiClient.capturePaymentIntent(paymentIntentId)
                        addEvent(Event("Captured PaymentIntent", "backend.capturePaymentIntent"))
                        statusMessage.postValue("Payment successful!")
                    } catch (e: Exception) {
                        addEvent(Event("Error capturing: ${e.message}", "backend.capturePaymentIntent"))
                        statusMessage.postValue("Payment processed, but capture failed. Please check with support.")
                    }
                }
                
                // Clear deep link data after successful payment
                MainActivity.clearDeepLinkData()
            }
        }.also(jobs::add)
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
     * Safely launch a coroutine in the viewModelScope invoking [block] catching and posting [TerminalException]s as Events.
     * Also catches [CancellationException]s to ensure they are rethrown after logging the cancellation event.
     */
    private fun viewModelScopeSafeLaunch(block: suspend () -> Unit): Job {
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
            } catch (e: CancellationException) {
                statusMessage.postValue("Payment canceled.")
                addEvent(Event("Canceled", "viewModel.cancelIntents"))
                throw e
            } finally {
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
