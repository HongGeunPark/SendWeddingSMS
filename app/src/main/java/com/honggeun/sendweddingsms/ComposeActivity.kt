package com.honggeun.sendweddingsms

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged

/**
 * 1단계: 이번에 보낼 문구를 쓴다.
 * 다 보내고 새로 시작하면 문구 칸은 비어 있고, 청첩장 주소는 그대로 남아 있다.
 */
class ComposeActivity : AppCompatActivity() {

    private lateinit var store: AppStore
    private lateinit var linkInput: EditText
    private lateinit var messageInput: EditText
    private lateinit var preview: TextView
    private lateinit var nextButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_compose)
        store = AppStore(this)

        findViewById<TextView>(R.id.stepText).text = "1단계 / 3  ·  보낼 문구 쓰기"
        linkInput = findViewById(R.id.linkInput)
        messageInput = findViewById(R.id.messageInput)
        preview = findViewById(R.id.preview)
        nextButton = findViewById(R.id.next)

        linkInput.doAfterTextChanged { update() }
        messageInput.doAfterTextChanged { update() }
        PlaceholderButtons.bind(findViewById(android.R.id.content), messageInput)

        nextButton.setOnClickListener {
            val msg = messageInput.text.toString().trim()
            if (msg.isEmpty()) {
                Toast.makeText(this, "보낼 문구를 먼저 써 주세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            save()
            startActivity(Intent(this, SelectActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // 새로 시작했거나 앱을 다시 열었을 때 저장된 값으로 채운다.
        if (linkInput.text.toString() != store.link) linkInput.setText(store.link)
        if (messageInput.text.toString() != store.draftMessage) messageInput.setText(store.draftMessage)
        update()
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    private fun save() {
        store.link = linkInput.text.toString()
        store.draftMessage = messageInput.text.toString().trim()
    }

    private fun update() {
        val msg = messageInput.text.toString()
        val link = linkInput.text.toString().ifBlank { "(청첩장 주소)" }
        preview.text = if (msg.isBlank()) "문구를 쓰면 여기에 미리보기가 나와요." else MessageRenderer.render(msg, "홍길동", link)
        nextButton.isEnabled = msg.isNotBlank()
    }
}
