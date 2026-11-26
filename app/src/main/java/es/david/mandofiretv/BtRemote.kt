package es.david.mandofiretv

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import java.util.concurrent.Executors

/**
 * El móvil se presenta como un teclado + mando multimedia Bluetooth (perfil HID).
 * El Fire TV lo empareja como cualquier teclado y recibe las teclas sin WiFi ni ADB.
 * Requiere Android 9 o superior y que el fabricante del móvil no haya desactivado el perfil HID.
 */
@SuppressLint("MissingPermission")
class BtRemote(private val context: Context) {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var hid: BluetoothHidDevice? = null
    @Volatile var registered: Boolean = false
        private set
    @Volatile var host: BluetoothDevice? = null
        private set
    @Volatile var unsupported: Boolean = false
        private set
    private var proxyRequested = false
    var wantedAddress: String? = null

    val supported: Boolean get() = Build.VERSION.SDK_INT >= 28 && adapter != null && !unsupported
    val enabled: Boolean get() = try { adapter?.isEnabled == true } catch (_: Exception) { false }
    val connected: Boolean get() = host != null

    // Teclado (informe 1) + teclas multimedia/consumo (informe 2)
    private val descriptor = byteArrayOf(
        0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x01,
        0x05, 0x07, 0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(), 0x15, 0x00, 0x25, 0x01,
        0x75, 0x01, 0x95.toByte(), 0x08, 0x81.toByte(), 0x02,
        0x95.toByte(), 0x01, 0x75, 0x08, 0x81.toByte(), 0x01,
        0x95.toByte(), 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
        0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81.toByte(), 0x00,
        0xC0.toByte(),
        0x05, 0x0C, 0x09, 0x01, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x02,
        0x15, 0x00, 0x26, 0xFF.toByte(), 0x03, 0x19, 0x00, 0x2A, 0xFF.toByte(), 0x03,
        0x75, 0x10, 0x95.toByte(), 0x01, 0x81.toByte(), 0x00,
        0xC0.toByte()
    )

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, reg: Boolean) {
            registered = reg
            if (reg) tryConnectWanted()
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> host = device
                BluetoothProfile.STATE_DISCONNECTED -> if (host?.address == device.address) host = null
            }
        }
    }

    /** Arranca el perfil HID (hay que tener ya los permisos de Bluetooth). */
    fun start() {
        if (Build.VERSION.SDK_INT < 28 || adapter == null || proxyRequested) return
        proxyRequested = true
        val ok = adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val h = proxy as BluetoothHidDevice
                hid = h
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "Mando Fire TV", "Mando para Fire TV", "David",
                    BluetoothHidDevice.SUBCLASS1_COMBO, descriptor
                )
                try {
                    if (!h.registerApp(sdp, null, null, Executors.newSingleThreadExecutor(), callback)) unsupported = true
                } catch (_: Exception) { unsupported = true }
            }
            override fun onServiceDisconnected(profile: Int) { hid = null; registered = false; host = null }
        }, BluetoothProfile.HID_DEVICE)
        if (!ok) unsupported = true
    }

    fun stop() {
        try { hid?.unregisterApp() } catch (_: Exception) {}
        try { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid) } catch (_: Exception) {}
        hid = null; registered = false; host = null; proxyRequested = false
    }

    /** Dispositivos ya emparejados con el móvil (para elegir el Fire TV). */
    fun bonded(): List<Pair<String, String>> = try {
        adapter?.bondedDevices?.map { (it.name ?: it.address) to it.address }?.sortedBy { it.first } ?: emptyList()
    } catch (_: Exception) { emptyList() }

    fun hostName(): String = try { host?.name ?: host?.address ?: "" } catch (_: Exception) { "" }

    fun tryConnectWanted(): Boolean {
        val addr = wantedAddress ?: return false
        val h = hid ?: return false
        if (host?.address == addr) return true
        return try {
            host?.let { h.disconnect(it) }
            val dev = adapter?.getRemoteDevice(addr) ?: return false
            h.connect(dev)
        } catch (_: Exception) { false }
    }

    fun disconnect() {
        try { host?.let { hid?.disconnect(it) } } catch (_: Exception) {}
    }

    // ------------------------------------------------------------ envío de teclas
    private fun kb(mod: Int, key: Int): Boolean {
        val h = hid ?: return false
        val d = host ?: return false
        return try { h.sendReport(d, 1, byteArrayOf(mod.toByte(), 0, key.toByte(), 0, 0, 0, 0, 0)) } catch (_: Exception) { false }
    }

    private fun consumer(usage: Int): Boolean {
        val h = hid ?: return false
        val d = host ?: return false
        return try { h.sendReport(d, 2, byteArrayOf((usage and 0xFF).toByte(), (usage shr 8).toByte())) } catch (_: Exception) { false }
    }

    private fun pressKb(key: Int, mod: Int = 0, holdMs: Long = 30): Boolean {
        if (!kb(mod, key)) return false
        Thread.sleep(holdMs)
        return kb(0, 0)
    }

    private fun pressConsumer(usage: Int, holdMs: Long = 30): Boolean {
        if (!consumer(usage)) return false
        Thread.sleep(holdMs)
        return consumer(0)
    }

    /** Código de tecla Android → HID. Devuelve null si esa tecla no se puede mandar por Bluetooth. */
    fun sendKey(code: Int, long: Boolean): Boolean? {
        val hold = if (long) 900L else 30L
        val kbKey = when (code) {
            19 -> 0x52; 20 -> 0x51; 21 -> 0x50; 22 -> 0x4F      // flechas
            23, 66 -> 0x28                                      // OK / Intro
            67 -> 0x2A; 62 -> 0x2C                              // borrar / espacio
            82 -> 0x65                                          // menú
            else -> -1
        }
        if (kbKey >= 0) return pressKb(kbKey, holdMs = hold)
        val usage = when (code) {
            4 -> 0x0224        // atrás
            3 -> 0x0223        // inicio
            85 -> 0x00CD       // play/pausa
            89 -> 0x00B4       // retroceder
            90 -> 0x00B3       // avanzar
            24 -> 0x00E9; 25 -> 0x00EA; 164 -> 0x00E2
            26 -> 0x0030       // encendido / reposo
            84 -> 0x0221       // buscar
            else -> -1
        }
        if (usage >= 0) return pressConsumer(usage, hold)
        return null
    }

    /** Escribe texto como un teclado (distribución estándar). */
    fun type(text: String): Boolean {
        for (c in text) {
            val (mod, key) = when (c) {
                in 'a'..'z' -> 0 to (0x04 + (c - 'a'))
                in 'A'..'Z' -> 0x02 to (0x04 + (c - 'A'))
                in '1'..'9' -> 0 to (0x1E + (c - '1'))
                '0' -> 0 to 0x27
                ' ' -> 0 to 0x2C
                '.' -> 0 to 0x37
                ',' -> 0 to 0x36
                '-' -> 0 to 0x2D
                else -> continue
            }
            if (!pressKb(key, mod, 20)) return false
            Thread.sleep(15)
        }
        return true
    }
}
