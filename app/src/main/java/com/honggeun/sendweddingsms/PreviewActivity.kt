package com.honggeun.sendweddingsms

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlin.concurrent.thread

/** 보낼 사람마다 실제로 갈 문자를 보여 주고, 한 명씩 문자 앱으로 보낸다. */
class PreviewActivity : AppCompatActivity() {

    private enum class Status { ALL, UNSENT, SENT }

    private lateinit var store: AppStore
    private lateinit var searchInput: EditText
    private lateinit var formatFilter: Spinner
    private lateinit var countText: TextView
    private lateinit var linkWarning: TextView
    private lateinit var adapter: PreviewAdapter

    private var formats: List<MessageFormat> = emptyList()
    private var link = ""
    private var status = Status.UNSENT
    private var formatFilterName: String? = null
    private var visible: List<ContactEntry> = emptyList()
    private lateinit var autoSendButton: Button
    private var sender: AutoSender? = null

    /** 문자 권한을 받은 뒤 이어서 할 일. */
    private var afterPermission: (() -> Unit)? = null
    /** 권한이 없을 때 문자 앱으로 대신 보낼 사람(한 명 보내기일 때만). */
    private var fallbackEntry: ContactEntry? = null

    private val smsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = afterPermission
            afterPermission = null
            if (granted) action?.invoke() else showSmsPermissionHelp(fallbackEntry)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)
        store = AppStore(this)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        searchInput = findViewById(R.id.search)
        formatFilter = findViewById(R.id.formatFilter)
        countText = findViewById(R.id.count)
        linkWarning = findViewById(R.id.linkWarning)

        linkWarning.setOnClickListener { startActivity(Intent(this, TemplatesActivity::class.java)) }

        adapter = PreviewAdapter(
            message = { e -> MessageRenderer.messageFor(e, formats, link) },
            formatLabel = { e -> MessageRenderer.formatFor(e, formats).name },
            onSend = { e -> send(e) },
            onToggleSent = { e ->
                e.sentAt = if (e.sentAt > 0) 0L else System.currentTimeMillis()
                store.save(listOf(e))
                refresh()
            },
            onEdit = { e -> PersonEditDialog.show(this, e, store) { refresh() } },
        )
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        searchInput.doAfterTextChanged { refresh() }
        findViewById<MaterialButtonToggleGroup>(R.id.statusGroup).addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            status = when (id) {
                R.id.statusSent -> Status.SENT
                R.id.statusAll -> Status.ALL
                else -> Status.UNSENT
            }
            refresh()
        }
        formatFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val name = if (position == 0) null else formats.getOrNull(position - 1)?.name
                if (name != formatFilterName) {
                    formatFilterName = name
                    refresh()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.sendNext).setOnClickListener {
            val next = visible.firstOrNull { it.sentAt == 0L }
            if (next == null) {
                Toast.makeText(this, "이 목록은 모두 보냈어요", Toast.LENGTH_SHORT).show()
            } else {
                send(next)
            }
        }

        autoSendButton = findViewById(R.id.autoSend)
        autoSendButton.setOnClickListener { withSmsPermission(null) { confirmAutoSend() } }

        if (ContactCache.entries.isEmpty()) {
            // 앱이 백그라운드에서 정리된 뒤 다시 열린 경우
            thread {
                try {
                    ContactCache.loadIfNeeded(this)
                } catch (e: Exception) {
                    // 권한이 없으면 빈 목록으로 둔다.
                }
                runOnUiThread { refresh() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        formats = store.formats()
        link = store.link
        linkWarning.visibility = if (link.isBlank()) View.VISIBLE else View.GONE
        autoSendButton.visibility = if (store.bulkSend) View.VISIBLE else View.GONE
        val labels = listOf(getString(R.string.all_formats)) + formats.map { it.name }
        formatFilter.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val idx = formats.indexOfFirst { it.name == formatFilterName }
        if (idx < 0) formatFilterName = null
        formatFilter.setSelection(if (idx < 0) 0 else idx + 1)
        refresh()
    }

    private fun refresh() {
        val q = searchInput.text?.toString() ?: ""
        val fmt = formatFilterName
        val selected = ContactCache.entries.filter { it.selected }
        visible = selected.filter { e ->
            val passStatus = when (status) {
                Status.ALL -> true
                Status.UNSENT -> e.sentAt == 0L
                Status.SENT -> e.sentAt > 0L
            }
            passStatus && (fmt == null || MessageRenderer.formatFor(e, formats).name == fmt) && e.matches(q)
        }
        adapter.submit(visible)
        val sent = selected.count { it.sentAt > 0 }
        countText.text = "보낼 사람 ${selected.size}명 · 보냄 ${sent}명 · 남음 ${selected.size - sent}명"
    }

    private fun withSmsPermission(entry: ContactEntry?, action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            afterPermission = action
            fallbackEntry = entry
            smsPermission.launch(Manifest.permission.SEND_SMS)
        }
    }

    private fun showSmsPermissionHelp(entry: ContactEntry?) {
        val b = AlertDialog.Builder(this)
            .setTitle("문자 권한이 필요해요")
            .setMessage(R.string.restricted_help)
            .setPositiveButton("설정 열기") { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                )
            }
            .setNegativeButton("취소", null)
        if (entry != null) {
            // 권한을 못 풀었을 때를 위해 예전 방식(문자 앱 열기)도 남겨 둔다.
            b.setNeutralButton("문자 앱으로 보내기") { _, _ -> openInSmsApp(entry) }
        }
        b.show()
    }

    /** 지금 화면에 보이는 사람 중 안 보낸 사람에게 자동으로 보낸다. */
    private fun confirmAutoSend() {
        if (sender != null) return
        val targets = visible.filter { it.sentAt == 0L }
        if (targets.isEmpty()) {
            Toast.makeText(this, "이 목록에 안 보낸 사람이 없어요", Toast.LENGTH_SHORT).show()
            return
        }
        if (link.isBlank()) {
            Toast.makeText(this, "청첩장 주소를 먼저 넣어 주세요", Toast.LENGTH_LONG).show()
            return
        }
        val minutes = (targets.size * (AutoSender.DELAY_MS / 1000.0 + 1) / 60).let { if (it < 1) 1 else Math.ceil(it).toInt() }
        if (sender != null) return
        val names = targets.take(5).joinToString(", ") { it.name } + if (targets.size > 5) " 외 ${targets.size - 5}명" else ""
        AlertDialog.Builder(this)
            .setTitle("${targets.size}명에게 자동으로 보낼까요?")
            .setMessage(
                "받는 사람: $names\n\n" +
                    "한 명씩 약 3초 간격으로 보내서 ${minutes}분쯤 걸려요. 보내는 동안 이 화면을 켜 두세요.\n" +
                    "보내기 전에 미리보기에서 문구를 꼭 확인해 주세요. 보낸 문자는 되돌릴 수 없어요."
            )
            .setPositiveButton("보내기") { _, _ -> runAutoSend(targets) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun runAutoSend(targets: List<ContactEntry>) {
        val pairs = targets.map { it to MessageRenderer.messageFor(it, formats, link) }
        val s = AutoSender(this)
        sender = s
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val progress = AlertDialog.Builder(this)
            .setTitle("자동 발송 중")
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

        var sentCount = 0
        s.start(
            pairs,
            onProgress = { i, total, e -> progress.setMessage("${i + 1} / $total\n${e.name}님께 보내는 중…\n\n보냄 ${sentCount}명") },
            onResult = { e, ok ->
                if (ok) {
                    sentCount++
                    e.sentAt = System.currentTimeMillis()
                    store.save(listOf(e))
                }
            },
            onFinish = { sent, failed, stopped ->
                sender = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (!isFinishing && !isDestroyed) {
                    progress.dismiss()
                    refresh()
                    Toast.makeText(this, "${sent}명에게 전송했습니다", Toast.LENGTH_LONG).show()
                }
                if (!isFinishing && !isDestroyed && (stopped || failed.isNotEmpty())) {
                    val msg = buildString {
                        append(if (stopped) "중지했어요.\n" else "다 보냈어요.\n")
                        append("보냄 ${sent}명")
                        if (failed.isNotEmpty()) {
                            append(", 실패 ${failed.size}명\n\n실패: ")
                            append(failed.joinToString(", ") { it.name })
                            append("\n\n실패한 사람은 \"안 보낸 사람\" 목록에 남아 있어요. 다시 자동 발송하거나 한 명씩 보내 주세요.")
                        }
                    }
                    AlertDialog.Builder(this).setTitle("자동 발송 결과").setMessage(msg).setPositiveButton("확인", null).show()
                }
            },
        )
    }

    override fun onDestroy() {
        sender?.cancel()
        super.onDestroy()
    }

    /** 한 명에게 바로 보낸다. 이미 보낸 사람이면 한 번 더 묻는다. */
    private fun send(e: ContactEntry) {
        if (sender != null) {
            Toast.makeText(this, "보내는 중이에요. 잠시만 기다려 주세요", Toast.LENGTH_SHORT).show()
            return
        }
        if (e.sentAt > 0) {
            AlertDialog.Builder(this)
                .setMessage("${e.name}님께는 이미 보냈어요. 한 번 더 보낼까요?")
                .setPositiveButton("보내기") { _, _ -> withSmsPermission(e) { sendNow(e) } }
                .setNegativeButton("취소", null)
                .show()
        } else {
            withSmsPermission(e) { sendNow(e) }
        }
    }

    private fun sendNow(e: ContactEntry) {
        val body = MessageRenderer.messageFor(e, formats, link)
        val s = AutoSender(this)
        sender = s
        Toast.makeText(this, "${e.name}님께 보내는 중…", Toast.LENGTH_SHORT).show()
        s.start(
            listOf(e to body),
            onProgress = { _, _, _ -> },
            onResult = { entry, ok ->
                if (ok) {
                    entry.sentAt = System.currentTimeMillis()
                    store.save(listOf(entry))
                }
                val text = if (ok) "${entry.name}님께 전송했습니다" else "${entry.name}님께 보내지 못했어요. 번호나 신호를 확인해 주세요"
                Toast.makeText(this, text, Toast.LENGTH_LONG).show()
            },
            onFinish = { _, _, _ ->
                sender = null
                if (!isFinishing && !isDestroyed) refresh()
            },
        )
    }

    /** 문자 권한을 쓸 수 없을 때만 쓰는 예전 방식. */
    private fun openInSmsApp(e: ContactEntry) {
        val body = MessageRenderer.messageFor(e, formats, link)
        if (Sms.open(this, e.phone, body)) {
            // 문자 앱에서 실제로 전송했는지는 알 수 없어서, 연 순간 "보냄"으로 표시한다. 잘못 눌렀으면 다시 눌러 취소할 수 있다.
            e.sentAt = System.currentTimeMillis()
            store.save(listOf(e))
        }
    }
}

class PreviewAdapter(
    private val message: (ContactEntry) -> String,
    private val formatLabel: (ContactEntry) -> String,
    private val onSend: (ContactEntry) -> Unit,
    private val onToggleSent: (ContactEntry) -> Unit,
    private val onEdit: (ContactEntry) -> Unit,
) : RecyclerView.Adapter<PreviewAdapter.Holder>() {

    private var items: List<ContactEntry> = emptyList()

    fun submit(list: List<ContactEntry>) {
        items = list
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.name)
        val meta: TextView = view.findViewById(R.id.meta)
        val message: TextView = view.findViewById(R.id.message)
        val edit: Button = view.findViewById(R.id.edit)
        val toggleSent: Button = view.findViewById(R.id.toggleSent)
        val send: Button = view.findViewById(R.id.send)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_preview, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val e = items[position]
        val sent = e.sentAt > 0
        holder.name.text = if (sent) "${e.name}  ✓ 보냄" else e.name
        val custom = if (e.customMessage.isNotBlank()) " · 개별 문구" else ""
        holder.meta.text = "${e.phone} · ${formatLabel(e)}$custom"
        holder.message.text = message(e)
        holder.toggleSent.text = if (sent) "보냄 취소" else "보냄 표시"
        holder.send.text = if (sent) "다시 보내기" else "보내기"
        holder.edit.setOnClickListener { onEdit(e) }
        holder.toggleSent.setOnClickListener { onToggleSent(e) }
        holder.send.setOnClickListener { onSend(e) }
    }
}
