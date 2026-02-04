package com.stripe.example.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.stripe.example.ConnectionStatusHolder
import com.stripe.example.MainActivity
import com.stripe.example.NavigationListener
import com.stripe.example.R
import com.stripe.stripeterminal.external.models.ConnectionStatus
import java.text.NumberFormat
import java.util.Locale

/**
 * The `PaymentFragment` allows the user to create a custom payment and ask the reader to handle it.
 */
class PaymentFragment : Fragment() {

    companion object {
        const val TAG = "com.stripe.example.fragment.PaymentFragment"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        val view = inflater.inflate(R.layout.fragment_payment, container, false)
        val amountEditText = view.findViewById<TextView>(R.id.amount_edit_text)
        val chargeAmount = view.findViewById<TextView>(R.id.charge_amount)
        val currentEditText = view.findViewById<EditText>(R.id.currency_edit_text)

        // Check if deep link data exists
        val deepLinkAmount = MainActivity.deepLinkAmount
        val deepLinkAmountDisplay = MainActivity.deepLinkAmountDisplay
        val deepLinkCurrency = MainActivity.deepLinkCurrency

        if (deepLinkAmount != null && deepLinkAmountDisplay != null) {
            // Pre-fill amount from deep link
            amountEditText.text = deepLinkAmount.toString()
            chargeAmount.text = formatCentsToString(deepLinkAmount.toInt())
            
            // Pre-fill currency from deep link
            currentEditText.setText(deepLinkCurrency.lowercase())
            
            // Disable amount editing when deep link exists
            amountEditText.isEnabled = false
            amountEditText.isFocusable = false
        } else {
            // Normal behavior - allow editing
        amountEditText.doAfterTextChanged { editable ->
            if (editable.toString().isNotEmpty()) {
                chargeAmount.text = formatCentsToString(editable.toString().toInt())
                }
            }
        }

        val collectPaymentButton = view.findViewById<MaterialButton>(R.id.collect_payment_button)
        collectPaymentButton.setOnClickListener {
            if (!ConnectionStatusHolder.isConnected) return@setOnClickListener
            (activity as? NavigationListener)?.onRequestPayment(
                amountEditText.text.toString().toLong(),
                currentEditText.text.toString(),
            )
        }

        // Enable Pay only when terminal is connected; disable when disconnected
        ConnectionStatusHolder.connectionStatus.observe(viewLifecycleOwner) { status ->
            val connected = status == ConnectionStatus.CONNECTED
            collectPaymentButton.isEnabled = connected
            collectPaymentButton.alpha = if (connected) 1f else 0.5f
        }
        collectPaymentButton.isEnabled = ConnectionStatusHolder.isConnected
        collectPaymentButton.alpha = if (ConnectionStatusHolder.isConnected) 1f else 0.5f

        view.findViewById<View>(R.id.home_button).setOnClickListener {
            (activity as? NavigationListener)?.onRequestExitWorkflow()
        }
        return view
    }

    private fun formatCentsToString(i: Int): String {
        return NumberFormat.getCurrencyInstance(Locale.US).format(i / 100.0)
    }
}
