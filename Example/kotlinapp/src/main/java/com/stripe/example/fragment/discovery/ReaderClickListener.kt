package com.stripe.example.fragment.discovery

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.viewModelScope
import com.stripe.example.BuildConfig
import com.stripe.example.MainActivity
import com.stripe.example.R
import com.stripe.example.viewmodel.DiscoveryViewModel
import com.stripe.stripeterminal.Terminal
import com.stripe.stripeterminal.external.models.ConnectionConfiguration
import com.stripe.stripeterminal.external.models.Reader
import com.stripe.stripeterminal.external.models.TerminalException
import com.stripe.stripeterminal.ktx.connectReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

class ReaderClickListener(
    private val activity: MainActivity,
    private val viewModel: DiscoveryViewModel
) {
    fun onClick(reader: Reader) {
        // Use Stripe location ID from gradle.properties (not from deep link or reader)
        // This is the Stripe Terminal location ID, not the frontend location ID
        val connectLocationId = BuildConfig.STRIPE_LOCATION_ID.ifEmpty { null }

        // If location ID is not configured in gradle.properties, show error
        if (connectLocationId.isNullOrEmpty()) {
            AlertDialog.Builder(activity)
                .setPositiveButton(R.string.alert_acknowledge_button) { _, _ -> }
                .setTitle(R.string.location_required_dialog_title)
                .setMessage("Stripe location ID not configured. Please set LOCATION_ID in gradle.properties")
                .show()
            return
        }

        val config = when (viewModel.discoveryMethod) {
            DiscoveryMethod.BLUETOOTH_SCAN ->
                ConnectionConfiguration.BluetoothConnectionConfiguration(
                    locationId = connectLocationId,
                    bluetoothReaderListener = activity,
                )

            DiscoveryMethod.USB -> ConnectionConfiguration.UsbConnectionConfiguration(
                locationId = connectLocationId,
                usbReaderListener = activity,
            )
        }

        val activityRef = WeakReference(activity)
        val viewModelRef = WeakReference(viewModel)
        // Use id or serialNumber as unique identifier
        val readerId = reader.id ?: reader.serialNumber

        viewModel.viewModelScope.launch {
            // Set the connecting reader ID to show spinner next to this reader
            viewModelRef.get()?.connectingReaderId?.postValue(readerId)
            val result = runCatching { Terminal.getInstance().connectReader(reader, config) }
                // rethrow CancellationException to properly cancel the coroutine
                .onFailure { if (it is CancellationException) throw it }
            withContext(Dispatchers.Main) {
                // switch to the main thread to update the UI
                val activity = activityRef.get() ?: return@withContext
                val viewModel = viewModelRef.get() ?: return@withContext

                if (result.isSuccess) {
                    viewModel.connectingReaderId.value = null
                    activity.onConnectReader()
                    viewModel.isUpdating.value = false
                    viewModel.isConnecting.value = false
                } else {
                    // handle failure - show toast with error message
                    val exception = result.exceptionOrNull() as? TerminalException
                    val errorMessage = if (exception != null) {
                        "Failed to connect: ${exception.errorMessage ?: exception.errorCode}"
                    } else {
                        "Failed to connect to reader"
                    }
                    Toast.makeText(
                        activity,
                        errorMessage,
                        Toast.LENGTH_LONG
                    ).show()
                    // Clear connecting state to hide spinner and allow retry
                    viewModel.connectingReaderId.value = null
                    viewModel.isUpdating.value = false
                }
            }
        }
    }
}
