package com.honggeun.sendweddingsms

import android.view.View
import android.widget.Button
import android.widget.EditText

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
