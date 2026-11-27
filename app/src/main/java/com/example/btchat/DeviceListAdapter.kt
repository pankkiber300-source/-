package com.example.btchat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class DeviceEntry(
    val name: String,
    val address: String,
    val paired: Boolean
)

class DeviceListAdapter(
    private val onClick: (DeviceEntry) -> Unit
) : RecyclerView.Adapter<DeviceListAdapter.VH>() {

    private val items = mutableListOf<DeviceEntry>()

    fun submit(newItems: List<DeviceEntry>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun addIfAbsent(entry: DeviceEntry) {
        if (items.none { it.address == entry.address }) {
            items.add(entry)
            notifyItemInserted(items.size - 1)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = if (item.paired) "${item.name} (сопряжено)" else item.name
        holder.subtitle.text = item.address
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(android.R.id.text1)
        val subtitle: TextView = view.findViewById(android.R.id.text2)
    }
}
