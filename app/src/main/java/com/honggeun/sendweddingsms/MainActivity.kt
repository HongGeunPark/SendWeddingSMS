package com.honggeun.sendweddingsms

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
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

class MainActivity : AppCompatActivity() {

    private enum class Filter { ALL, SELECTED, UNSELECTED }

    private lateinit var store: AppStore
    private lateinit var adapter: ContactAdapter
    private lateinit var searchInput: EditText
    private lateinit var formatFilter: Spinner
    private lateinit var countText: TextView
    private lateinit var emptyBox: View
    private lateinit var emptyText: TextView
    private lateinit var permissionButton: Button
    private lateinit var list: RecyclerView

    private val all: List<ContactEntry> get() = ContactCache.entries
    private var visible: List<ContactEntry> = emptyList()
    private var formats: List<MessageFormat> = emptyList()
    private var filter = Filter.ALL
    /** null이면 모든 형식. */
    private var formatFilterName: String? = null
    private var pendingExport: List<ContactEntry> = emptyList()
    private var askedOnce = false
    private var loading = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) loadContacts() else showNeedPermission(denied = true)
        }

    private val createDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument(XLSX_MIME)) { uri ->
            if (uri != null) writeExcel(uri, pendingExport)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = AppStore(this)

        searchInput = findViewById(R.id.search)
        formatFilter = findViewById(R.id.formatFilter)
        countText = findViewById(R.id.count)
        emptyBox = findViewById(R.id.emptyBox)
        emptyText = findViewById(R.id.emptyText)
        permissionButton = findViewById(R.id.permissionButton)
        list = findViewById(R.id.list)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.main)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_templates -> startActivity(Intent(this, TemplatesActivity::class.java))
                R.id.action_bulk_format -> askBulkFormat()
                R.id.action_export -> askExport()
            }
            true
        }

        adapter = ContactAdapter(
            formatLabel = { e -> MessageRenderer.formatFor(e, formats).name },
            onToggle = { e ->
                e.selected = !e.selected
                store.save(listOf(e))
                refresh()
            },
            onEdit = { e -> PersonEditDialog.show(this, e, store) { refresh() } },
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        searchInput.doAfterTextChanged { refresh() }

        findViewById<MaterialButtonToggleGroup>(R.id.filterGroup).addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            filter = when (id) {
                R.id.filterSelected -> Filter.SELECTED
                R.id.filterUnselected -> Filter.UNSELECTED
                else -> Filter.ALL
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

        findViewById<Button>(R.id.selectVisible).setOnClickListener { setVisibleSelected(true) }
        findViewById<Button>(R.id.clearVisible).setOnClickListener { setVisibleSelected(false) }
        findViewById<Button>(R.id.export).setOnClickListener { askExport() }
        findViewById<Button>(R.id.preview).setOnClickListener {
            if (all.none { it.selected }) {
                Toast.makeText(this, R.string.nothing_selected, Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this, PreviewActivity::class.java))
            }
        }
        permissionButton.setOnClickListener { requestOrOpenSettings() }
    }

    override fun onResume() {
        super.onResume()
        reloadFormats()
        // 설정 화면에서 권한을 켜고 돌아온 경우도 여기서 처리된다.
        if (hasPermission()) {
            if (all.isEmpty()) loadContacts() else refresh()
        } else if (!askedOnce) {
            askedOnce = true
            permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        } else {
            showNeedPermission(denied = true)
        }
    }

    private fun reloadFormats() {
        formats = store.formats()
        val labels = listOf(getString(R.string.all_formats)) + formats.map { it.name }
        formatFilter.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val idx = formats.indexOfFirst { it.name == formatFilterName }
        if (idx < 0) formatFilterName = null
        formatFilter.setSelection(if (idx < 0) 0 else idx + 1)
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun requestOrOpenSettings() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS) || !askedOnce) {
            askedOnce = true
            permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        } else {
            // "다시 묻지 않음" 상태면 앱 설정 화면으로 보낸다.
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            )
        }
    }

    private fun showNeedPermission(denied: Boolean) {
        list.visibility = View.GONE
        emptyBox.visibility = View.VISIBLE
        permissionButton.visibility = View.VISIBLE
        emptyText.setText(if (denied) R.string.permission_denied else R.string.permission_needed)
        countText.text = ""
    }

    private fun loadContacts() {
        if (loading) return
        loading = true
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
        val q = searchInput.text?.toString() ?: ""
        val fmt = formatFilterName
        visible = all.filter { e ->
            val passFilter = when (filter) {
                Filter.ALL -> true
                Filter.SELECTED -> e.selected
                Filter.UNSELECTED -> !e.selected
            }
            passFilter && (fmt == null || MessageRenderer.formatFor(e, formats).name == fmt) && e.matches(q)
        }
        adapter.submit(visible)

        val selected = all.count { it.selected }
        countText.text = getString(R.string.count_format, selected, all.size, visible.size)

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

    private fun setVisibleSelected(value: Boolean) {
        val targets = visible.filter { it.selected != value }
        if (targets.isEmpty()) return
        val apply = {
            targets.forEach { it.selected = value }
            store.save(targets)
            refresh()
        }
        if (targets.size < 10) {
            apply()
            return
        }
        val msg = getString(if (value) R.string.confirm_select_many else R.string.confirm_clear_many, targets.size)
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton(R.string.ok) { _, _ -> apply() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 검색이나 필터로 추린 사람들의 형식을 한꺼번에 정한다. 예: "○○상사" 검색 → 거래처. */
    private fun askBulkFormat() {
        if (visible.isEmpty()) return
        val names = formats.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.bulk_format_title, visible.size))
            .setItems(names) { _, which ->
                val name = formats[which].name
                visible.forEach { it.format = name }
                store.save(visible)
                refresh()
                Toast.makeText(this, getString(R.string.bulk_format_done, visible.size, name), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askExport() {
        if (all.isEmpty()) return
        val selected = all.filter { it.selected }
        val choices = arrayOf(
            getString(R.string.export_selected, selected.size),
            getString(R.string.export_all, all.size),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.export_title)
            .setItems(choices) { _, which ->
                val target = if (which == 0) selected else all
                if (target.isEmpty()) {
                    Toast.makeText(this, R.string.nothing_selected, Toast.LENGTH_SHORT).show()
                    return@setItems
                }
                pendingExport = target
                val date = SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(Date())
                val suffix = if (which == 0) "보낼사람" else "전체"
                createDocument.launch("청첩장_연락처_${suffix}_$date.xlsx")
            }
            .show()
    }

    private fun writeExcel(uri: Uri, entries: List<ContactEntry>) {
        val link = store.link
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA)
        val headers = listOf("순번", "이름", "전화번호", "기타 번호", "형식", "부를 이름", "보낼지", "보낼 문구", "보낸 시각", "메모")
        val rows = entries.mapIndexed { i, e ->
            listOf(
                i + 1,
                e.name,
                e.phone,
                e.otherPhones.joinToString(", "),
                MessageRenderer.formatFor(e, formats).name,
                e.displayCallName,
                if (e.selected) "O" else "X",
                MessageRenderer.messageFor(e, formats, link),
                if (e.sentAt > 0) dateFmt.format(Date(e.sentAt)) else "",
                "",
            )
        }
        thread {
            val error = try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    XlsxWriter.write(
                        out, "연락처", headers, rows,
                        listOf(6.0, 14.0, 16.0, 22.0, 10.0, 12.0, 8.0, 60.0, 17.0, 20.0),
                    )
                } ?: throw IllegalStateException("파일을 열 수 없어요")
                null
            } catch (e: Exception) {
                e
            }
            runOnUiThread {
                if (error != null) {
                    Toast.makeText(this, getString(R.string.save_failed, error.message), Toast.LENGTH_LONG).show()
                } else {
                    Snackbar.make(list, getString(R.string.saved, entries.size), Snackbar.LENGTH_LONG)
                        .setAction(R.string.share) { share(uri) }
                        .show()
                }
            }
        }
    }

    private fun share(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = XLSX_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    companion object {
        const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }
}
