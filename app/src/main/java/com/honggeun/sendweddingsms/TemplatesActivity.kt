package com.honggeun.sendweddingsms

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch

/** 청첩장 주소와 형식별(어르신, 직원, 거래처 등) 문구를 정하는 화면. */
class TemplatesActivity : AppCompatActivity() {

    private lateinit var store: AppStore
    private lateinit var linkInput: EditText
    private lateinit var formatsBox: LinearLayout
    private var formats: MutableList<MessageFormat> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_templates)
        store = AppStore(this)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        linkInput = findViewById(R.id.linkInput)
        formatsBox = findViewById(R.id.formatsBox)

        linkInput.setText(store.link)
        linkInput.doAfterTextChanged { store.link = it?.toString() ?: "" }

        val autoSwitch = findViewById<MaterialSwitch>(R.id.autoSendSwitch)
        autoSwitch.isChecked = store.autoSend
        autoSwitch.setOnCheckedChangeListener { _, checked -> store.autoSend = checked }

        findViewById<Button>(R.id.addFormat).setOnClickListener { editFormat(-1) }

        formats = store.formats().toMutableList()
        render()
    }

    private fun render() {
        formatsBox.removeAllViews()
        val sampleLink = store.link.ifBlank { "(청첩장 주소)" }
        formats.forEachIndexed { i, f ->
            val v = layoutInflater.inflate(R.layout.item_format, formatsBox, false)
            v.findViewById<TextView>(R.id.formatName).text = if (i == 0) "${f.name}  (기본값)" else f.name
            v.findViewById<TextView>(R.id.formatText).text = MessageRenderer.render(f.text, "홍길동", sampleLink)
            v.setOnClickListener { editFormat(i) }
            formatsBox.addView(v)
        }
    }

    private fun editFormat(index: Int) {
        val existing = formats.getOrNull(index)
        val view = layoutInflater.inflate(R.layout.dialog_format, null)
        val nameInput = view.findViewById<EditText>(R.id.formatNameInput)
        val textInput = view.findViewById<EditText>(R.id.formatTextInput)
        val preview = view.findViewById<TextView>(R.id.formatPreview)
        nameInput.setText(existing?.name ?: "")
        textInput.setText(existing?.text ?: "{이름}님, \n{링크}")

        fun update() {
            preview.text = MessageRenderer.render(textInput.text.toString(), "홍길동", store.link.ifBlank { "(청첩장 주소)" })
        }
        textInput.doAfterTextChanged { update() }
        PlaceholderButtons.bind(view, textInput)
        update()

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "형식 추가" else "형식 고치기")
            .setView(view)
            .setPositiveButton("저장", null)
            .setNegativeButton("취소", null)
        if (existing != null && formats.size > 1) {
            builder.setNeutralButton("삭제") { _, _ -> confirmDelete(index) }
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            // 이름이 비었거나 겹치면 창을 닫지 않는다.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                val text = textInput.text.toString().trim()
                when {
                    name.isEmpty() -> nameInput.error = "이름을 넣어 주세요"
                    formats.withIndex().any { (i, f) -> i != index && f.name == name } -> nameInput.error = "같은 이름이 있어요"
                    else -> {
                        val updated = MessageFormat(name, text)
                        if (existing == null) {
                            formats.add(updated)
                        } else {
                            formats[index] = updated
                            if (existing.name != name) store.replaceFormatInContacts(existing.name, name)
                        }
                        store.saveFormats(formats)
                        render()
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun confirmDelete(index: Int) {
        val f = formats[index]
        val fallback = formats.first { it.name != f.name }.name
        AlertDialog.Builder(this)
            .setMessage("'${f.name}' 형식을 지울까요?\n이 형식을 쓰던 사람은 '$fallback' 형식으로 바뀌어요.")
            .setPositiveButton("삭제") { _, _ ->
                formats.removeAt(index)
                store.saveFormats(formats)
                store.replaceFormatInContacts(f.name, fallback)
                render()
                Toast.makeText(this, "지웠어요", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
