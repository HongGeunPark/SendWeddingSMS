package com.honggeun.sendweddingsms

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlin.concurrent.thread

/**
 * 한 분류(예: 직원)를 고른 뒤, 그 분류에 넣을 사람을 목록에서 한 번에 체크하는 화면.
 * 저장하면 체크한 사람은 이 분류가 되고 보낼 사람으로도 표시된다.
 * 체크를 푼 사람은 기본 분류로 돌아간다.
 */
class FormatMembersActivity : AppCompatActivity() {

    private enum class Filter { ALL, CHECKED, NO_FORMAT }

    private lateinit var store: AppStore
    private lateinit var formatName: String
    private lateinit var formats: List<MessageFormat>
    private lateinit var searchInput: EditText
    private lateinit var countText: TextView
    private lateinit var saveButton: Button
    private val adapter = MemberAdapter()

    /** 화면에서 체크한 사람(키). 저장하기 전까지는 실제 데이터를 바꾸지 않는다. */
    private val checked = HashSet<String>()
    private val initial = HashSet<String>()
    private var filter = Filter.ALL
    private var visible: List<ContactEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_format_members)
        store = AppStore(this)
        formats = store.formats()
        formatName = intent.getStringExtra(EXTRA_FORMAT) ?: formats.first().name

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = "'$formatName' 분류에 넣을 사람"
        toolbar.setNavigationOnClickListener { confirmLeave() }

        searchInput = findViewById(R.id.search)
        countText = findViewById(R.id.count)
        saveButton = findViewById(R.id.save)

        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        searchInput.doAfterTextChanged { refresh() }
        findViewById<MaterialButtonToggleGroup>(R.id.filterGroup).addOnButtonCheckedListener { _, id, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            filter = when (id) {
                R.id.filterChecked -> Filter.CHECKED
                R.id.filterNoFormat -> Filter.NO_FORMAT
                else -> Filter.ALL
            }
            refresh()
        }
        findViewById<Button>(R.id.checkVisible).setOnClickListener {
            visible.forEach { checked.add(it.key) }
            refresh()
        }
        findViewById<Button>(R.id.uncheckVisible).setOnClickListener {
            visible.forEach { checked.remove(it.key) }
            refresh()
        }
        saveButton.setOnClickListener { save() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmLeave()
        })

        thread {
            try {
                ContactCache.loadIfNeeded(this)
            } catch (e: Exception) {
                // 권한이 없으면 빈 목록
            }
            runOnUiThread {
                for (e in ContactCache.entries) {
                    if (e.format == formatName) {
                        checked.add(e.key)
                        initial.add(e.key)
                    }
                }
                refresh()
            }
        }
    }

    private fun changed() = checked != initial

    private fun confirmLeave() {
        if (!changed()) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setMessage("고른 내용을 저장할까요?")
            .setPositiveButton("저장") { _, _ -> save() }
            .setNegativeButton("저장 안 함") { _, _ -> finish() }
            .setNeutralButton("취소", null)
            .show()
    }

    private fun currentFormat(e: ContactEntry): String =
        if (e.format.isEmpty() || formats.none { it.name == e.format }) "" else e.format

    private fun refresh() {
        val q = searchInput.text?.toString() ?: ""
        visible = ContactCache.entries.filter { e ->
            val pass = when (filter) {
                Filter.ALL -> true
                Filter.CHECKED -> e.key in checked
                Filter.NO_FORMAT -> currentFormat(e).isEmpty()
            }
            pass && e.matches(q)
        }
        adapter.notifyDataSetChanged()
        countText.text = "체크 ${checked.size}명 · 화면에 ${visible.size}명"
        saveButton.text = "저장 ('$formatName' ${checked.size}명)"
    }

    private fun save() {
        val touched = ArrayList<ContactEntry>()
        var moved = 0
        for (e in ContactCache.entries) {
            val inNow = e.key in checked
            if (inNow) {
                if (e.format != formatName) {
                    if (currentFormat(e).isNotEmpty()) moved++
                    e.format = formatName
                    touched.add(e)
                }
                if (!e.selected) {
                    e.selected = true
                    if (e !in touched) touched.add(e)
                }
            } else if (e.format == formatName) {
                // 이 분류에서 뺀 사람은 기본 분류로 돌린다.
                e.format = ""
                touched.add(e)
            }
        }
        store.save(touched)
        val msg = if (moved > 0) "저장했어요. 다른 분류에서 옮겨 온 사람 ${moved}명 포함" else "저장했어요"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        finish()
    }

    private inner class MemberAdapter : RecyclerView.Adapter<MemberAdapter.Holder>() {
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
            holder.check.isChecked = e.key in checked
            holder.name.text = e.name
            val cur = currentFormat(e)
            val label = when {
                cur == formatName -> ""
                cur.isEmpty() -> " · 분류 없음"
                else -> " · 지금 '$cur'"
            }
            holder.sub.text = e.phone + label
            holder.itemView.setOnClickListener {
                if (!checked.add(e.key)) checked.remove(e.key)
                notifyItemChanged(holder.bindingAdapterPosition)
                countText.text = "체크 ${checked.size}명 · 화면에 ${visible.size}명"
                saveButton.text = "저장 ('$formatName' ${checked.size}명)"
                if (filter == Filter.CHECKED) refresh()
            }
        }
    }

    companion object {
        private const val EXTRA_FORMAT = "format"

        fun intent(context: Context, format: String): Intent =
            Intent(context, FormatMembersActivity::class.java).putExtra(EXTRA_FORMAT, format)

        /** 분류를 먼저 고르게 한 뒤 화면을 연다. */
        fun pickAndOpen(activity: AppCompatActivity) {
            val formats = AppStore(activity).formats()
            AlertDialog.Builder(activity)
                .setTitle("어느 분류에 사람을 넣을까요?")
                .setItems(formats.map { it.name }.toTypedArray()) { _, which ->
                    activity.startActivity(intent(activity, formats[which].name))
                }
                .setNegativeButton("취소", null)
                .show()
        }
    }
}
