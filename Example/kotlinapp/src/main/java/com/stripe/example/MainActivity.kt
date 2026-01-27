package com.stripe.example

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.stripe.example.fragment.ConnectedReaderFragment
import com.stripe.example.fragment.PaymentFragment
import com.stripe.example.fragment.TerminalFragment
import com.stripe.example.fragment.UpdateReaderFragment
import com.stripe.example.fragment.admin.LedgerFragment
import com.stripe.example.fragment.discovery.DiscoveryFragment
import com.stripe.example.fragment.discovery.DiscoveryMethod
import com.stripe.example.fragment.event.EventFragment
import com.stripe.example.fragment.offline.OfflinePaymentsLogFragment
import com.stripe.example.model.OfflineBehaviorSelection
import com.stripe.example.network.TokenProvider
import com.stripe.stripeterminal.Terminal
import com.stripe.stripeterminal.external.OfflineMode
import com.stripe.stripeterminal.external.callable.Cancelable
import com.stripe.stripeterminal.external.callable.MobileReaderListener
import com.stripe.stripeterminal.external.callable.TapToPayReaderListener
import com.stripe.stripeterminal.external.models.ConnectionStatus
import com.stripe.stripeterminal.external.models.DisconnectReason
import com.stripe.stripeterminal.external.models.Location
import com.stripe.stripeterminal.external.models.Reader
import com.stripe.stripeterminal.external.models.ReaderDisplayMessage
import com.stripe.stripeterminal.external.models.ReaderInputOptions
import com.stripe.stripeterminal.external.models.ReaderSoftwareUpdate
import com.stripe.stripeterminal.external.models.TerminalException
import com.stripe.stripeterminal.log.LogLevel

