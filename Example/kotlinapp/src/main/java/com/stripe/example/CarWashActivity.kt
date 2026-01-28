package com.stripe.example

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent

class CarWashActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CarWashActivity"
        private const val CAR_WASH_URL = "https://carwash.way.com"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Open the URL in Chrome Custom Tabs
        openUrlInCustomTab(CAR_WASH_URL)
        
        // Finish the activity after opening the URL
        // The user will interact with the browser tab
        finish()
    }

    /**
     * Open URL in Chrome Custom Tabs
     */
    private fun openUrlInCustomTab(url: String) {
        try {
            val builder = CustomTabsIntent.Builder()
            builder.setShowTitle(true)
            builder.setStartAnimations(this, android.R.anim.fade_in, android.R.anim.fade_out)
            builder.setExitAnimations(this, android.R.anim.fade_in, android.R.anim.fade_out)
            
            val customTabsIntent = builder.build()
            customTabsIntent.launchUrl(this, Uri.parse(url))
        } catch (e: Exception) {
            Log.e(TAG, "Error opening URL in custom tab: $url", e)
            // Fallback to regular browser intent
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        }
    }
}
