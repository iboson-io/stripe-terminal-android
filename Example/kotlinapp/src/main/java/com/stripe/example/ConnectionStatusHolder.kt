package com.stripe.example

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.stripe.stripeterminal.external.models.ConnectionStatus

/**
 * Holds the current terminal connection status so UI can enable/disable Pay only when connected.
 * Updated by [TerminalEventListener.onConnectionStatusChange].
 */
object ConnectionStatusHolder {

    private val _connectionStatus = MutableLiveData<ConnectionStatus>(ConnectionStatus.NOT_CONNECTED)

    /** Observable connection status. Pay should only be allowed when value is [ConnectionStatus.CONNECTED]. */
    val connectionStatus: LiveData<ConnectionStatus> = _connectionStatus

    /** Current value for synchronous checks (e.g. before starting payment). */
    val isConnected: Boolean
        get() = _connectionStatus.value == ConnectionStatus.CONNECTED

    /** Called from [TerminalEventListener] and when syncing initial state after Terminal.init. */
    fun setStatus(status: ConnectionStatus) {
        _connectionStatus.postValue(status)
    }
}
