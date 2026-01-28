package com.stripe.example.fragment.discovery

import android.view.View
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

    fun bind(reader: Reader, locationSelection: Location?, isConnecting: Boolean) {
        binding.listItemCardTitle.text = reader.serialNumber
            ?: reader.id
            ?: resources.getString(R.string.discovery_reader_unknown)
        // For M2 readers, location will be set from gradle.properties during connection
        // Show reader's location if available, otherwise show device type
        binding.listItemCardDescription.text = reader.location?.displayName
            ?: reader.deviceType?.toString()
            ?: ""
        
        // Show/hide spinner based on connecting state
        binding.listItemCardSpinner.visibility = if (isConnecting) View.VISIBLE else View.GONE
        
        // Disable click when connecting
        binding.listItemCard.isClickable = !isConnecting
        binding.listItemCard.setOnClickListener {
            if (!isConnecting) {
                clickListener.onClick(reader)
            }
        }
    }
}
