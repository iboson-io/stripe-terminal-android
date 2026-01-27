package com.stripe.example

import android.content.Context
import android.content.SharedPreferences
import com.stripe.example.fragment.discovery.DiscoveryMethod

/**
 * Helper class to persist and retrieve reader connection information for auto-reconnect
 */
object ReaderPreferences {
    private const val PREFS_NAME = "reader_preferences"
    private const val KEY_READER_ID = "reader_id"
    private const val KEY_READER_SERIAL = "reader_serial"
    private const val KEY_DISCOVERY_METHOD = "discovery_method"
    private const val KEY_LOCATION_ID = "location_id"
    private const val KEY_READER_DEVICE_TYPE = "reader_device_type"

    private fun getSharedPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Save reader information after successful connection
     */
    fun saveReaderInfo(
        context: Context,
        readerId: String,
        readerSerial: String?,
        discoveryMethod: DiscoveryMethod,
        locationId: String?,
        deviceType: String?
    ) {
        val prefs = getSharedPreferences(context)
        prefs.edit().apply {
            putString(KEY_READER_ID, readerId)
            putString(KEY_READER_SERIAL, readerSerial)
            putString(KEY_DISCOVERY_METHOD, discoveryMethod.name)
            putString(KEY_LOCATION_ID, locationId)
            putString(KEY_READER_DEVICE_TYPE, deviceType)
            apply()
        }
    }

    /**
     * Get saved reader information
     */
    fun getSavedReaderInfo(context: Context): SavedReaderInfo? {
        val prefs = getSharedPreferences(context)
        val readerId = prefs.getString(KEY_READER_ID, null) ?: return null
        val readerSerial = prefs.getString(KEY_READER_SERIAL, null)
        val discoveryMethodStr = prefs.getString(KEY_DISCOVERY_METHOD, null)
        val locationId = prefs.getString(KEY_LOCATION_ID, null)
        val deviceType = prefs.getString(KEY_READER_DEVICE_TYPE, null)

        val discoveryMethod = try {
            DiscoveryMethod.valueOf(discoveryMethodStr ?: DiscoveryMethod.BLUETOOTH_SCAN.name)
        } catch (e: IllegalArgumentException) {
            DiscoveryMethod.BLUETOOTH_SCAN
        }

        return SavedReaderInfo(
            readerId = readerId,
            readerSerial = readerSerial,
            discoveryMethod = discoveryMethod,
            locationId = locationId,
            deviceType = deviceType
        )
    }

    /**
     * Clear saved reader information (e.g., when user disconnects)
     */
    fun clearReaderInfo(context: Context) {
        val prefs = getSharedPreferences(context)
        prefs.edit().clear().apply()
    }

    /**
     * Data class to hold saved reader information
     */
    data class SavedReaderInfo(
        val readerId: String,
        val readerSerial: String?,
        val discoveryMethod: DiscoveryMethod,
        val locationId: String?,
        val deviceType: String?
    )
}
