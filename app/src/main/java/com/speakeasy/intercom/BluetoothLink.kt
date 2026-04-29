package com.speakeasy.intercom

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * RFCOMM-Punkt-zu-Punkt-Verbindung zwischen zwei Telefonen.
 *
 * Eine Seite ruft [listen] auf (Host), die andere [connect] (Join).
 * Der zurückgegebene [Connection] kapselt die geöffneten Streams.
 */
class BluetoothLink(private val adapter: BluetoothAdapter) {

    class Connection internal constructor(
        private val socket: BluetoothSocket,
        val remoteDevice: BluetoothDevice,
        val remoteName: String,
        val input: InputStream,
        val output: OutputStream,
    ) : Closeable {
        override fun close() {
            try { socket.close() } catch (_: IOException) {}
        }
    }

    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var clientSocket: BluetoothSocket? = null

    /** Blockiert bis Peer connected oder Fehler/Abbruch. */
    @Throws(IOException::class)
    fun listen(): Connection {
        val name = "SpeakEasyIntercom"
        // Insecure-RFCOMM: kein Pairing-Zwang, deutlich zuverlässiger als der
        // Secure-Pfad – beide Seiten müssen denselben Modus verwenden.
        val server = adapter.listenUsingInsecureRfcommWithServiceRecord(name, SERVICE_UUID)
        serverSocket = server
        try {
            val socket = server.accept() // blockiert
            clientSocket = socket
            return socket.toConnection()
        } finally {
            try { server.close() } catch (_: IOException) {}
            serverSocket = null
        }
    }

    @Throws(IOException::class)
    fun connect(device: BluetoothDevice): Connection {
        // Discovery vor jedem Connect abbrechen – sonst sehr langsam/unzuverlässig.
        if (adapter.isDiscovering) {
            adapter.cancelDiscovery()
            // Stack-Settle-Time: ohne diese Pause wirft der Connect oft
            // "read failed, socket might closed or timeout, read ret: -1".
            sleepQuiet(300)
        }

        var lastError: IOException? = null
        repeat(2) { attempt ->
            if (attempt > 0) sleepQuiet(800)
            val socket = device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)
            clientSocket = socket
            try {
                socket.connect() // blockiert
                return socket.toConnection()
            } catch (e: IOException) {
                Log.w(TAG, "RFCOMM-Connect Versuch ${attempt + 1} fehlgeschlagen: ${e.message}")
                try { socket.close() } catch (_: IOException) {}
                clientSocket = null
                lastError = e
            }
        }

        // Reflektiver Last-Resort-Fallback auf RFCOMM-Channel 1 – hilft auf
        // einigen Geräten, wenn der SDP-Lookup auf der Gegenseite zickt.
        runCatching {
            val method = device.javaClass.getMethod(
                "createRfcommSocket", Int::class.javaPrimitiveType,
            )
            val socket = method.invoke(device, 1) as BluetoothSocket
            clientSocket = socket
            try {
                socket.connect()
                Log.i(TAG, "RFCOMM-Connect via reflektiven Fallback erfolgreich")
                return socket.toConnection()
            } catch (e: IOException) {
                try { socket.close() } catch (_: IOException) {}
                lastError = e
            }
        }

        throw lastError ?: IOException("RFCOMM-Verbindung fehlgeschlagen")
    }

    private fun sleepQuiet(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    /** Bricht eine wartende [listen]/[connect] hart ab. */
    fun cancel() {
        serverSocket?.let { try { it.close() } catch (_: IOException) {} }
        clientSocket?.let { try { it.close() } catch (_: IOException) {} }
        serverSocket = null
        clientSocket = null
    }

    private fun BluetoothSocket.toConnection(): Connection {
        val name = try { remoteDevice.name ?: remoteDevice.address } catch (_: SecurityException) { remoteDevice.address }
        Log.i(TAG, "RFCOMM verbunden mit $name")
        return Connection(this, remoteDevice, name, inputStream, outputStream)
    }

    companion object {
        private const val TAG = "BluetoothLink"
        // Stabile UUID für den SDP-Service. Beide Seiten müssen dieselbe verwenden.
        val SERVICE_UUID: UUID = UUID.fromString("4f3a4b7e-8d6a-4d3f-9a1c-2b9c1f0e7a55")
    }
}
