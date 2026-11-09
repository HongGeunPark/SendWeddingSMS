package com.honggeun.sendweddingsms

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 사람별 선택·형식·문구·보낸 기록, 형식 목록, 청첩장 주소를 폰에 저장한다.
 * 앱을 껐다 켜도 이어서 작업할 수 있다.
 */
class AppStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("selection", Context.MODE_PRIVATE)

    fun restore(entries: List<ContactEntry>) {
        for (e in entries) {
            e.selected = prefs.getBoolean("sel_${e.key}", false)
            e.format = prefs.getString("fmt_${e.key}", "") ?: ""
            e.callName = prefs.getString("call_${e.key}", "") ?: ""
            e.customMessage = prefs.getString("msg_${e.key}", "") ?: ""
            e.sentAt = prefs.getLong("sent_${e.key}", 0L)
        }
    }

    fun save(entries: Collection<ContactEntry>) {
        val edit = prefs.edit()
        for (e in entries) {
            edit.putBoolean("sel_${e.key}", e.selected)
            edit.putString("fmt_${e.key}", e.format)
            edit.putString("call_${e.key}", e.callName)
            edit.putString("msg_${e.key}", e.customMessage)
            edit.putLong("sent_${e.key}", e.sentAt)
        }
        edit.apply()
    }

    var link: String
        get() = prefs.getString("link", "") ?: ""
        set(value) = prefs.edit().putString("link", value.trim()).apply()

    /** 미리보기 화면에 "한꺼번에 보내기" 버튼을 보일지. 기본은 보임. */
    var bulkSend: Boolean
        get() = prefs.getBoolean("bulk_send", true)
        set(value) = prefs.edit().putBoolean("bulk_send", value).apply()

    fun formats(): List<MessageFormat> {
        val raw = prefs.getString("formats", null) ?: return MessageRenderer.DEFAULT_FORMATS
        return try {
            val arr = JSONArray(raw)
            val list = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                MessageFormat(o.getString("name"), o.getString("text"))
            }
            list.ifEmpty { MessageRenderer.DEFAULT_FORMATS }
        } catch (e: Exception) {
            MessageRenderer.DEFAULT_FORMATS
        }
    }

    fun saveFormats(list: List<MessageFormat>) {
        val arr = JSONArray()
        for (f in list) arr.put(JSONObject().put("name", f.name).put("text", f.text))
        prefs.edit().putString("formats", arr.toString()).apply()
    }

    /** 형식 이름을 바꾸거나 지울 때, 그 형식을 쓰던 사람들도 함께 바꾼다. */
    fun replaceFormatInContacts(old: String, new: String) {
        val edit = prefs.edit()
        for ((k, v) in prefs.all) {
            if (k.startsWith("fmt_") && v == old) edit.putString(k, new)
        }
        edit.apply()
        ContactCache.entries.forEach { if (it.format == old) it.format = new }
    }
}
