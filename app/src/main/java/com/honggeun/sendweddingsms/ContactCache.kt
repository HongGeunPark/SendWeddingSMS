package com.honggeun.sendweddingsms

import android.content.Context

/** 화면끼리 같은 연락처 목록을 같이 쓴다. */
object ContactCache {
    @Volatile
    var entries: List<ContactEntry> = emptyList()

    /** 백그라운드 스레드에서 부른다. */
    fun loadIfNeeded(context: Context): List<ContactEntry> {
        if (entries.isEmpty()) {
            val loaded = ContactRepository.load(context.contentResolver)
            AppStore(context).restore(loaded)
            entries = loaded
        }
        return entries
    }
}
