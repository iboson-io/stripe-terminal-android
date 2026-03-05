package com.stripe.example.viewmodel

import android.Manifest
import androidx.annotation.RequiresPermission
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stripe.example.NavigationListener
import com.stripe.example.fragment.discovery.DiscoveryMethod
import com.stripe.example.fragment.discovery.DiscoveryMethod.BLUETOOTH_SCAN
import com.stripe.example.fragment.discovery.DiscoveryMethod.USB
import com.stripe.example.fragment.discovery.ReaderClickListener
import com.stripe.stripeterminal.Terminal
import com.stripe.stripeterminal.external.callable.Cancelable
import com.stripe.stripeterminal.external.models.DiscoveryConfiguration
import com.stripe.stripeterminal.external.models.Location
import com.stripe.stripeterminal.external.models.Reader
import com.stripe.stripeterminal.ktx.discoverReaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

class DiscoveryViewModel(
    val discoveryMethod: DiscoveryMethod,
    private val isSimulated: Boolean
) : ViewModel() {
    val readers: MutableLiveData<List<Reader>> = MutableLiveData(listOf())
    val isConnecting: MutableLiveData<Boolean> = MutableLiveData(false)
    val connectingReaderId: MutableLiveData<String?> = MutableLiveData(null)
    val isUpdating: MutableLiveData<Boolean> = MutableLiveData(false)
    val updateProgress: MutableLiveData<Float> = MutableLiveData(0F)
    val isDiscoveryTimedOut: MutableLiveData<Boolean> = MutableLiveData(false)
    // Removed selectedLocation - M2 readers should already have location assigned when discovered

    var discoveryTask: Cancelable? = null
    var readerClickListener: ReaderClickListener? = null
    var navigationListener: NavigationListener? = null
    
    private var discoveryJob: Job? = null
    private var timeoutJob: Job? = null
    private val discoveryJobs = mutableListOf<Job>()
    // Tracks an in-flight stopDiscovery coroutine so rapid Refresh taps don't spawn multiple ones
    private var stopDiscoveryJob: Job? = null
    
    companion object {
        private const val DISCOVERY_TIMEOUT_MS = 15000L // 15 seconds timeout
    }

    // Removed location selection functionality for M2 readers
    // private var isRequestingChangeLocation: Boolean = false
    // fun requestChangeLocation() { ... }

    private val discoveryConfig: DiscoveryConfiguration
        get() = when (discoveryMethod) {
            BLUETOOTH_SCAN -> DiscoveryConfiguration.BluetoothDiscoveryConfiguration(0, isSimulated)
            USB -> DiscoveryConfiguration.UsbDiscoveryConfiguration(0, isSimulated)
        }

    @RequiresPermission(
        anyOf = [
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ],
    )
    fun startDiscovery(onFailure: () -> Unit) {
        // Reset timeout state
        isDiscoveryTimedOut.postValue(false)
        com.stripe.example.PaymentLogger.startStep("bt_discovery")

        // Create discovery job first
        val newDiscoveryJob = viewModelScope.launch {
            Terminal.getInstance().discoverReaders(config = discoveryConfig)
                .catch { e ->
                    if (e is CancellationException) {
                        // Ignore cancellations (including timeout cancellations)
                        return@catch
                    }
                    com.stripe.example.PaymentLogger.endStep("bt_discovery", false, "error: ${e.message}")
                    onFailure()
                }
                .collect { discoveredReaders: List<Reader> ->
                    // Cancel timeout when readers are found
                    timeoutJob?.cancel()
                    isDiscoveryTimedOut.postValue(false)
                    val visible = discoveredReaders.filter { it.networkStatus != Reader.NetworkStatus.OFFLINE }
                    if (visible.isNotEmpty()) {
                        com.stripe.example.PaymentLogger.endStep(
                            "bt_discovery", true, "${visible.size} reader(s) found"
                        )
                    }
                    readers.postValue(visible)
                }
        }

        // Start timeout job
        val newTimeoutJob = viewModelScope.launch {
            delay(DISCOVERY_TIMEOUT_MS)
            // Stop discovery when timeout occurs
            newDiscoveryJob.cancel("Discovery timeout")
            com.stripe.example.PaymentLogger.endStep(
                "bt_discovery", false, "timeout after ${DISCOVERY_TIMEOUT_MS}ms"
            )
            isDiscoveryTimedOut.postValue(true)
        }
        
        discoveryJob = newDiscoveryJob
        timeoutJob = newTimeoutJob
        discoveryJobs.add(newDiscoveryJob)
        discoveryJobs.add(newTimeoutJob)
    }
    fun stopDiscovery(onSuccess: () -> Unit = { }) {
        // Cancel any previous stop that is still waiting on joinAll so that rapid
        // Refresh taps cannot spawn multiple concurrent discovery sessions.
        stopDiscoveryJob?.cancel()
        stopDiscoveryJob = viewModelScope.launch {
            discoveryJobs.forEach { it.cancel("Stopping discovery") }
            discoveryJobs.joinAll()
            discoveryJobs.clear()
            discoveryJob = null
            timeoutJob = null
            onSuccess()
        }
    }

    override fun onCleared() {
        super.onCleared()
        readerClickListener = null
        navigationListener = null
        stopDiscovery()
    }
}
