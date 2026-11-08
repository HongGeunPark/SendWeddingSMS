package com.honggeun.sendweddingsms

import android.content.Context

/** 체크 상태와 관계를 폰에 저장해서, 앱을 껐다 켜도 이어서 고를 수 있게 한다. */
class SelectionStore(context: Context) {
    private val prefs = context.getSharedPreferences("selection", Context.MODE_PRIVATE)

    fun restore(entries: List<ContactEntry>) {
        for (e in entries) {
            e.selected = prefs.getBoolean("sel_${e.key}", false)
            e.relation = prefs.getString("rel_${e.key}", "") ?: ""
        }
    }

    fun save(entries: Collection<ContactEntry>) {
        val edit = prefs.edit()
        for (e in entries) {
            edit.putBoolean("sel_${e.key}", e.selected)
            edit.putString("rel_${e.key}", e.relation)
        }
        edit.apply()
    }
}
