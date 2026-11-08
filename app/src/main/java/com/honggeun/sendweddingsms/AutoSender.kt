package com.honggeun.sendweddingsms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 여러 사람에게 문자를 한 명씩 차례로 직접 보낸다.
 * 한 통이 전송 완료(또는 실패)된 걸 확인한 뒤 잠깐 쉬고 다음 사람에게 보낸다.
 * 짧은 시간에 많이 보내면 통신사가 스팸으로 막을 수 있어서 간격을 둔다.
 */
class AutoSender(context: Context) {

    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean(false)

    fun cancel() = cancelled.set(true)

    fun start(
        targets: List<Pair<ContactEntry, String>>,
        onProgress: (index: Int, total: Int, entry: ContactEntry) -> Unit,
        onResult: (entry: ContactEntry, ok: Boolean) -> Unit,
        onFinish: (sent: Int, failed: List<ContactEntry>, stopped: Boolean) -> Unit,
    ) {
        thread {
            var sent = 0
            val failed = ArrayList<ContactEntry>()
            for ((i, pair) in targets.withIndex()) {
                if (cancelled.get()) break
                val (entry, body) = pair
                main.post { onProgress(i, targets.size, entry) }
                val ok = sendOne(PhoneUtil.digits(entry.phone), body)
                if (ok) sent++ else failed.add(entry)
                main.post { onResult(entry, ok) }
                if (i < targets.size - 1 && !cancelled.get()) Thread.sleep(DELAY_MS)
            }
            val stopped = cancelled.get()
            main.post { onFinish(sent, failed, stopped) }
        }
    }

    private fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

    private fun sendOne(dest: String, body: String): Boolean {
        if (dest.isEmpty() || body.isEmpty()) return false
        val action = "${context.packageName}.SMS_SENT.${System.nanoTime()}"
        val ok = AtomicBoolean(true)
        return try {
            val sms = smsManager()
            val parts = sms.divideMessage(body)
            val latch = CountDownLatch(parts.size)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    if (resultCode != Activity.RESULT_OK) ok.set(false)
                    latch.countDown()
                }
            }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
            try {
                val sentIntents = ArrayList<PendingIntent>()
                for (i in parts.indices) {
                    sentIntents.add(
                        PendingIntent.getBroadcast(
                            context, i, Intent(action).setPackage(context.packageName),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
                        )
                    )
                }
                if (parts.size <= 1) {
                    sms.sendTextMessage(dest, null, body, sentIntents.firstOrNull(), null)
                } else {
                    sms.sendMultipartTextMessage(dest, null, parts, sentIntents, null)
                }
                latch.await(TIMEOUT_S, TimeUnit.SECONDS) && ok.get()
            } finally {
                context.unregisterReceiver(receiver)
            }
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        const val DELAY_MS = 3000L
        const val TIMEOUT_S = 60L
    }
}
