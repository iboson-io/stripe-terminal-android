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
import com.stripe.example.network.TokenProvider
import com.stripe.stripeterminal.Terminal
import com.stripe.stripeterminal.external.OfflineMode
import com.stripe.stripeterminal.external.callable.Cancelable
import com.stripe.stripeterminal.external.callable.MobileReaderListener
import com.stripe.stripeterminal.external.models.ConnectionStatus
import com.stripe.stripeterminal.external.models.DisconnectReason
import com.stripe.stripeterminal.external.models.Location
import com.stripe.stripeterminal.external.models.Reader
import com.stripe.stripeterminal.external.models.ReaderDisplayMessage
import com.stripe.stripeterminal.external.models.ReaderInputOptions
import com.stripe.stripeterminal.external.models.ReaderSoftwareUpdate
import com.stripe.stripeterminal.external.models.TerminalException
import com.stripe.stripeterminal.log.LogLevel
import android.widget.Toast

class MainActivity :
    AppCompatActivity(),
    NavigationListener,
    MobileReaderListener {

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
        var deepLinkSource: String? = null
        var deepLinkPhoneNumber: String? = null
        var deepLinkPublicOrderId: String? = null
        
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
            deepLinkSource = null
            deepLinkPhoneNumber = null
            deepLinkPublicOrderId = null
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
        
        // If deep link was received, handle it
        if (deepLinkAmount != null) {
            Log.d(TAG, "Deep link received, amount: $deepLinkAmountDisplay, currency: $deepLinkCurrency")
            val connectionStatus = Terminal.getInstance().connectionStatus
            if (connectionStatus == ConnectionStatus.CONNECTED) {
                // Reader connected, navigate directly to payment/testing screen
                navigateTo(
                    EventFragment.TAG,
                    EventFragment.requestPayment(
                        deepLinkAmount!!,
                        deepLinkCurrency.lowercase()
                    )
                )
            } else {
                // Reader not connected, auto-start Bluetooth discovery
                Log.d(TAG, "Reader not connected, auto-starting Bluetooth discovery (simulated: ${BuildConfig.USE_SIMULATED_READER})")
                onRequestDiscovery(isSimulated = BuildConfig.USE_SIMULATED_READER, discoveryMethod = DiscoveryMethod.BLUETOOTH_SCAN)
            }
        }
    }

    private fun handleDeepLink(intent: Intent?) {
        try {
            val data: Uri? = intent?.data
            // If no deep link in intent, clear any old deep link data
            if (data == null) {
                Log.d(TAG, "No deep link in intent, clearing old deep link data")
                clearDeepLinkData()
                return
            }
            
            Log.d(TAG, "Deep link: $data")

            val amountParam = data.getQueryParameter("amount")
            if (amountParam.isNullOrEmpty()) {
                Log.e(TAG, "Deep link missing required 'amount' parameter")
                clearDeepLinkData() // Clear old data if new deep link is invalid
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
            val source = data.getQueryParameter("source")
            val phoneNumber = data.getQueryParameter("phoneNumber")
            val publicOrderId = data.getQueryParameter("public_order_id")

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
                deepLinkSource = source
                deepLinkPhoneNumber = phoneNumber
                deepLinkPublicOrderId = publicOrderId

                Log.d(TAG, "Amount: $amountParam ($amountInCents cents), Currency: $deepLinkCurrency")
                Log.d(TAG, "CustomerId: $customerId, OrderId: $orderId, LocationId: $locationId, Email: $email, Id: $id")
                Log.d(TAG, "AdminUserId: $adminUserId, WashType: $washType, PackageId: $packageId, VehicleId: $vehicleId, Source: $source, PhoneNumber: $phoneNumber, PublicOrderId: $publicOrderId")
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
     * Removed navigation to terminal page - discovery can be stopped without leaving the screen
     */
    override fun onCancelDiscovery() {
        // No-op: Discovery can be stopped via back arrow without navigation
        // User can refresh to restart discovery if needed
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
            // Auto-start Bluetooth discovery (production mode - skip selection screen)
            Log.d(TAG, "Auto-starting Bluetooth discovery (simulated: ${BuildConfig.USE_SIMULATED_READER})")
            onRequestDiscovery(isSimulated = BuildConfig.USE_SIMULATED_READER, discoveryMethod = DiscoveryMethod.BLUETOOTH_SCAN)
        }
    }

    /**
     * Callback function called to start a payment by the [PaymentFragment].
     * Payment is only allowed when terminal reader is connected.
     */
    override fun onRequestPayment(
        amount: Long,
        currency: String,
    ) {
        if (Terminal.getInstance().connectionStatus != ConnectionStatus.CONNECTED) {
            Toast.makeText(this, R.string.reader_not_connected_wait, Toast.LENGTH_SHORT).show()
            return
        }
        navigateTo(
                EventFragment.TAG,
            EventFragment.requestPayment(
                amount,
                currency
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
        // Offline logs not needed - app only works online
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
        // If deep link exists, navigate directly to payment/testing screen
        if (deepLinkAmount != null) {
            navigateTo(
                EventFragment.TAG,
                EventFragment.requestPayment(
                    deepLinkAmount!!,
                    deepLinkCurrency.lowercase()
                )
            )
        } else {
            navigateTo(ConnectedReaderFragment.TAG, ConnectedReaderFragment())
        }
    }

    override fun onDisconnectReader() {
        // Auto-start Bluetooth discovery after disconnect (production mode)
        Log.d(TAG, "Reader disconnected, auto-starting Bluetooth discovery (simulated: ${BuildConfig.USE_SIMULATED_READER})")
        onRequestDiscovery(isSimulated = BuildConfig.USE_SIMULATED_READER, discoveryMethod = DiscoveryMethod.BLUETOOTH_SCAN)
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
     * Initialize the [Terminal] and navigate based on deep link and connection status
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

        // Sync connection status so Pay is only enabled when connected
        ConnectionStatusHolder.setStatus(Terminal.getInstance().connectionStatus)

        // Check if deep link exists and reader is already connected
        val connectionStatus = Terminal.getInstance().connectionStatus
        if (deepLinkAmount != null && connectionStatus == ConnectionStatus.CONNECTED) {
            Log.d(TAG, "Deep link present and reader connected, navigating directly to payment/testing screen")
            navigateTo(
                EventFragment.TAG,
                EventFragment.requestPayment(
                    deepLinkAmount!!,
                    deepLinkCurrency.lowercase()
                )
            )
        } else if (connectionStatus == ConnectionStatus.CONNECTED) {
            // Reader already connected, go to connected reader screen
            Log.d(TAG, "Reader connected, showing connected reader screen")
            navigateTo(ConnectedReaderFragment.TAG, ConnectedReaderFragment())
        } else {
            // No reader connected, auto-start Bluetooth discovery
            Log.d(TAG, "No reader connected, auto-starting Bluetooth discovery (simulated: ${BuildConfig.USE_SIMULATED_READER})")
            onRequestDiscovery(isSimulated = BuildConfig.USE_SIMULATED_READER, discoveryMethod = DiscoveryMethod.BLUETOOTH_SCAN)
        }
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
