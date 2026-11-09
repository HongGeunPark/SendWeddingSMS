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
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.snackbar.Snackbar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** 2단계: 폰 연락처를 불러와서 이번에 보낼 사람을 고른다. */
class SelectActivity : AppCompatActivity() {

    private enum class Filter { ALL, CHOSEN, NEVER_SENT }

    private lateinit var store: AppStore
    private lateinit var searchInput: EditText
    private lateinit var countText: TextView
    private lateinit var emptyBox: View
    private lateinit var emptyText: TextView
    private lateinit var permissionButton: Button
    private lateinit var list: RecyclerView
    private lateinit var nextButton: Button
    private val adapter = PickAdapter()

    private val chosen = LinkedHashSet<String>()
    private var visible: List<ContactEntry> = emptyList()
    private var filter = Filter.ALL
    private var askedOnce = false
    private var loading = false
    private val dateFmt = SimpleDateFormat("M/d", Locale.KOREA)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) loadContacts() else showNeedPermission()
        }

    private val createDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument(XLSX_MIME)) { uri ->
            if (uri != null) writeExcel(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_select)
        store = AppStore(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.select)
        toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_export) startExport()
            true
        }
        findViewById<TextView>(R.id.stepText).text = "2단계 / 3  ·  받는 사람 고르기"

        searchInput = findViewById(R.id.search)
        countText = findViewById(R.id.count)
        emptyBox = findViewById(R.id.emptyBox)
        emptyText = findViewById(R.id.emptyText)
        permissionButton = findViewById(R.id.permissionButton)
        list = findViewById(R.id.list)
        nextButton = findViewById(R.id.next)

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        searchInput.doAfterTextChanged { refresh() }
        findViewById<MaterialButtonToggleGroup>(R.id.filterGroup).addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            filter = when (id) {
                R.id.filterChosen -> Filter.CHOSEN
                R.id.filterNeverSent -> Filter.NEVER_SENT
                else -> Filter.ALL
            }
            refresh()
        }
        findViewById<Button>(R.id.selectVisible).setOnClickListener { setVisible(true) }
        findViewById<Button>(R.id.clearVisible).setOnClickListener { setVisible(false) }
        permissionButton.setOnClickListener { requestOrOpenSettings() }
        nextButton.setOnClickListener {
            if (chosen.isEmpty()) {
                Toast.makeText(this, "보낼 사람을 한 명 이상 골라 주세요", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this, ReviewActivity::class.java))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 3단계에서 사람을 뺐을 수 있으니 매번 다시 읽는다.
        chosen.clear()
        chosen.addAll(store.draftSelected)
        if (hasPermission()) {
            if (ContactCache.entries.isEmpty()) loadContacts() else refresh()
        } else if (!askedOnce) {
            askedOnce = true
            permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        } else {
            showNeedPermission()
        }
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun requestOrOpenSettings() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS) || !askedOnce) {
            askedOnce = true
            permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        } else {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    private fun showNeedPermission() {
        list.visibility = View.GONE
        emptyBox.visibility = View.VISIBLE
        permissionButton.visibility = View.VISIBLE
        emptyText.setText(R.string.permission_denied)
    }

    private fun loadContacts() {
        if (loading) return
        loading = true
        list.visibility = View.GONE
        emptyBox.visibility = View.VISIBLE
        permissionButton.visibility = View.GONE
        emptyText.setText(R.string.loading)
        thread {
            try {
                ContactCache.loadIfNeeded(this)
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, getString(R.string.load_failed, e.message), Toast.LENGTH_LONG).show() }
            }
            runOnUiThread {
                loading = false
                refresh()
            }
        }
    }

    private fun refresh() {
        val all = ContactCache.entries
        val q = searchInput.text?.toString() ?: ""
        visible = all.filter { e ->
            val pass = when (filter) {
                Filter.ALL -> true
                Filter.CHOSEN -> e.key in chosen
                Filter.NEVER_SENT -> e.sentAt == 0L
            }
            pass && e.matches(q)
        }
        adapter.notifyDataSetChanged()
        updateCounts()
        if (all.isEmpty()) {
            if (!loading && hasPermission()) {
                list.visibility = View.GONE
                emptyBox.visibility = View.VISIBLE
                permissionButton.visibility = View.GONE
                emptyText.setText(R.string.no_contacts)
            }
        } else {
            list.visibility = View.VISIBLE
            emptyBox.visibility = View.GONE
        }
    }

    private fun updateCounts() {
        countText.text = "고른 사람 ${chosen.size}명 / 전체 ${ContactCache.entries.size}명 (화면에 ${visible.size}명)"
        nextButton.text = "다음: 미리보기 (${chosen.size}명)"
        nextButton.isEnabled = chosen.isNotEmpty()
    }

    private fun toggle(e: ContactEntry) {
        if (!chosen.add(e.key)) chosen.remove(e.key)
        store.draftSelected = chosen
    }

    private fun setVisible(value: Boolean) {
        val targets = visible.filter { (it.key in chosen) != value }
        if (targets.isEmpty()) return
        val apply = {
            for (e in targets) if (value) chosen.add(e.key) else chosen.remove(e.key)
            store.draftSelected = chosen
            refresh()
        }
        if (targets.size < 10) {
            apply()
        } else {
            val msg = getString(if (value) R.string.confirm_select_many else R.string.confirm_clear_many, targets.size)
            AlertDialog.Builder(this)
                .setMessage(msg)
                .setPositiveButton(R.string.ok) { _, _ -> apply() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun startExport() {
        if (ContactCache.entries.isEmpty()) return
        val date = SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(Date())
        createDocument.launch("청첩장_연락처_$date.xlsx")
    }

    private fun writeExcel(uri: Uri) {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA)
        val headers = listOf("순번", "이름", "전화번호", "기타 번호", "이번에 고름", "보낸 시각", "마지막으로 보낸 문자")
        val rows = ContactCache.entries.mapIndexed { i, e ->
            listOf(
                i + 1, e.name, e.phone, e.otherPhones.joinToString(", "),
                if (e.key in chosen) "O" else "",
                if (e.sentAt > 0) fmt.format(Date(e.sentAt)) else "",
                e.lastMessage,
            )
        }
        thread {
            val error = try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    XlsxWriter.write(out, "연락처", headers, rows, listOf(6.0, 14.0, 16.0, 22.0, 10.0, 17.0, 60.0))
                } ?: throw IllegalStateException("파일을 열 수 없어요")
                null
            } catch (e: Exception) {
                e
            }
            runOnUiThread {
                if (error != null) {
                    Toast.makeText(this, getString(R.string.save_failed, error.message), Toast.LENGTH_LONG).show()
                } else {
                    Snackbar.make(list, getString(R.string.saved, rows.size), Snackbar.LENGTH_LONG)
                        .setAction(R.string.share) {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = XLSX_MIME
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(Intent.createChooser(send, getString(R.string.share)))
                        }
                        .show()
                }
            }
        }
    }

    private inner class PickAdapter : RecyclerView.Adapter<PickAdapter.Holder>() {
        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val check: CheckBox = view.findViewById(R.id.check)
            val name: TextView = view.findViewById(R.id.name)
            val sub: TextView = view.findViewById(R.id.sub)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_member, parent, false))

        override fun getItemCount(): Int = visible.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val e = visible[position]
            holder.check.isChecked = e.key in chosen
            holder.name.text = e.name
            val sent = if (e.sentAt > 0) "  · ${dateFmt.format(Date(e.sentAt))} 보냄 ✓" else ""
            holder.sub.text = e.phone + sent
            holder.itemView.setOnClickListener {
                toggle(e)
                if (filter == Filter.CHOSEN) refresh() else {
                    notifyItemChanged(holder.bindingAdapterPosition)
                    updateCounts()
                }
            }
        }
    }

    companion object {
        const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }
}
