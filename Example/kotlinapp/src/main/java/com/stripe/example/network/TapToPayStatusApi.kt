package com.stripe.example.network

import android.util.Log
import com.stripe.example.BuildConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * Fire-and-forget client that reports tap-to-pay lifecycle statuses to the carwash API.
 *
 * Usage:
 *   TapToPayStatusApi.send(orderId, token, TapToPayStatusApi.Status.APP_OPENED)
 *
 * All calls are async (OkHttp enqueue). Silently skips when orderId or token are missing.
 */
object TapToPayStatusApi {

    private const val TAG = "TapToPayStatusApi"

    object Status {
        const val APP_OPENED           = "app_opened"
        const val APP_STARTED          = "app_started"
        const val DEVICE_NOT_CONNECTED = "device_not_connected"
        const val DEVICE_CONNECTED     = "device_connected"
        const val PAYMENT_WAITING      = "payment_waiting"
        const val PAYMENT_SUCCESS      = "payment_success"
        const val PAYMENT_FAILED       = "payment_failed"
        const val APP_CLOSED           = "app_closed"
    }

    private val client = OkHttpClient()
    private val JSON   = "application/json; charset=utf-8".toMediaType()

    /**
     * Send a tap-to-pay status update. No-ops silently when [orderId] or [token] are blank.
     *
     * @param orderId  The order UUID from the deep link `id` parameter.
     * @param token    The Bearer JWT from the deep link `token` parameter.
     * @param status   One of the [Status] constants.
     */
    fun send(orderId: String?, token: String?, status: String) {
        if (orderId.isNullOrEmpty()) {
            Log.w(TAG, "⚠ Skipping '$status': orderId is null/empty")
            return
        }
        if (token.isNullOrEmpty()) {
            Log.w(TAG, "⚠ Skipping '$status': token is null/empty (check deep link has token= param)")
            return
        }

        val baseUrl = BuildConfig.CARWASH_API_URL.trimEnd('/')
        val url     = "$baseUrl/api/order/tap-to-pay-status/$orderId"
        val body    = """{"tap_to_pay_status":"$status"}""".toRequestBody(JSON)

        Log.i(TAG, "→ PUT $url  status=$status")

        val request = Request.Builder()
            .url(url)
            .put(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $token")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "✗ '$status' network error: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val bodyStr = response.body?.string() ?: ""
                response.close()
                if (response.isSuccessful) {
                    Log.i(TAG, "✓ '$status' → HTTP ${response.code}")
                } else {
                    Log.w(TAG, "✗ '$status' → HTTP ${response.code}  body=$bodyStr")
                }
            }
        })
    }
}
