package com.honggeun.sendweddingsms

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class ContactAdapter(
    private val onToggle: (ContactEntry) -> Unit,
    private val onRelation: (ContactEntry) -> Unit,
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
        val relation: MaterialButton = view.findViewById(R.id.relation)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_contact, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val e = items[position]
        holder.check.isChecked = e.selected
        holder.name.text = e.name
        holder.phone.text = if (e.otherPhones.isEmpty()) e.phone else "${e.phone}  (외 ${e.otherPhones.size}개)"
        holder.relation.text = e.relation.ifEmpty { holder.itemView.context.getString(R.string.relation_none) }
        holder.itemView.setOnClickListener { onToggle(e) }
        holder.relation.setOnClickListener { onRelation(e) }
    }
}
