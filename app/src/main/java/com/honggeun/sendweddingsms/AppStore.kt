package com.honggeun.sendweddingsms

import android.content.Context
import org.json.JSONObject

/**
 * 폰에 저장하는 것들.
 * - 오래 기억: 청첩장 주소, 사람별 부를 이름, 보낸 기록
 * - 이번 회차만: 쓰고 있는 문구, 고른 사람, 사람별로 고친 문구 (다 보내면 지워져서 새로 시작)
 * 앱이 중간에 꺼져도 이번 회차를 이어서 할 수 있다.
 */
class AppStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("selection", Context.MODE_PRIVATE)

    fun restore(entries: List<ContactEntry>) {
        for (e in entries) {
            e.callName = prefs.getString("call_${e.key}", "") ?: ""
            e.sentAt = prefs.getLong("sent_${e.key}", 0L)
            e.lastMessage = prefs.getString("last_${e.key}", "") ?: ""
        }
    }

    fun saveContacts(entries: Collection<ContactEntry>) {
        val edit = prefs.edit()
        for (e in entries) {
            edit.putString("call_${e.key}", e.callName)
            edit.putLong("sent_${e.key}", e.sentAt)
            edit.putString("last_${e.key}", e.lastMessage)
        }
        edit.apply()
    }

    var link: String
        get() = prefs.getString("link", "") ?: ""
        set(value) = prefs.edit().putString("link", value.trim()).apply()

    // ---- 이번 회차 ----

    var draftMessage: String
        get() = prefs.getString("draft_msg", "") ?: ""
        set(value) = prefs.edit().putString("draft_msg", value).apply()

    var draftSelected: Set<String>
        get() = prefs.getStringSet("draft_sel", emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet("draft_sel", HashSet(value)).apply()

    var draftCustom: Map<String, String>
        get() {
            val raw = prefs.getString("draft_custom", null) ?: return emptyMap()
            return try {
                val o = JSONObject(raw)
                o.keys().asSequence().associateWith { o.getString(it) }
            } catch (e: Exception) {
                emptyMap()
            }
        }
        set(value) {
            val o = JSONObject()
            for ((k, v) in value) o.put(k, v)
            prefs.edit().putString("draft_custom", o.toString()).apply()
        }

    /** 다 보낸 뒤 새로 시작할 때 이번 회차 내용을 지운다. 청첩장 주소와 보낸 기록은 남긴다. */
    fun resetRound() {
        prefs.edit().remove("draft_msg").remove("draft_sel").remove("draft_custom").apply()
    }
}
