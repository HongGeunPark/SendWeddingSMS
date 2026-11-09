package com.honggeun.sendweddingsms

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import kotlin.concurrent.thread

/**
 * 3단계: 고른 사람마다 실제로 갈 문자를 미리 보여 준다.
 * 카드를 눌러 그 사람 문구만 고치거나 뺄 수 있고, 다 되면 한꺼번에 보낸다.
 * 다 보내면 처음(문구 쓰기)으로 돌아가 새로 시작한다.
 */
class ReviewActivity : AppCompatActivity() {

    private lateinit var store: AppStore
    private lateinit var countText: TextView
    private lateinit var sendButton: Button
    private val adapter = ReviewAdapter()

    private var template = ""
    private var link = ""
    private var custom: MutableMap<String, String> = mutableMapOf()
    private var people: List<ContactEntry> = emptyList()
    private var sender: AutoSender? = null

    private val smsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) confirmSend() else showSmsPermissionHelp()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_review)
        store = AppStore(this)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        findViewById<TextView>(R.id.stepText).text = "3단계 / 3  ·  확인하고 보내기"
        countText = findViewById(R.id.count)
        sendButton = findViewById(R.id.sendAll)

        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        sendButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                confirmSend()
            } else {
                smsPermission.launch(Manifest.permission.SEND_SMS)
            }
        }

        if (ContactCache.entries.isEmpty()) {
            thread {
                try {
                    ContactCache.loadIfNeeded(this)
                } catch (e: Exception) {
                    // 권한이 없으면 빈 목록
                }
                runOnUiThread { reload() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        template = store.draftMessage
        link = store.link
        custom = store.draftCustom.toMutableMap()
        val chosen = store.draftSelected
        people = ContactCache.entries.filter { it.key in chosen }
        adapter.notifyDataSetChanged()
        countText.text = "${people.size}명에게 보내요. 카드를 누르면 그 사람 문구만 고칠 수 있어요."
        sendButton.text = "${people.size}명에게 모두 보내기"
        sendButton.isEnabled = people.isNotEmpty() && sender == null
    }

    private fun messageOf(e: ContactEntry) = MessageRenderer.messageFor(e, template, custom[e.key], link)

    private fun edit(e: ContactEntry) {
        val view = layoutInflater.inflate(R.layout.dialog_person, null)
        val callName = view.findViewById<EditText>(R.id.callName)
        val text = view.findViewById<EditText>(R.id.customMessage)
        val preview = view.findViewById<TextView>(R.id.preview)

        callName.hint = e.name
        callName.setText(e.callName)
        text.setText(custom[e.key] ?: template)

        fun update() {
            val name = callName.text.toString().trim().ifEmpty { e.name }
            preview.text = MessageRenderer.render(text.text.toString(), name, link)
        }
        callName.doAfterTextChanged { update() }
        text.doAfterTextChanged { update() }
        view.findViewById<Button>(R.id.copyFormat).setOnClickListener { text.setText(template) }
        PlaceholderButtons.bind(view, text)
        update()

        AlertDialog.Builder(this)
            .setTitle("${e.name}님께 보낼 문자")
            .setView(view)
            .setPositiveButton("저장") { _, _ ->
                e.callName = callName.text.toString().trim()
                store.saveContacts(listOf(e))
                val t = text.text.toString().trim()
                if (t.isEmpty() || t == template.trim()) custom.remove(e.key) else custom[e.key] = t
                store.draftCustom = custom
                reload()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 이번 보낼 목록에서만 지운다. 폰 연락처는 건드리지 않는다. */
    private fun remove(e: ContactEntry) {
        val savedCustom = custom[e.key]
        store.draftSelected = store.draftSelected - e.key
        custom.remove(e.key)
        store.draftCustom = custom
        reload()
        Snackbar.make(findViewById(android.R.id.content), "${e.name} 삭제했어요", Snackbar.LENGTH_LONG)
            .setAction("되돌리기") {
                store.draftSelected = store.draftSelected + e.key
                if (savedCustom != null) custom[e.key] = savedCustom
                store.draftCustom = custom
                reload()
            }
            .show()
    }

    private fun showSmsPermissionHelp() {
        AlertDialog.Builder(this)
            .setTitle("문자 권한이 필요해요")
            .setMessage(R.string.restricted_help)
            .setPositiveButton("설정 열기") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun confirmSend() {
        if (sender != null || people.isEmpty()) return
        if (link.isBlank()) {
            AlertDialog.Builder(this)
                .setMessage("청첩장 주소가 비어 있어요. 그래도 보낼까요?")
                .setPositiveButton("그냥 보내기") { _, _ -> askFinal() }
                .setNegativeButton("주소 넣으러 가기") { _, _ -> goToStart(reset = false) }
                .show()
        } else {
            askFinal()
        }
    }

    private fun askFinal() {
        val minutes = Math.ceil(people.size * (AutoSender.DELAY_MS / 1000.0 + 1) / 60).toInt().coerceAtLeast(1)
        val already = people.count { it.sentAt > 0 }
        val note = if (already > 0) "\n이 중 ${already}명은 전에 보낸 적이 있어요." else ""
        AlertDialog.Builder(this)
            .setTitle("${people.size}명에게 보낼까요?")
            .setMessage("한 명씩 약 3초 간격으로 보내서 ${minutes}분쯤 걸려요. 보내는 동안 이 화면을 켜 두세요.$note\n보낸 문자는 되돌릴 수 없어요.")
            .setPositiveButton("보내기") { _, _ -> runSend() }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun runSend() {
        val targets = people.toList()
        val pairs = targets.map { it to messageOf(it) }
        val s = AutoSender(this)
        sender = s
        sendButton.isEnabled = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val progress = AlertDialog.Builder(this)
            .setTitle("보내는 중")
            .setMessage("준비 중…")
            .setCancelable(false)
            .setNegativeButton("중지", null)
            .create()
        progress.setOnShowListener {
            progress.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                s.cancel()
                progress.setMessage("지금 보내는 문자까지만 보내고 멈춰요…")
            }
        }
        progress.show()

        val sentKeys = HashSet<String>()
        s.start(
            pairs,
            onProgress = { i, total, e -> progress.setMessage("${i + 1} / $total\n${e.name}님께 보내는 중…\n\n보냄 ${sentKeys.size}명") },
            onResult = { e, ok ->
                if (ok) {
                    sentKeys.add(e.key)
                    e.sentAt = System.currentTimeMillis()
                    e.lastMessage = pairs.first { it.first.key == e.key }.second
                    store.saveContacts(listOf(e))
                }
            },
            onFinish = { sent, failed, stopped ->
                sender = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                // 보낸 사람은 이번 목록에서 빼서, 다시 보내면 못 보낸 사람만 가게 한다.
                store.draftSelected = store.draftSelected - sentKeys
                if (!isFinishing && !isDestroyed) showResult(progress, sent, failed, stopped)
            },
        )
    }

    private fun showResult(progress: AlertDialog, sent: Int, failed: List<ContactEntry>, stopped: Boolean) {
                progress.dismiss()
                reload()
                if (!stopped && failed.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle("${sent}명에게 전송했습니다")
                        .setMessage("이번 문구는 끝났어요. 다른 사람들에게 다른 문구로 보내려면 새로 시작하세요.")
                        .setCancelable(false)
                        .setPositiveButton("새로 시작") { _, _ -> goToStart(reset = true) }
                        .show()
                } else {
                    val msg = buildString {
                        append(if (stopped) "중지했어요.\n" else "")
                        append("보냄 ${sent}명")
                        if (failed.isNotEmpty()) {
                            append(", 실패 ${failed.size}명\n\n실패: ")
                            append(failed.joinToString(", ") { it.name })
                        }
                        append("\n\n못 보낸 사람은 목록에 남아 있어요. 다시 \"모두 보내기\"를 누르면 그 사람들에게만 보내요.")
                    }
                    AlertDialog.Builder(this).setTitle("보내기 결과").setMessage(msg).setPositiveButton("확인", null).show()
                }
    }

    /** 처음 화면(문구 쓰기)으로 돌아간다. reset이면 이번 회차를 지우고 빈 칸으로 시작한다. */
    private fun goToStart(reset: Boolean) {
        if (reset) store.resetRound()
        startActivity(
            Intent(this, ComposeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    override fun onDestroy() {
        sender?.cancel()
        super.onDestroy()
    }

    private inner class ReviewAdapter : RecyclerView.Adapter<ReviewAdapter.Holder>() {
        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.name)
            val meta: TextView = view.findViewById(R.id.meta)
            val message: TextView = view.findViewById(R.id.message)
            val edit: Button = view.findViewById(R.id.edit)
            val remove: Button = view.findViewById(R.id.remove)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_preview, parent, false))

        override fun getItemCount(): Int = people.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val e = people[position]
            holder.name.text = e.name
            val tags = buildList {
                add(e.phone)
                if (custom.containsKey(e.key)) add("따로 고친 문구")
                if (e.sentAt > 0) add("전에 보낸 적 있음")
            }
            holder.meta.text = tags.joinToString(" · ")
            holder.message.text = messageOf(e)
            holder.itemView.setOnClickListener { edit(e) }
            holder.edit.setOnClickListener { edit(e) }
            holder.remove.setOnClickListener { remove(e) }
        }
    }
}
