package mx.quili.smartprojector.sender.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import mx.quili.smartprojector.sender.core.DiscoveredReceiver
import mx.quili.smartprojector.sender.databinding.ItemReceiverBinding

/** Lists the receivers discovered on the network. */
class ReceiverAdapter(
    private val onSelect: (DiscoveredReceiver) -> Unit,
) : ListAdapter<DiscoveredReceiver, ReceiverAdapter.ViewHolder>(DIFF) {

    class ViewHolder(val binding: ItemReceiverBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return ViewHolder(ItemReceiverBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val receiver = getItem(position)
        holder.binding.name.text = receiver.name
        holder.binding.address.text = "${receiver.host}:${receiver.port}"
        holder.binding.root.setOnClickListener { onSelect(receiver) }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<DiscoveredReceiver>() {
            override fun areItemsTheSame(old: DiscoveredReceiver, new: DiscoveredReceiver): Boolean =
                old.key == new.key

            override fun areContentsTheSame(old: DiscoveredReceiver, new: DiscoveredReceiver): Boolean =
                old == new
        }
    }
}
