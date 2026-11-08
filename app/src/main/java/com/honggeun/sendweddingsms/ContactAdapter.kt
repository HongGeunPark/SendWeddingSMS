package com.honggeun.sendweddingsms

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class ContactAdapter(
    private val formatLabel: (ContactEntry) -> String,
    private val onToggle: (ContactEntry) -> Unit,
    private val onEdit: (ContactEntry) -> Unit,
) : RecyclerView.Adapter<ContactAdapter.Holder>() {

    private var items: List<ContactEntry> = emptyList()

    fun submit(list: List<ContactEntry>) {
        items = list
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val check: CheckBox = view.findViewById(R.id.check)
        val name: TextView = view.findViewById(R.id.name)
        val phone: TextView = view.findViewById(R.id.phone)
        val edit: MaterialButton = view.findViewById(R.id.relation)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_contact, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val e = items[position]
        holder.check.isChecked = e.selected
        holder.name.text = if (e.callName.isNotEmpty() && e.callName != e.name) "${e.name} (${e.callName})" else e.name
        val phone = if (e.otherPhones.isEmpty()) e.phone else "${e.phone}  (외 ${e.otherPhones.size}개)"
        holder.phone.text = if (e.sentAt > 0) "$phone  · 보냄 ✓" else phone
        holder.edit.text = formatLabel(e) + if (e.customMessage.isNotBlank()) " ✎" else ""
        holder.itemView.setOnClickListener { onToggle(e) }
        holder.edit.setOnClickListener { onEdit(e) }
    }
}
