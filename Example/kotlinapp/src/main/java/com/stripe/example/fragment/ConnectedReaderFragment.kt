package com.stripe.example.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.stripe.example.NavigationListener
import com.stripe.example.R
import com.stripe.example.TerminalOfflineListener
import com.stripe.example.customviews.TerminalOnlineIndicator
import com.stripe.stripeterminal.Terminal
import com.stripe.stripeterminal.external.OfflineMode
import com.stripe.stripeterminal.external.callable.Callback
import com.stripe.stripeterminal.external.models.NetworkStatus
import com.stripe.stripeterminal.external.models.TerminalException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * The `ConnectedReaderFragment` displays the reader that's currently connected and provides
 * options for workflows that can be executed.
 */
@OptIn(OfflineMode::class)
class ConnectedReaderFragment : Fragment() {

    companion object {
        const val TAG = "com.stripe.example.fragment.ConnectedReaderFragment"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        val view = inflater.inflate(R.layout.fragment_connected_reader, container, false)

        // Set the description of the connected reader
        Terminal.getInstance().connectedReader?.let {
            view.findViewById<TextView>(R.id.reader_description).text = getString(
                R.string.reader_description,
                it.deviceType,
                it.serialNumber,
            )
        }

        // Set up the disconnect button
        val activityRef = WeakReference(activity)
        val fragmentRef = WeakReference(this)
        val disconnectButton = view.findViewById<Button>(R.id.disconnect_button)
        val readerDescription = view.findViewById<TextView>(R.id.reader_description)

        disconnectButton.setOnClickListener {
            // Disable button immediately to prevent double-taps and show progress
            disconnectButton.isEnabled = false
            readerDescription.text = getString(R.string.disconnecting)

            Terminal.getInstance().disconnectReader(object : Callback {

                override fun onSuccess() {
                    activityRef.get()?.let { act ->
                        if (act is NavigationListener) {
                            act.runOnUiThread { act.onDisconnectReader() }
                        }
                    }
                }

                override fun onFailure(e: TerminalException) {
                    // Re-enable the button and restore reader description so the user can retry
                    activityRef.get()?.runOnUiThread {
                        disconnectButton.isEnabled = true
                        Terminal.getInstance().connectedReader?.let { reader ->
                            fragmentRef.get()?.let { frag ->
                                readerDescription.text = frag.getString(
                                    R.string.reader_description,
                                    reader.deviceType,
                                    reader.serialNumber,
                                )
                            }
                        } ?: run {
                            readerDescription.text = getString(R.string.disconnect_failed_retry)
                        }
                        Toast.makeText(
                            activityRef.get(),
                            getString(R.string.disconnect_failed_message, e.errorMessage ?: "unknown error"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            })
        }

        launchAndRepeatWithViewLifecycle(Lifecycle.State.RESUMED) {
            launch {
                TerminalOfflineListener.offlineStatus
                        .collectLatest {
                            updateTerminalOnlineIndicator(it)
                        }
            }
        }

        updateTerminalOnlineIndicator(Terminal.getInstance().offlineStatus.sdk.networkStatus)

        // Set up the collect payment button
        view.findViewById<View>(R.id.collect_card_payment_button).setOnClickListener {
            (activity as? NavigationListener)?.onSelectPaymentWorkflow()
        }

        // Set up the save card button
        view.findViewById<View>(R.id.save_card_button).setOnClickListener {
            (activity as? NavigationListener)?.onRequestSaveCard()
        }

        // Set up the update reader button
        view.findViewById<View>(R.id.update_reader_button).setOnClickListener {
            (activity as? NavigationListener)?.onSelectUpdateWorkflow()
        }

        // Offline logs button removed - app only works online

        // set up the ledger button
        view.findViewById<View>(R.id.view_ledger_button).setOnClickListener {
            (activity as? NavigationListener)?.onSelectViewLedger()
        }

        return view
    }

    private fun updateTerminalOnlineIndicator(networkStatus: NetworkStatus) {
        view?.findViewById<TerminalOnlineIndicator>(R.id.online_indicator).run {
            this?.networkStatus = networkStatus
        }
    }
}
