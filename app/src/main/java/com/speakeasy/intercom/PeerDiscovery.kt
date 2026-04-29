package com.speakeasy.intercom

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Parcelable
import android.os.ParcelUuid
import android.util.Log

/**
 * Sucht andere Telefone, auf denen SpeakEasy läuft, indem klassische BT-Discovery
 * gestartet und für jedes gefundene Phone-Gerät via SDP geprüft wird, ob es die
 * App-eigene [BluetoothLink.SERVICE_UUID] anbietet.
 *
 * Listener-Callbacks landen IMMER auf dem Main-Thread.
 */
class PeerDiscovery(
    private val context: Context,
    private val adapter: BluetoothAdapter,
) {
    interface Listener {
        fun onPeerFound(device: BluetoothDevice, name: String)
        fun onScanFinished()
    }

    private var listener: Listener? = null
    private val seen = mutableSetOf<String>()
    private val confirmed = mutableSetOf<String>()
    private var registered = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> deviceOf(intent)?.let(::onDeviceSeen)
                BluetoothDevice.ACTION_UUID -> {
                    val device = deviceOf(intent) ?: return
                    val uuids = uuidsOf(intent)
                    val isPeer = uuids.any { it.uuid == BluetoothLink.SERVICE_UUID }
                    if (isPeer && confirmed.add(device.address)) notifyConfirmed(device)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED ->
                    mainHandler.post { listener?.onScanFinished() }
            }
        }
    }

    fun start(listener: Listener) {
        this.listener = listener
        seen.clear()
        confirmed.clear()

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_UUID)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        registered = true

        // Bereits gepairte Telefone direkt prüfen – Discovery erkennt diese teils
        // nicht (manche Stacks blenden sie aus), wir sehen sie aber so sofort.
        try {
            adapter.bondedDevices?.forEach { onDeviceSeen(it) }
        } catch (_: SecurityException) {}

        try {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            adapter.startDiscovery()
        } catch (e: SecurityException) {
            Log.w(TAG, "BT-Scan nicht erlaubt", e)
            mainHandler.post { listener.onScanFinished() }
        }
    }

    fun stop() {
        if (registered) {
            try { context.unregisterReceiver(receiver) } catch (_: Throwable) {}
            registered = false
        }
        try {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
        } catch (_: SecurityException) {}
        listener = null
    }

    private fun onDeviceSeen(device: BluetoothDevice) {
        if (!seen.add(device.address)) return
        // Auf Telefone beschränken – spart unnötige SDP-Anfragen an Speaker, Watches usw.
        val major = try { device.bluetoothClass?.majorDeviceClass } catch (_: SecurityException) { null }
        if (major != null && major != BluetoothClass.Device.Major.PHONE) return
        try { device.fetchUuidsWithSdp() } catch (_: SecurityException) {}
    }

    private fun notifyConfirmed(device: BluetoothDevice) {
        val name = try { device.name } catch (_: SecurityException) { null } ?: device.address
        mainHandler.post { listener?.onPeerFound(device, name) }
    }

    private fun deviceOf(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else
            @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    private fun uuidsOf(intent: Intent): List<ParcelUuid> {
        val raw: Array<Parcelable>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID, Parcelable::class.java)
            else
                @Suppress("DEPRECATION") intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
        return raw?.mapNotNull { it as? ParcelUuid } ?: emptyList()
    }

    companion object { private const val TAG = "PeerDiscovery" }
}
