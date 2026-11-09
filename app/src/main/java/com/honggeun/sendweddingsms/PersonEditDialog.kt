package com.honggeun.sendweddingsms

import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged

/** 한 사람의 부를 이름, 보내는 형식, 개별 문구를 고치는 창. */
object PersonEditDialog {

    fun show(activity: AppCompatActivity, entry: ContactEntry, store: AppStore, onSaved: () -> Unit) {
        val formats = store.formats()
        val link = store.link
        val view = activity.layoutInflater.inflate(R.layout.dialog_person, null)
        val callName = view.findViewById<EditText>(R.id.callName)
        val spinner = view.findViewById<Spinner>(R.id.formatSpinner)
        val custom = view.findViewById<EditText>(R.id.customMessage)
        val preview = view.findViewById<TextView>(R.id.preview)

        callName.hint = entry.name
        callName.setText(entry.callName)
        spinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, formats.map { it.name })
        spinner.setSelection(formats.indexOfFirst { it.name == entry.format }.coerceAtLeast(0))
        custom.setText(entry.customMessage)

        fun update() {
            val f = formats.getOrNull(spinner.selectedItemPosition)
            val template = custom.text.toString().ifBlank { f?.text ?: "" }
            val name = callName.text.toString().trim().ifEmpty { entry.name }
            preview.text = MessageRenderer.render(template, name, link)
        }

        callName.doAfterTextChanged { update() }
        custom.doAfterTextChanged { update() }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) = update()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        view.findViewById<Button>(R.id.copyFormat).setOnClickListener {
            custom.setText(formats.getOrNull(spinner.selectedItemPosition)?.text ?: "")
        }
        PlaceholderButtons.bind(view, custom)
        update()

        AlertDialog.Builder(activity)
            .setTitle(entry.name)
            .setView(view)
            .setPositiveButton("저장") { _, _ ->
                entry.callName = callName.text.toString().trim()
                entry.format = formats.getOrNull(spinner.selectedItemPosition)?.name ?: ""
                entry.customMessage = custom.text.toString().trim()
                store.save(listOf(entry))
                onSaved()
            }
            .setNegativeButton("취소", null)
            .show()
    }
}

/** {이름}, {이름아} 버튼을 누르면 커서 위치에 넣는다. */
object PlaceholderButtons {
    fun bind(root: View, target: EditText) {
        val pairs = listOf(
            R.id.insertName to MessageRenderer.NAME,
            R.id.insertNameCall to MessageRenderer.NAME_CALL,
        )
        for ((id, token) in pairs) {
            root.findViewById<Button>(id).setOnClickListener {
                val start = target.selectionStart.coerceAtLeast(0)
                val end = target.selectionEnd.coerceAtLeast(0)
                target.text.replace(minOf(start, end), maxOf(start, end), token)
                target.requestFocus()
            }
        }
    }
}