class MainActivity :
    AppCompatActivity(),
    NavigationListener,
    MobileReaderListener,
    TapToPayReaderListener {

    companion object {
        private const val TAG = "MainActivity"
        
        // Deep link data
        var deepLinkAmount: Long? = null
        var deepLinkAmountDisplay: String? = null
        var deepLinkCurrency: String = "USD"
        var deepLinkCustomerId: String? = null
        var deepLinkOrderId: String? = null
        var deepLinkLocationId: String? = null
        var deepLinkEmail: String? = null
        var deepLinkId: String? = null
        var deepLinkAdminUserId: String? = null
        var deepLinkWashType: String? = null
        var deepLinkPackageId: String? = null
        var deepLinkVehicleId: String? = null
        
        fun clearDeepLinkData() {
            deepLinkAmount = null
            deepLinkAmountDisplay = null
            deepLinkCurrency = "USD"
            deepLinkCustomerId = null
            deepLinkOrderId = null
            deepLinkLocationId = null
            deepLinkEmail = null
            deepLinkId = null
            deepLinkAdminUserId = null
            deepLinkWashType = null
            deepLinkPackageId = null
            deepLinkVehicleId = null
        }
    }

    /**
     * Upon starting, we should verify we have the permissions we need, then start the app
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        // Parse deep link first
        handleDeepLink(intent)

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            BluetoothAdapter.getDefaultAdapter()?.let { adapter ->
                if (!adapter.isEnabled) {
                    adapter.enable()
                }
            }
        } else {
            Log.w(TAG, "Failed to acquire Bluetooth permission")
        }

        initialize()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
        
        // If deep link was received, refresh the current fragment
        // The payment flow will use the deep link data
        if (deepLinkAmount != null) {
            Log.d(TAG, "Deep link received, amount: $deepLinkAmountDisplay, currency: $deepLinkCurrency")
            // If reader is already connected, navigate to payment screen
            if (Terminal.getInstance().connectionStatus == ConnectionStatus.CONNECTED) {
                navigateTo(PaymentFragment.TAG, PaymentFragment())
            }
        }
    }

    private fun handleDeepLink(intent: Intent?) {
        try {
            val data: Uri = intent?.data ?: return
            Log.d(TAG, "Deep link: $data")

            val amountParam = data.getQueryParameter("amount")
            if (amountParam.isNullOrEmpty()) {
                Log.e(TAG, "Deep link missing required 'amount' parameter")
                return
            }
            
            val currencyParam = data.getQueryParameter("currency") ?: "USD"

            // Extract metadata parameters
            val customerId = data.getQueryParameter("customerId")
            val orderId = data.getQueryParameter("orderId") ?: data.getQueryParameter("order_id")
            val locationId = data.getQueryParameter("locationId")
            val email = data.getQueryParameter("email")
            val id = data.getQueryParameter("id")
            val adminUserId = data.getQueryParameter("admin_user_id")
            val washType = data.getQueryParameter("wash_type")
            val packageId = data.getQueryParameter("package_id")
            val vehicleId = data.getQueryParameter("vehicle_id")

            try {
                val amountDecimal = amountParam.toDouble()
                if (amountDecimal <= 0) {
                    Log.e(TAG, "Invalid amount: must be greater than 0")
                    return
                }
                val amountInCents = (amountDecimal * 100).toLong()
                
                deepLinkAmount = amountInCents
                deepLinkAmountDisplay = amountParam
                deepLinkCurrency = currencyParam.uppercase()
                deepLinkCustomerId = customerId
                deepLinkOrderId = orderId
                deepLinkLocationId = locationId
                deepLinkEmail = email
                deepLinkId = id
                deepLinkAdminUserId = adminUserId
                deepLinkWashType = washType
                deepLinkPackageId = packageId
                deepLinkVehicleId = vehicleId

                Log.d(TAG, "Amount: $amountParam ($amountInCents cents), Currency: $deepLinkCurrency")
                Log.d(TAG, "CustomerId: $customerId, OrderId: $orderId, LocationId: $locationId, Email: $email, Id: $id")
                Log.d(TAG, "AdminUserId: $adminUserId, WashType: $washType, PackageId: $packageId, VehicleId: $vehicleId")
            } catch (e: NumberFormatException) {
                Log.e(TAG, "Invalid amount format: $amountParam", e)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing amount: $amountParam", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling deep link", e)
        }
    }

    // Navigation callbacks

    /**
     * Callback function called when discovery has been canceled by the [DiscoveryFragment]
     */
    override fun onCancelDiscovery() {
        navigateTo(TerminalFragment.TAG, TerminalFragment())
    }

    // Location selection removed - M2 readers should already have location assigned
    override fun onRequestLocationSelection() {
        // No-op: Location selection not needed for M2 readers
    }

    override fun onCancelLocationSelection() {
        // No-op: Location selection not needed for M2 readers
    }

    override fun onRequestCreateLocation() {
        // No-op: Location creation not needed for M2 readers
    }

    override fun onCancelCreateLocation() {
        // No-op: Location creation not needed for M2 readers
    }

    override fun onLocationCreated() {
        // No-op: Location creation not needed for M2 readers
    }

    /**
     * Callback function called once discovery has been selected by the [TerminalFragment]
     */
    override fun onRequestDiscovery(isSimulated: Boolean, discoveryMethod: DiscoveryMethod) {
        navigateTo(DiscoveryFragment.TAG, DiscoveryFragment.newInstance(isSimulated, discoveryMethod))
    }

    /**
     * Callback function called to exit the payment workflow
     */
    override fun onRequestExitWorkflow() {
        if (Terminal.getInstance().connectionStatus == ConnectionStatus.CONNECTED) {
            navigateTo(ConnectedReaderFragment.TAG, ConnectedReaderFragment())
        } else {
            navigateTo(TerminalFragment.TAG, TerminalFragment())
        }
    }

    /**
     * Callback function called to start a payment by the [PaymentFragment]
     */
    override fun onRequestPayment(
        amount: Long,
        currency: String,
        skipTipping: Boolean,
        extendedAuth: Boolean,
        incrementalAuth: Boolean,
        offlineBehaviorSelection: OfflineBehaviorSelection,
    ) {
        navigateTo(
                EventFragment.TAG,
            EventFragment.requestPayment(
                amount,
                currency,
                skipTipping,
                extendedAuth,
                incrementalAuth,
                offlineBehaviorSelection
            )
        )
    }

    override fun onRequestCancel(transactionId: String) {
        navigateTo(EventFragment.TAG, EventFragment.cancelTransaction(transactionId))
    }

    override fun onRequestRefundPayment(transactionId: String) {
        navigateTo(EventFragment.TAG, EventFragment.refundPayment(transactionId))
    }

    /**
     * Callback function called once the payment workflow has been selected by the
     * [ConnectedReaderFragment]
     */
    override fun onSelectPaymentWorkflow() {
        navigateTo(PaymentFragment.TAG, PaymentFragment())
    }

    /**
     * Callback function called once the read card workflow has been selected by the
     * [ConnectedReaderFragment]
     */
    override fun onRequestSaveCard() {
        navigateTo(EventFragment.TAG, EventFragment.collectSetupIntentPaymentMethod())
    }

    /**
     * Callback function called once the update reader workflow has been selected by the
     * [ConnectedReaderFragment]
     */
    override fun onSelectUpdateWorkflow() {
        navigateTo(UpdateReaderFragment.TAG, UpdateReaderFragment())
    }

    /**
     * Callback function called once the view offline logs has been selected by the
     * [ConnectedReaderFragment]
     */
    override fun onSelectViewOfflineLogs() {
        navigateTo(OfflinePaymentsLogFragment.TAG, OfflinePaymentsLogFragment())
    }

    override fun onSelectViewLedger() {
        navigateTo(LedgerFragment.TAG, LedgerFragment())
    }

    // Terminal event callbacks

    /**
     * Callback function called when collect payment method has been canceled
     */
    override fun onCancelCollectPaymentMethod() {
        navigateTo(ConnectedReaderFragment.TAG, ConnectedReaderFragment())
    }

    /**
     * Callback function called when collect setup intent has been canceled
     */
    override fun onCancelCollectSetupIntent() {
        navigateTo(ConnectedReaderFragment.TAG, ConnectedReaderFragment())
    }

    /**
     * Callback function called on completion of [Terminal.connectReader]
     */
    override fun onConnectReader() {
        navigateTo(ConnectedReaderFragment.TAG, ConnectedReaderFragment())
        
        // If deep link exists, auto-navigate to payment screen
        if (deepLinkAmount != null) {
            navigateTo(PaymentFragment.TAG, PaymentFragment())
        }
    }

    override fun onDisconnectReader() {
        navigateTo(TerminalFragment.TAG, TerminalFragment())
    }

    override fun onStartInstallingUpdate(update: ReaderSoftwareUpdate, cancelable: Cancelable?) {
        runOnUiThread {
            // Delegate out to the current fragment, if it acts as a MobileReaderListener
            supportFragmentManager.fragments.last()?.let {
                if (it is MobileReaderListener) {
                    it.onStartInstallingUpdate(update, cancelable)
                }
            }
        }
    }

    override fun onReportReaderSoftwareUpdateProgress(progress: Float) {
        runOnUiThread {
            // Delegate out to the current fragment, if it acts as a MobileReaderListener
            supportFragmentManager.fragments.last()?.let {
                if (it is MobileReaderListener) {
                    it.onReportReaderSoftwareUpdateProgress(progress)
                }
            }
        }
    }

    override fun onFinishInstallingUpdate(update: ReaderSoftwareUpdate?, e: TerminalException?) {
        runOnUiThread {
            // Delegate out to the current fragment, if it acts as a MobileReaderListener
            supportFragmentManager.fragments.last()?.let {
                if (it is MobileReaderListener) {
                    it.onFinishInstallingUpdate(update, e)
                }
            }
        }
    }

    override fun onRequestReaderInput(options: ReaderInputOptions) {
        runOnUiThread {
            // Delegate out to the current fragment, if it acts as a MobileReaderListener
            supportFragmentManager.fragments.last()?.let {
                if (it is MobileReaderListener) {
                    it.onRequestReaderInput(options)
                }
            }
        }
    }

    override fun onRequestReaderDisplayMessage(message: ReaderDisplayMessage) {
        runOnUiThread {
            // Delegate out to the current fragment, if it acts as a MobileReaderListener
            supportFragmentManager.fragments.last()?.let {
                if (it is MobileReaderListener) {
                    it.onRequestReaderDisplayMessage(message)
                }
            }
        }
    }


    override fun onReaderReconnectStarted(reader: Reader, cancelReconnect: Cancelable, reason: DisconnectReason) {
        Log.d(TAG, "Reconnection to reader ${reader.id} started!")
    }

    override fun onReaderReconnectSucceeded(reader: Reader) {
        Log.d(TAG, "Reader ${reader.id} reconnected successfully")
    }

    override fun onReaderReconnectFailed(reader: Reader) {
        Log.d(TAG, "Reconnection to reader ${reader.id} failed!")
    }

    override fun onDisconnect(reason: DisconnectReason) {
        if (reason == DisconnectReason.UNKNOWN) {
            Log.i("UnexpectedDisconnect", "disconnect reason: $reason")
        }
    }

    /**
     * Initialize the [Terminal] and go to the [TerminalFragment]
     */
    @OptIn(OfflineMode::class)
    private fun initialize() {
        // Initialize the Terminal as soon as possible
        try {
            if (!Terminal.isInitialized()) {
                Terminal.init(
                        applicationContext,
                        LogLevel.VERBOSE,
                        TokenProvider(),
                        TerminalEventListener,
                        TerminalOfflineListener
                )
            }
        } catch (e: TerminalException) {
            throw RuntimeException(e)
        }

        navigateTo(TerminalFragment.TAG, TerminalFragment())
    }

    /**
     * Navigate to the given fragment.
     *
     * @param fragment Fragment to navigate to.
     */
    private fun navigateTo(
        tag: String,
        fragment: Fragment,
        replace: Boolean = true,
        addToBackStack: Boolean = false,
    ) {
        val frag = supportFragmentManager.findFragmentByTag(tag) ?: fragment
        supportFragmentManager
            .beginTransaction()
            .apply {
                if (replace) {
                    replace(R.id.container, frag, tag)
                } else {
                    add(R.id.container, frag, tag)
                }

                if (addToBackStack) {
                    addToBackStack(tag)
                }
            }
            .commitAllowingStateLoss()
    }
}
