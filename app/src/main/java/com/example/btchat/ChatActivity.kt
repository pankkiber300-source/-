package com.example.btchat

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ChatActivity : AppCompatActivity(), BluetoothChatService.Listener {

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_DEVICE_ADDRESS = "device_address"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val MODE_LISTEN = "listen"
        const val MODE_CONNECT = "connect"
    }

    private lateinit var chatTitle: TextView
    private lateinit var messagesText: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var messageInput: EditText
    private lateinit var sendButton: Button

    private lateinit var service: BluetoothChatService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        chatTitle = findViewById(R.id.chatTitle)
        messagesText = findViewById(R.id.messagesText)
        scrollView = findViewById(R.id.scrollView)
        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)

        sendButton.setOnClickListener {
            val text = messageInput.text.toString().trim()
            if (text.isNotEmpty()) {
                service.send(text)
                messageInput.setText("")
            }
        }

        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) {
            Toast.makeText(this, "Bluetooth недоступен", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        service = BluetoothChatService(adapter, this)

        when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_LISTEN -> {
                chatTitle.text = "Ожидание подключения…"
                service.startListening()
            }
            MODE_CONNECT -> {
                val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                val name = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: address
                chatTitle.text = "Подключение к $name…"
                val device = try {
                    adapter.getRemoteDevice(address)
                } catch (e: IllegalArgumentException) {
                    Toast.makeText(this, "Некорректный адрес устройства", Toast.LENGTH_SHORT).show()
                    finish()
                    return
                }
                service.connectTo(device)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        service.stop()
    }

    override fun onStateChanged(state: BluetoothChatService.ConnState) {
        when (state) {
            BluetoothChatService.ConnState.LISTENING -> chatTitle.text = "Ожидание подключения…"
            BluetoothChatService.ConnState.CONNECTING -> chatTitle.text = "Подключение…"
            BluetoothChatService.ConnState.CONNECTED -> {} // title set in onConnectedTo
            BluetoothChatService.ConnState.NONE -> {}
        }
    }

    override fun onConnectedTo(deviceName: String) {
        chatTitle.text = "Чат с $deviceName"
    }

    override fun onMessageReceived(text: String) {
        appendLine("Собеседник: $text")
    }

    override fun onMessageSent(text: String) {
        appendLine("Я: $text")
    }

    override fun onError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun appendLine(line: String) {
        messagesText.append(if (messagesText.text.isEmpty()) line else "\n$line")
        scrollView.post { scrollView.fullScroll(android.view.View.FOCUS_DOWN) }
    }
}
