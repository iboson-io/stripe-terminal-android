package com.stripe.example.network

import android.util.Log
import com.stripe.example.BuildConfig
import com.stripe.example.MainActivity
import com.stripe.example.model.PaymentIntentCreationResponse
import com.stripe.stripeterminal.external.models.ConnectionTokenException
import okhttp3.OkHttpClient
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The `ApiClient` is a singleton object used to make calls to our backend and return their results
 */
object ApiClient {

    private const val TAG = "ApiClient"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.EXAMPLE_BACKEND_URL)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    private val service: BackendService = retrofit.create(BackendService::class.java)

    @Throws(ConnectionTokenException::class)
    internal fun createConnectionToken(): String {
        try {
            val result = service.getConnectionToken().execute()
            if (result.isSuccessful && result.body() != null) {
                return result.body()!!.secret
            } else {
                throw ConnectionTokenException("Creating connection token failed")
            }
        } catch (e: IOException) {
            throw ConnectionTokenException("Creating connection token failed", e)
        }
    }

    @Throws(Exception::class)
    internal fun createLocation(
        displayName: String,
        line1: String,
        line2: String?,
        city: String?,
        postalCode: String?,
        state: String?,
        country: String,
    ) {
        try {
            val result = service.createLocation(
                displayName,
                line1,
                line2,
                city,
                postalCode,
                state,
                country
            ).execute()
            if (result.isSuccessful.not()) {
                throw Exception("Creating location failed")
            }
        } catch (e: IOException) {
            throw Exception("Creating location failed", e)
        }
    }

    internal fun capturePaymentIntent(id: String) {
        try {
            if (id.isEmpty()) {
                Log.e(TAG, "Cannot capture payment intent: ID is empty")
                return
            }
            
            val result = service.capturePaymentIntent(id).execute()
            if (!result.isSuccessful) {
                val errorBody = result.errorBody()?.string()
                Log.e(TAG, "Failed to capture payment intent: ${result.code()} - ${result.message()} - $errorBody")
            } else {
                Log.d(TAG, "Payment intent captured successfully: $id")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error capturing payment intent", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error capturing payment intent", e)
        }
    }

    internal fun cancelPaymentIntent(
        id: String,
        callback: Callback<Void>
    ) {
        service.cancelPaymentIntent(id).enqueue(callback)
    }

    /**
     * Create a PaymentIntent on the backend with all metadata from deep link.
     * The backend creates the PaymentIntent and returns the client secret.
     */
    internal fun createPaymentIntent(
        amount: Long,
        currency: String,
        extendedAuth: Boolean,
        incrementalAuth: Boolean,
        customerId: String?,
        orderId: String?,
        locationId: String?,
        email: String?,
        id: String?,
        adminUserId: String?,
        washType: String?,
        packageId: String?,
        vehicleId: String?,
        callback: Callback<PaymentIntentCreationResponse>
    ) {
        val createPaymentIntentParams = buildMap<String, String> {
            put("amount", amount.toString())
            put("currency", currency)
            
            // Add email as top-level parameter
            email?.let { put("email", it) }

            if (extendedAuth) {
                put("payment_method_options[card_present[request_extended_authorization]]", "true")
            }
            if (incrementalAuth) {
                put("payment_method_options[card_present[request_incremental_authorization_support]]", "true")
            }
            
            // Add metadata to payment intent
            customerId?.let { put("metadata[customer_id]", it) }
            // Use id value for order_id metadata key
            id?.let { put("metadata[order_id]", it) }
            locationId?.let { put("metadata[location_id]", it) }
            adminUserId?.let { put("metadata[admin_user_id]", it) }
            washType?.let { put("metadata[wash_type]", it) }
            packageId?.let { put("metadata[package_id]", it) }
            vehicleId?.let { put("metadata[vehicle_id]", it) }
            put("metadata[source]", "saas")
        }

        Log.d(TAG, "Creating PaymentIntent with params: $createPaymentIntentParams")
        service.createPaymentIntent(createPaymentIntentParams).enqueue(callback)
    }
}
