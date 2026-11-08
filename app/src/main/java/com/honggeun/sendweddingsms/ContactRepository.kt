package com.honggeun.sendweddingsms

import android.content.ContentResolver
import android.provider.ContactsContract.CommonDataKinds.Phone
import java.text.Collator
import java.util.Locale

/** 폰 연락처를 읽어서 사람 한 명당 한 줄로 정리한다. */
object ContactRepository {

    data class RawPhone(val contactId: Long, val name: String, val number: String)

    fun load(resolver: ContentResolver): List<ContactEntry> {
        val rows = ArrayList<RawPhone>()
        resolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER),
            null, null, null
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(Phone.CONTACT_ID)
            val nameCol = c.getColumnIndexOrThrow(Phone.DISPLAY_NAME_PRIMARY)
            val numCol = c.getColumnIndexOrThrow(Phone.NUMBER)
            while (c.moveToNext()) {
                val number = c.getString(numCol) ?: continue
                rows.add(RawPhone(c.getLong(idCol), c.getString(nameCol) ?: "", number))
            }
        }
        return build(rows)
    }

    /**
     * 같은 사람의 번호를 묶고(휴대폰 번호를 대표로), 대표 번호가 같은 연락처는
     * 하나로 합친다. 구글 계정과 SIM에 같은 사람이 두 번 저장된 경우가 흔하다.
     */
    fun build(rows: List<RawPhone>): List<ContactEntry> {
        class Group(var name: String, val numbers: LinkedHashSet<String>)

        val groups = LinkedHashMap<Long, Group>()
        for (r in rows) {
            val norm = PhoneUtil.normalize(r.number)
            if (norm.isEmpty()) continue
            val g = groups.getOrPut(r.contactId) { Group(r.name.trim(), LinkedHashSet()) }
            if (g.name.isEmpty()) g.name = r.name.trim()
            g.numbers.add(norm)
        }

        val byKey = LinkedHashMap<String, ContactEntry>()
        for (g in groups.values) {
            val ordered = g.numbers.filter { PhoneUtil.isMobile(it) } + g.numbers.filterNot { PhoneUtil.isMobile(it) }
            val primary = ordered.first()
            val key = PhoneUtil.digits(primary)
            val existing = byKey[key]
            if (existing != null) {
                for (n in ordered.drop(1)) {
                    if (n != existing.phone && n !in existing.otherPhones) existing.otherPhones.add(n)
                }
                continue
            }
            byKey[key] = ContactEntry(
                key = key,
                name = g.name.ifEmpty { "(이름 없음)" },
                phone = primary,
                otherPhones = ordered.drop(1).toMutableList(),
            )
        }

        val collator = Collator.getInstance(Locale.KOREAN)
        return byKey.values.sortedWith { a, b -> collator.compare(a.name, b.name) }
    }
}
