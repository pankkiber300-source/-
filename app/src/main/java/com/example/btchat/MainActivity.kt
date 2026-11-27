package com.example.btchat

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var scanButton: Button
    private lateinit var listenButton: Button
    private lateinit var deviceList: RecyclerView
    private lateinit var adapter: DeviceListAdapter

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    // Launcher for the "please enable Bluetooth" system dialog.
    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshStatus() }

    // Launcher for requesting all Bluetooth-related runtime permissions at once.
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            refreshStatus()
        } else {
            Toast.makeText(
                this,
                "Без этих разрешений Bluetooth-чат работать не может",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_FOUND) {
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                device?.let {
                    val name = nameOf(it) ?: "Неизвестное устройство"
                    adapter.addIfAbsent(DeviceEntry(name, it.address, paired = false))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        scanButton = findViewById(R.id.scanButton)
        listenButton = findViewById(R.id.listenButton)
        deviceList = findViewById(R.id.deviceList)

        adapter = DeviceListAdapter { entry -> onDeviceChosen(entry) }
        deviceList.layoutManager = LinearLayoutManager(this)
        deviceList.adapter = adapter

        scanButton.setOnClickListener { startScan() }
        listenButton.setOnClickListener { startListening() }

        registerReceiver(discoveryReceiver, IntentFilter(BluetoothDevice.ACTION_FOUND))

        ensurePermissionsThenRefresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(discoveryReceiver) } catch (_: IllegalArgumentException) {}
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasAllPermissions(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun ensurePermissionsThenRefresh() {
        if (hasAllPermissions()) {
            refreshStatus()
        } else {
            permissionLauncher.launch(requiredPermissions())
        }
    }

    private fun refreshStatus() {
        val adapterLocal = bluetoothAdapter
        if (adapterLocal == null) {
            statusText.text = "Это устройство не поддерживает Bluetooth"
            scanButton.isEnabled = false
            listenButton.isEnabled = false
            return
        }
        if (!adapterLocal.isEnabled) {
            statusText.text = "Bluetooth выключен — включите его"
            scanButton.isEnabled = false
            listenButton.isEnabled = false
            if (hasAllPermissions()) {
                enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
            return
        }
        if (!hasAllPermissions()) {
            statusText.text = "Нужны разрешения Bluetooth"
            scanButton.isEnabled = false
            listenButton.isEnabled = false
            return
        }
        statusText.text = "Bluetooth готов"
        scanButton.isEnabled = true
        listenButton.isEnabled = true
        loadPairedDevices()
    }

    private fun loadPairedDevices() {
        val adapterLocal = bluetoothAdapter ?: return
        if (!hasConnectPermission()) return
        val paired = try {
            adapterLocal.bondedDevices
        } catch (_: SecurityException) {
            emptySet()
        }
        val entries = paired.map { DeviceEntry(nameOf(it) ?: it.address, it.address, paired = true) }
        adapter.submit(entries)
    }

    private fun startScan() {
        val adapterLocal = bluetoothAdapter ?: return
        if (!hasScanPermission()) return
        loadPairedDevices()
        try {
            if (adapterLocal.isDiscovering) adapterLocal.cancelDiscovery()
            adapterLocal.startDiscovery()
            Toast.makeText(this, "Ищу устройства поблизости…", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, "Нет разрешения на поиск: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startListening() {
        if (!hasConnectPermission()) return
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra(ChatActivity.EXTRA_MODE, ChatActivity.MODE_LISTEN)
        }
        startActivity(intent)
    }

    private fun onDeviceChosen(entry: DeviceEntry) {
        if (!hasConnectPermission()) return
        val adapterLocal = bluetoothAdapter ?: return
        try {
            if (adapterLocal.isDiscovering) adapterLocal.cancelDiscovery()
        } catch (_: SecurityException) {}

        val device = try {
            adapterLocal.getRemoteDevice(entry.address)
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, "Некорректный адрес устройства", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra(ChatActivity.EXTRA_MODE, ChatActivity.MODE_CONNECT)
            putExtra(ChatActivity.EXTRA_DEVICE_ADDRESS, device.address)
            putExtra(ChatActivity.EXTRA_DEVICE_NAME, entry.name)
        }
        startActivity(intent)
    }

    private fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    private fun nameOf(device: BluetoothDevice): String? =
        try { device.name } catch (_: SecurityException) { null }
}
