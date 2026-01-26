package com.stripe.example.model

/**
 * PaymentIntentCreationResponse data model from backend
 * The backend creates the PaymentIntent and returns the intent ID and client secret
 */
data class PaymentIntentCreationResponse(
    val intent: String,
    val secret: String
)
