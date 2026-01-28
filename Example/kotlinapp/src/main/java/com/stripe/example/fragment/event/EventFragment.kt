package com.stripe.example.fragment.event

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.BundleCompat
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import com.airbnb.lottie.LottieAnimationView
import com.stripe.example.NavigationListener
import com.stripe.example.R
import com.stripe.example.TerminalRepository
import com.stripe.example.databinding.FragmentEventBinding
import com.stripe.example.model.Event
import com.stripe.example.viewmodel.EventViewModel
import com.stripe.stripeterminal.external.callable.MobileReaderListener
import com.stripe.stripeterminal.external.models.AllowRedisplay
import com.stripe.stripeterminal.external.models.CardPresentParameters
import com.stripe.stripeterminal.external.models.CollectPaymentIntentConfiguration
import com.stripe.stripeterminal.external.models.CollectSetupIntentConfiguration
import com.stripe.stripeterminal.external.models.ConfirmPaymentIntentConfiguration
import com.stripe.stripeterminal.external.models.CreateConfiguration
import com.stripe.stripeterminal.external.models.DisconnectReason
import com.stripe.stripeterminal.external.models.PaymentIntentParameters
import com.stripe.stripeterminal.external.models.PaymentMethodOptionsParameters
import com.stripe.stripeterminal.external.models.PaymentMethodType
import com.stripe.stripeterminal.external.models.ReaderDisplayMessage
import com.stripe.stripeterminal.external.models.ReaderInputOptions
import com.stripe.stripeterminal.external.models.SetupIntentParameters
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * The `EventFragment` displays events as they happen during a payment flow
 */
class EventFragment : Fragment(), MobileReaderListener {

    companion object {
        const val TAG = "com.stripe.example.fragment.event.EventFragment"

        private const val AMOUNT = "com.stripe.example.fragment.event.EventFragment.amount"
        private const val CURRENCY = "com.stripe.example.fragment.event.EventFragment.currency"
        private const val REQUEST_PAYMENT = "com.stripe.example.fragment.event.EventFragment.request_payment"
        private const val SAVE_CARD = "com.stripe.example.fragment.event.EventFragment.save_card"
        private const val TRANSACTION_ID = "com.stripe.example.fragment.event.EventFragment.transaction_id"
        private const val REFUND_PAYMENT = "com.stripe.example.fragment.event.EventFragment.refund_payment"
        private const val CANCEL_TRANSACTION = "com.stripe.example.fragment.event.EventFragment.cancel_transaction"

        fun collectSetupIntentPaymentMethod(): EventFragment {
            val fragment = EventFragment()
            val bundle = Bundle()
            bundle.putBoolean(SAVE_CARD, true)
            fragment.arguments = bundle
            return fragment
        }

        fun requestPayment(
            amount: Long,
            currency: String
        ): EventFragment {
            val fragment = EventFragment()
            val bundle = Bundle()
            bundle.putLong(AMOUNT, amount)
            bundle.putString(CURRENCY, currency)
            bundle.putBoolean(REQUEST_PAYMENT, true)
            fragment.arguments = bundle
            return fragment
        }

        fun refundPayment(transactionId: String): EventFragment {
            val fragment = EventFragment()
            val bundle = Bundle()
            bundle.putBoolean(REFUND_PAYMENT, true)
            bundle.putString(TRANSACTION_ID, transactionId)
            fragment.arguments = bundle
            return fragment
        }

        fun cancelTransaction(transactionId: String): EventFragment {
            val fragment = EventFragment()
            val bundle = Bundle()
            bundle.putBoolean(CANCEL_TRANSACTION, true)
            bundle.putString(TRANSACTION_ID, transactionId)
            fragment.arguments = bundle
            return fragment
        }
    }

    private lateinit var activityRef: WeakReference<FragmentActivity?>
    private var completionDialogShown = false // Prevent showing dialog multiple times

    private lateinit var binding: FragmentEventBinding
    private lateinit var viewModel: EventViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityRef = WeakReference(activity)
        viewModel = ViewModelProvider(this)[EventViewModel::class.java]

