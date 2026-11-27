package com.example.btchat

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Manages a single Bluetooth RFCOMM connection: listening for an incoming
 * connection (server role) or connecting out to a chosen device (client role),
 * then reading/writing chat messages over the resulting socket.
 *
 * Only one chat partner at a time (1-to-1), matching the app's design.
 */
class BluetoothChatService(
    private val adapter: BluetoothAdapter,
    private val listener: Listener
) {

    interface Listener {
        fun onStateChanged(state: State)
        fun onMessageReceived(text: String)
        fun onMessageSent(text: String)
        fun onConnectedTo(deviceName: String)
        fun onError(message: String)
    }

    enum class State { NONE, LISTENING, CONNECTING, CONNECTED }

    companion object {
        // Well-known "Serial Port Profile" UUID, used so both sides agree on
        // which RFCOMM channel / service this chat app is speaking.
        val APP_UUID: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
        private const val SERVICE_NAME = "BtChatSecure"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var state: State = State.NONE
        set(value) {
            field = value
            mainHandler.post { listener.onStateChanged(value) }
        }

    private var acceptThread: AcceptThread? = null
    private var connectThread: ConnectThread? = null
    private var connectedThread: ConnectedThread? = null

    @Synchronized
    fun startListening() {
        connectThread?.cancel(); connectThread = null
        connectedThread?.cancel(); connectedThread = null
        if (acceptThread == null) {
            acceptThread = AcceptThread().also { it.start() }
        }
        state = State.LISTENING
    }

    @Synchronized
    fun connectTo(device: BluetoothDevice) {
        acceptThread?.cancel(); acceptThread = null
        connectThread?.cancel()
        connectThread = ConnectThread(device).also { it.start() }
        state = State.CONNECTING
    }

    @Synchronized
    fun stop() {
        acceptThread?.cancel(); acceptThread = null
        connectThread?.cancel(); connectThread = null
        connectedThread?.cancel(); connectedThread = null
        state = State.NONE
    }

    fun send(text: String) {
        val thread = connectedThread ?: run {
            listener.onError("Нет активного соединения")
            return
        }
        thread.write(text)
    }

    @Synchronized
    private fun onSocketConnected(socket: BluetoothSocket, remoteName: String) {
        acceptThread?.cancel(); acceptThread = null
        connectThread?.cancel(); connectThread = null
        connectedThread?.cancel()
        connectedThread = ConnectedThread(socket).also { it.start() }
        state = State.CONNECTED
        mainHandler.post { listener.onConnectedTo(remoteName) }
    }

    /** Server role: waits for an incoming RFCOMM connection. */
    private inner class AcceptThread : Thread() {
        private val serverSocket: BluetoothServerSocket? = try {
            adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, APP_UUID)
        } catch (e: SecurityException) {
            mainHandler.post { listener.onError("Нет разрешения Bluetooth: ${e.message}") }
            null
        } catch (e: IOException) {
            mainHandler.post { listener.onError("Не удалось начать прослушивание: ${e.message}") }
            null
        }

        override fun run() {
            var socket: BluetoothSocket?
            while (state != State.CONNECTED) {
                socket = try {
                    serverSocket?.accept()
                } catch (e: IOException) {
                    break
                }
                if (socket != null) {
                    val name = remoteNameSafe(socket.remoteDevice)
                    onSocketConnected(socket, name)
                    try { serverSocket?.close() } catch (_: IOException) {}
                    break
                }
            }
        }

        fun cancel() {
            try { serverSocket?.close() } catch (_: IOException) {}
        }
    }

    /** Client role: connects out to a device the user picked from the list. */
    private inner class ConnectThread(private val device: BluetoothDevice) : Thread() {
        private val socket: BluetoothSocket? = try {
            device.createInsecureRfcommSocketToServiceRecord(APP_UUID)
        } catch (e: SecurityException) {
            mainHandler.post { listener.onError("Нет разрешения Bluetooth: ${e.message}") }
            null
        } catch (e: IOException) {
            mainHandler.post { listener.onError("Не удалось создать сокет: ${e.message}") }
            null
        }

        override fun run() {
            val s = socket ?: return
            try {
                adapter.cancelDiscovery()
            } catch (_: SecurityException) { /* ignore */ }

            try {
                s.connect()
            } catch (e: IOException) {
                mainHandler.post { listener.onError("Не удалось подключиться: ${e.message}") }
                try { s.close() } catch (_: IOException) {}
                mainHandler.post { startListening() }
                return
            }
            onSocketConnected(s, remoteNameSafe(device))
        }

        fun cancel() {
            try { socket?.close() } catch (_: IOException) {}
        }
    }

    /** Active connection: reads incoming text lines and writes outgoing ones. */
    private inner class ConnectedThread(private val socket: BluetoothSocket) : Thread() {
        private val input: InputStream = socket.inputStream
        private val output: OutputStream = socket.outputStream

        override fun run() {
            val buffer = ByteArray(4096)
            val acc = StringBuilder()
            while (true) {
                val count = try {
                    input.read(buffer)
                } catch (e: IOException) {
                    mainHandler.post { listener.onError("Соединение разорвано") }
                    mainHandler.post { startListening() }
                    break
                }
                if (count <= 0) break
                acc.append(String(buffer, 0, count, Charsets.UTF_8))
                var newlineIndex: Int
                while (acc.indexOf("\n").also { newlineIndex = it } >= 0) {
                    val line = acc.substring(0, newlineIndex)
                    acc.delete(0, newlineIndex + 1)
                    if (line.isNotEmpty()) {
                        mainHandler.post { listener.onMessageReceived(line) }
                    }
                }
            }
        }

        fun write(text: String) {
            try {
                output.write((text + "\n").toByteArray(Charsets.UTF_8))
                output.flush()
                mainHandler.post { listener.onMessageSent(text) }
            } catch (e: IOException) {
                mainHandler.post { listener.onError("Не удалось отправить: ${e.message}") }
            }
        }

        fun cancel() {
            try { socket.close() } catch (_: IOException) {}
        }
    }

    @SuppressLint("MissingPermission")
    private fun remoteNameSafe(device: BluetoothDevice): String =
        try { device.name ?: device.address } catch (_: SecurityException) { device.address }
}
