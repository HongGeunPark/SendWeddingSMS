package com.honggeun.sendweddingsms

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** 문자 앱을 열어 받는 사람과 내용을 채워 둔다. 전송 버튼은 문자 앱에서 직접 누른다. */
object Sms {
    fun open(context: Context, phone: String, body: String): Boolean {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + PhoneUtil.digits(phone))).apply {
            putExtra("sms_body", body)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "문자 앱을 찾지 못했어요", Toast.LENGTH_LONG).show()
            false
        }
    }
}