        if (savedInstanceState == null) {
            val fragmentArgs = requireArguments()
            when {
                fragmentArgs.getBoolean(REQUEST_PAYMENT, false) -> handleRequestPayment(fragmentArgs)
                fragmentArgs.getBoolean(SAVE_CARD, false) -> handleSaveCard()
                fragmentArgs.getBoolean(REFUND_PAYMENT, false) -> handleRefundPayment(fragmentArgs)
                fragmentArgs.getBoolean(CANCEL_TRANSACTION, false) -> handleCancelTransaction(fragmentArgs)
            }
        }
    }

    private fun handleRequestPayment(args: Bundle) {
        val currency = args.getString(CURRENCY)?.lowercase(Locale.ENGLISH) ?: "usd"
        val amount = args.getLong(AMOUNT)
        
        // Set display amount and currency in ViewModel
        val formattedAmount = formatCentsToString(amount.toInt())
        viewModel.displayAmount.value = formattedAmount
        viewModel.displayCurrency.value = currency.uppercase()

        // M2 readers use fixed amounts from deep link, no extended/incremental auth needed
        // App only works online, so no offline configuration needed
        val paymentMethodOptionsParameters = PaymentMethodOptionsParameters.Builder()
            .setCardPresentParameters(CardPresentParameters.Builder().build())
            .build()

        val params = PaymentIntentParameters.Builder(
            paymentMethodTypes = buildList {
                add(PaymentMethodType.CARD_PRESENT)
                if (currency == "cad") {
                    // Interac is only supported in Canada
                    add(PaymentMethodType.INTERAC_PRESENT)
                }
            }
        )
            .setAmount(amount)
            .setCurrency(currency)
            .setPaymentMethodOptionsParameters(paymentMethodOptionsParameters)
            .setMetadata(TerminalRepository.genMetaData())
            .build()
        // No offline configuration - app only works online
        val createConfiguration: CreateConfiguration? = null
        // M2 readers don't support tipping (no customer-facing display)
        val collectConfig = CollectPaymentIntentConfiguration.Builder()
            .skipTipping(true) // Always skip tipping for M2 readers
            .build()

        viewModel.takePayment(
            paymentParameters = params,
            createConfiguration = createConfiguration,
            collectConfiguration = collectConfig,
            confirmConfiguration = ConfirmPaymentIntentConfiguration.Builder().build()
        )
    }

    private fun handleSaveCard() {
        val params = SetupIntentParameters.Builder().setMetadata(TerminalRepository.genMetaData()).build()
        viewModel.saveCard(
            setupIntentParameters = params,
            allowRedisplay = AllowRedisplay.ALWAYS,
            collectConfiguration = CollectSetupIntentConfiguration.Builder().build()
        )
    }

    private fun handleRefundPayment(args: Bundle) {
        viewModel.refundTransaction(args.getString(TRANSACTION_ID))
    }

    private fun handleCancelTransaction(args: Bundle) {
        viewModel.cancelTransaction(args.getString(TRANSACTION_ID))
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Inflate the layout for this fragment
        binding = DataBindingUtil.inflate(inflater, R.layout.fragment_event, container, false)
        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = viewModel

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.cancelButton.setOnClickListener {
            viewModel.cancel()
        }
        
        // Initialize Lottie animation
        val lottieAnimationView = view.findViewById<LottieAnimationView>(R.id.lottie_animation_view)
        lottieAnimationView.setAnimation(R.raw.nfc_reader)
        lottieAnimationView.repeatCount = -1 // Infinite loop
        lottieAnimationView.playAnimation()
        
        // Set amount and currency display if arguments exist (for deep link)
        val fragmentArgs = arguments
        if (fragmentArgs != null && fragmentArgs.getBoolean(REQUEST_PAYMENT, false)) {
            val amount = fragmentArgs.getLong(AMOUNT)
            val currency = fragmentArgs.getString(CURRENCY)?.uppercase() ?: "USD"
            val formattedAmount = formatCentsToString(amount.toInt())
            viewModel.displayAmount.value = formattedAmount
            viewModel.displayCurrency.value = currency
        }

        // Observe payment completion and automatically close app
        viewModel.isComplete.observe(viewLifecycleOwner) { isComplete ->
            if (isComplete && !completionDialogShown) {
                // Auto-close app when payment is complete (only once)
                completionDialogShown = true
                // Small delay to show final event, then close app
                view.postDelayed({
                    closeApp()
                }, 1000) // 1 second delay to show final status
            }
        }
    }
    
    private fun closeApp() {
        // Clear deep link data after payment completion
        com.stripe.example.MainActivity.clearDeepLinkData()
        
        activityRef.get()?.let { activity ->
            activity.finishAffinity() // Close app completely to return to web app
        }
    }

    override fun onRequestReaderDisplayMessage(message: ReaderDisplayMessage) {
        addEvent(message.toString(), "listener.onRequestReaderDisplayMessage")
    }

    override fun onRequestReaderInput(options: ReaderInputOptions) {
        addEvent(options.toString(), "listener.onRequestReaderInput")
    }

    override fun onDisconnect(reason: DisconnectReason) {
        addEvent(reason.name, "listener.onDisconnect")
    }

    fun addEvent(message: String, method: String) {
        activityRef.get()?.let { activity ->
            activity.runOnUiThread {
                viewModel.addEvent(Event(message, method))
            }
        }
    }
    
    private fun formatCentsToString(cents: Int): String {
        val dollars = cents / 100.0
        return String.format(Locale.US, "$%.2f", dollars)
    }
}
