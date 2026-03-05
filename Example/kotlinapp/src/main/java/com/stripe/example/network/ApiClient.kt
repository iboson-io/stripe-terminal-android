package com.stripe.example.network

import android.util.Log
import com.stripe.example.BuildConfig
import com.stripe.example.MainActivity
import com.stripe.example.model.PaymentIntentCreationResponse
import com.stripe.stripeterminal.external.models.ConnectionTokenException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The `ApiClient` is a singleton object used to make calls to our backend and return their results
 */
object ApiClient {

    private const val TAG = "ApiClient"
    private const val PAYMENT_MODE = "terminal"

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
     * Suspend version of createPaymentIntent — wraps the Retrofit call in a cancellable coroutine.
     * This is properly cancelled when the calling ViewModel's scope is cancelled (e.g. Fragment
     * replaced), preventing stale backend calls from completing and polluting a new payment session.
     */
    internal suspend fun createPaymentIntentSuspend(
        amount: Long,
        currency: String,
        customerId: String?,
        orderId: String?,
        locationId: String?,
        email: String?,
        id: String?,
        adminUserId: String?,
        washType: String?,
        packageId: String?,
        vehicleId: String?,
        source: String?,
        publicOrderId: String?,
        stripeAccountId: String?
    ): PaymentIntentCreationResponse = suspendCancellableCoroutine { cont ->
        val params = buildMap<String, String> {
            put("amount", amount.toString())
            put("currency", currency)
            email?.let { put("email", it) }
            customerId?.let { put("metadata[customer_id]", it) }
            id?.let { put("metadata[order_id]", it) }
            locationId?.let { put("metadata[location_id]", it) }
            adminUserId?.let { put("metadata[admin_user_id]", it) }
            washType?.let { put("metadata[wash_type]", it) }
            packageId?.let { put("metadata[package_id]", it) }
            vehicleId?.let { put("metadata[vehicle_id]", it) }
            put("metadata[source]", source?.takeIf { it.isNotBlank() } ?: "lpr")
            publicOrderId?.let { put("metadata[public_order_id]", it) }
            put("metadata[paymentMode]", PAYMENT_MODE)
            stripeAccountId?.let { put("stripe_account_id", it) }
        }
        Log.d(TAG, "Creating PaymentIntent (suspend) with params: $params")
        val call = service.createPaymentIntent(params)
        // Cancel the HTTP call when the coroutine is cancelled (e.g. ViewModel cleared)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback<PaymentIntentCreationResponse> {
            override fun onResponse(
                call: Call<PaymentIntentCreationResponse>,
                response: Response<PaymentIntentCreationResponse>
            ) {
                if (!cont.isActive) return
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null) {
                        cont.resume(body)
                    } else {
                        cont.resumeWithException(Exception("Empty response from server"))
                    }
                } else {
                    val errorBody = response.errorBody()?.string()
                    cont.resumeWithException(
                        Exception("HTTP ${response.code()}: ${response.message()} — $errorBody")
                    )
                }
            }
            override fun onFailure(call: Call<PaymentIntentCreationResponse>, t: Throwable) {
                if (!cont.isActive) return
                cont.resumeWithException(t)
            }
        })
    }
}
