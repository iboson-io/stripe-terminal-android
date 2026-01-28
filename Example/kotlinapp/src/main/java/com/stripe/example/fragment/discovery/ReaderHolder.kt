package com.stripe.example.fragment.discovery

import android.view.View
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.stripe.example.R
import com.stripe.example.databinding.ListItemCardBinding
import com.stripe.stripeterminal.external.models.Location
import com.stripe.stripeterminal.external.models.Reader

/**
 * A simple [RecyclerView.ViewHolder] that also acts as a [View.OnClickListener] to allow for
 * selecting a reader.
 */
class ReaderHolder(
    parent: View,
    private val clickListener: ReaderClickListener,
) : RecyclerView.ViewHolder(parent) {
    private val binding = ListItemCardBinding.bind(parent)
    private val resources = parent.resources
    private val context = parent.context

    fun bind(reader: Reader, locationSelection: Location?, isConnecting: Boolean) {
        val readerIdentifier = reader.serialNumber
            ?: reader.id
            ?: resources.getString(R.string.discovery_reader_unknown)
        
        // Set device name (reader serial number or ID)
        binding.listItemCardTitle.text = readerIdentifier
        
        // Set device ID (location or device type)
        val deviceInfo = reader.location?.displayName
            ?: reader.deviceType?.toString()
            ?: ""
        binding.listItemCardDescription.text = deviceInfo
        
        // Handle connecting state
        val cardView = binding.listItemCard as CardView
        
        if (isConnecting) {
            // Connecting state - keep white background, show loader
            cardView.setCardBackgroundColor(ContextCompat.getColor(context, R.color.backgroundWhite))
            binding.listItemCardTitle.setTextColor(ContextCompat.getColor(context, R.color.textPrimary))
            binding.listItemCardDescription.setTextColor(ContextCompat.getColor(context, R.color.textTertiary))
            binding.deviceIcon.setColorFilter(ContextCompat.getColor(context, R.color.textTertiary))
            // Show the loader spinner
            binding.listItemCardSpinner.visibility = View.VISIBLE
        } else {
            // Normal state - white background
            cardView.setCardBackgroundColor(ContextCompat.getColor(context, R.color.backgroundWhite))
            binding.listItemCardTitle.setTextColor(ContextCompat.getColor(context, R.color.textPrimary))
            binding.listItemCardDescription.setTextColor(ContextCompat.getColor(context, R.color.textTertiary))
            binding.deviceIcon.setColorFilter(ContextCompat.getColor(context, R.color.textTertiary))
            // Hide spinner
            binding.listItemCardSpinner.visibility = View.GONE
        }
        
        // Set click listener on the entire card
        binding.listItemCard.isClickable = !isConnecting
        binding.listItemCard.setOnClickListener {
            if (!isConnecting) {
                clickListener.onClick(reader)
            }
        }
    }
}
