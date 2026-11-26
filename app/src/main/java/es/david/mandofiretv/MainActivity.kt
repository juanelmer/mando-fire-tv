package es.david.mandofiretv

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import dadb.AdbKeyPair
import dadb.AdbStream
import dadb.Dadb
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var prefs: SharedPreferences
    private val exec = Executors.newSingleThreadExecutor()   // las órdenes ADB van en fila, una a una
    private var dadb: Dadb? = null
    @Volatile private var connected = false

    // Canal rápido: un "monkey" que se queda abierto en el Fire TV y recibe teclas al instante
    private var monkeyProc: AdbStream? = null
    private var monkeySock: AdbStream? = null
    private var monkeyFailedAt = 0L
    private val pendingKeys = AtomicInteger(0)
    private val monkeyReader = Executors.newSingleThreadExecutor()   // lee la confirmación "OK" del canal rápido
    private lateinit var lg: LgTv
    private lateinit var ir: IrTv
    private lateinit var bt: BtRemote
    private val irExec = Executors.newSingleThreadExecutor()   // los infrarrojos no esperan a la cola del Fire TV

    private val pkgRe = Regex("^[A-Za-z0-9_.]+$")
    private val ipRe = Regex("^[A-Za-z0-9.\\-]+(:\\d{1,5})?$")

    private val defaultApps = """[
        {"name":"Netflix","pkg":"com.netflix.ninja"},
        {"name":"YouTube","pkg":"com.amazon.firetv.youtube"},
        {"name":"Disney+","pkg":"com.disney.disneyplus"},
        {"name":"Spotify","pkg":"com.spotify.tv.android"},
        {"name":"Kodi","pkg":"org.xbmc.kodi"}
    ]"""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("config", MODE_PRIVATE)
        lg = LgTv(prefs)
        ir = IrTv(this, prefs)
        bt = BtRemote(this)
        bt.wantedAddress = prefs.getString("bt_addr", null)
        if (btOn() && hasBtPerms()) bt.start()
        web = WebView(this)
        web.setBackgroundColor(0xFF111318.toInt())
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }

    override fun onDestroy() {
        try { bt.stop() } catch (_: Exception) {}
        exec.execute { try { dadb?.close() } catch (_: Exception) {}; lg.close() }
        exec.shutdown()
        irExec.shutdown()
        monkeyReader.shutdownNow()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7 && hasBtPerms() && btOn()) bt.start()
    }

    // Los botones de volumen del móvil controlan el volumen de la tele cuando hay conexión
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val isVol = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (isVol && irOn()) {
            val cmd = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) "volup" else "voldown"
            irExec.execute { ir.send(cmd) }
            return true
        }
        if ((connected || lg.configured()) && isVol) {
            val code = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 24 else 25
            if (pendingKeys.get() < 2) {
                pendingKeys.incrementAndGet()
                exec.execute { try { sendKey(code, false) } finally { pendingKeys.decrementAndGet() } }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------ puente JS
    inner class Bridge {
        @JavascriptInterface
        fun call(id: Int, path: String, body: String) {
            val keyCode = if (path == "/api/key") (try { JSONObject(body).optInt("code", -1) } catch (_: Exception) { -1 }) else -1
            if (path.startsWith("/api/ir") || (irOn() && ir.cmdForKey(keyCode) != null)) {
                irExec.execute {
                    val res = try { route(path, if (body.isEmpty()) JSONObject() else JSONObject(body)) }
                              catch (e: Exception) { result(false, e.message ?: e.toString()) }
                    runOnUiThread { web.evaluateJavascript("window.__cb($id, ${JSONObject.quote(res.toString())})", null) }
                }
                return
            }
            // Si ya hay teclas esperando en cola, se descartan las repeticiones para que no se acumule retraso
            val isKey = path == "/api/key"
            if (isKey && pendingKeys.get() >= 2) {
                runOnUiThread { web.evaluateJavascript("window.__cb($id, ${JSONObject.quote(result(true, "").toString())})", null) }
                return
            }
            if (isKey) pendingKeys.incrementAndGet()
            exec.execute {
                if (isKey) pendingKeys.decrementAndGet()
                val res = try {
                    route(path, if (body.isEmpty()) JSONObject() else JSONObject(body))
                } catch (e: Exception) {
                    result(false, e.message ?: e.toString())
                }
                runOnUiThread {
                    web.evaluateJavascript("window.__cb($id, ${JSONObject.quote(res.toString())})", null)
                }
            }
        }

        @JavascriptInterface
        fun vibrate() {
            runOnUiThread { web.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
        }
    }

    private fun result(ok: Boolean, msg: String) = JSONObject().put("ok", ok).put("msg", msg)

    private fun route(path: String, d: JSONObject): JSONObject = when (path) {
        "/api/status" -> JSONObject()
            .put("adb", true)
            .put("tv_name", tvName(currentTv()))
            .put("ip", ip())
            .put("connected", connected)
            .put("lg_ip", lg.ip())
            .put("ir_available", ir.available())
            .put("ir_on", irOn())
            .put("ir_set", ir.setName())
            .put("bt_on", btOn())
            .put("bt_connected", btOn() && bt.connected)
            .put("bt_host", bt.hostName())
            .put("apps", apps())

        "/api/tvs" -> JSONObject().put("ok", true).put("tvs", tvList()).put("current", currentTv())

        "/api/tv/switch" -> {
            val id = d.optInt("id", -1)
            if (!tvIds().contains(id)) result(false, "Esa tele no existe")
            else { switchTv(id); result(true, "Cambiado a ${tvName(id)}") }
        }

        "/api/tv/add" -> {
            val ids = tvIds()
            val id = (ids.maxOrNull() ?: 0) + 1
            val name = d.optString("name").trim().take(20).ifEmpty { "Tele $id" }
            saveTvIds(ids + id)
            prefs.edit().putString("tv_name_$id", name).apply()
            switchTv(id)
            result(true, "Añadida $name. Pon su IP en ⚙️")
        }

        "/api/tv/rename" -> {
            val name = d.optString("name").trim().take(20)
            if (name.isEmpty()) result(false, "Escribe un nombre")
            else { prefs.edit().putString("tv_name_${currentTv()}", name).apply(); result(true, "Nombre guardado") }
        }

        "/api/tv/delete" -> {
            val ids = tvIds()
            val cur = currentTv()
            if (ids.size <= 1) result(false, "No puedes borrar la única tele")
            else {
                val other = ids.first { it != cur }
                val gone = tvName(cur)
                switchTv(other)
                val e = prefs.edit()
                for (k in TV_KEYS) e.remove("p${cur}_$k")
                e.remove("tv_name_$cur").apply()
                saveTvIds(ids - cur)
                result(true, "Borrada $gone")
            }
        }

        "/api/connect" -> {
            val newIp = d.optString("ip", ip()).trim()
            if (newIp.isNotEmpty() && !ipRe.matches(newIp)) {
                result(false, "IP no válida")
            } else {
                if (newIp != ip()) {
                    prefs.edit().putString("ip", newIp).apply()
                    closeConnection()
                }
                closeMonkey()
                monkeyFailedAt = 0L
                val (ok, out) = shell("echo ok")
                if (ok && out.contains("ok")) result(true, "Conectado") else result(false, out)
            }
        }

        "/api/ir" -> {
            val (ok, msg) = ir.send(d.optString("cmd"))
            result(ok, msg)
        }

        "/api/ir/index" -> JSONObject().put("ok", true).put("index", ir.db.index())

        "/api/ir/test" -> {
            val list = ir.db.codes(d.optString("path"))
            val map = ir.db.mapButtons(list)
            val cmd = d.optString("cmd", "power")
            val c = map[cmd] ?: (if (cmd == "power") list.firstOrNull()?.second else null)
            if (c == null) result(false, "Este mando no tiene esa tecla.")
            else {
                val (ok, msg) = ir.sendCode(c)
                result(ok, if (ok) "Enviado (${c.protocol}). ¿Ha reaccionado la tele?" else msg)
            }
        }

        "/api/ir/use" -> {
            val path = d.optString("path")
            val map = ir.db.mapButtons(ir.db.codes(path))
            if (map.isEmpty()) result(false, "No encuentro teclas útiles en este mando. Prueba otro.")
            else {
                val name = d.optString("name").ifEmpty { path }
                ir.saveSet(name, map)
                prefs.edit().putBoolean("ir_on", true).apply()
                val names = mapOf("power" to "encender", "volup" to "vol+", "voldown" to "vol−", "mute" to "silencio",
                                  "chup" to "CH+", "chdown" to "CH−", "input" to "entrada")
                result(true, "Usando $name. Teclas: " + map.keys.mapNotNull { names[it] }.joinToString(", "))
            }
        }

        "/api/ir/reset" -> { ir.resetToLg(); result(true, "Vuelto a LG (integrado)") }

        "/api/irmode" -> {
            val on = d.optBoolean("on")
            if (on && !ir.available()) result(false, "Este móvil no tiene emisor de infrarrojos accesible.")
            else { prefs.edit().putBoolean("ir_on", on).apply(); result(true, if (on) "Infrarrojos activados" else "Infrarrojos desactivados") }
        }

        "/api/lg" -> {
            val newIp = d.optString("ip").trim()
            if (newIp.isNotEmpty() && !ipRe.matches(newIp)) result(false, "IP no válida")
            else {
                lg.setIp(newIp)
                if (newIp.isEmpty()) result(true, "Tele LG desactivada: el volumen va por el Fire TV")
                else { val (ok, msg) = lg.connect(60_000); result(ok, msg) }
            }
        }

        "/api/key" -> {
            val code = d.optInt("code", -1)
            if (code < 0) result(false, "Código inválido")
            else {
                val (ok, out) = sendKey(code, d.optBoolean("long"))
                result(ok, out)
            }
        }

        "/api/text" -> if (btOn() && bt.connected) {
            if (bt.type(d.optString("text"))) result(true, "") else result(false, "No se pudo enviar por Bluetooth")
        } else {
            val t = escapeText(d.optString("text"))
            if (t.isEmpty()) result(false, "Texto vacío o no válido")
            else { val (ok, out) = shell("input text $t"); result(ok, out) }
        }

        "/api/bt/state" -> JSONObject().put("ok", true)
            .put("supported", bt.supported).put("enabled", bt.enabled).put("perms", hasBtPerms())
            .put("on", btOn()).put("registered", bt.registered).put("connected", bt.connected)
            .put("host", bt.hostName()).put("addr", prefs.getString("bt_addr", "") ?: "")
            .put("proxy", bt.proxyConnected).put("error", bt.lastError)

        "/api/bt/restart" -> {
            if (!hasBtPerms()) { requestBtPerms(); result(false, "Acepta el permiso de Bluetooth y vuelve a pulsar.") }
            else {
                runOnUiThread { bt.restart() }
                Thread.sleep(2500)
                when {
                    bt.registered -> result(true, "Bluetooth listo")
                    bt.lastError.isNotEmpty() -> result(false, "Bluetooth: ${bt.lastError}")
                    !bt.proxyConnected -> result(false, "El sistema del móvil no responde al modo mando Bluetooth.")
                    else -> result(false, "Bluetooth todavía preparándose. Espera unos segundos.")
                }
            }
        }

        "/api/bt/auto" -> {
            // Lee por WiFi (ADB) la dirección Bluetooth del Fire TV y se conecta a ella directamente
            val macRe = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
            var mac = macRe.find(shell("settings get secure bluetooth_address").second)?.value
            if (mac == null || mac == "00:00:00:00:00:00" || mac == "02:00:00:00:00:00")
                mac = macRe.find(shell("dumpsys bluetooth_manager | grep -i -m1 'address'").second)?.value
            if (mac == null) result(false, "No pude leer la dirección Bluetooth del Fire TV. Conecta primero por WiFi (punto verde) y vuelve a probar.")
            else if (!bt.registered) result(false, "El Bluetooth del móvil aún no está listo como mando (pulsa Reiniciar Bluetooth).")
            else {
                val addr = mac.uppercase()
                prefs.edit().putString("bt_addr", addr).putBoolean("bt_on", true).apply()
                bt.wantedAddress = addr
                bt.tryConnectWanted()
                var waited = 0
                while (!bt.connected && waited < 15000) { Thread.sleep(250); waited += 250 }
                if (bt.connected) result(true, "Conectado por Bluetooth al Fire TV ($addr)")
                else result(false, "He intentado conectar con $addr. Si en la tele sale un aviso de emparejamiento, acéptalo y vuelve a pulsar.")
            }
        }

        "/api/bt/on" -> {
            val on = d.optBoolean("on")
            when {
                !on -> { prefs.edit().putBoolean("bt_on", false).apply(); bt.disconnect(); result(true, "Bluetooth desactivado: las teclas van por WiFi") }
                Build.VERSION.SDK_INT < 28 -> result(false, "Hace falta Android 9 o superior para el modo Bluetooth.")
                !bt.supported -> result(false, "Tu móvil no permite actuar como mando Bluetooth (el fabricante lo tiene desactivado).")
                !bt.enabled -> result(false, "Activa el Bluetooth del móvil y vuelve a intentarlo.")
                !hasBtPerms() -> { prefs.edit().putBoolean("bt_on", true).apply(); requestBtPerms(); result(false, "Acepta el permiso de Bluetooth (dispositivos cercanos) y vuelve a pulsar.") }
                else -> {
                    prefs.edit().putBoolean("bt_on", true).apply()
                    bt.start()
                    Thread.sleep(1500)
                    if (bt.unsupported) result(false, "Tu móvil no permite actuar como mando Bluetooth (el fabricante lo tiene desactivado).")
                    else result(true, if (bt.connected) "Conectado por Bluetooth" else "Bluetooth listo. Ahora empareja el Fire TV (pasos debajo).")
                }
            }
        }

        "/api/bt/visible" -> {
            if (!hasBtPerms()) { requestBtPerms(); result(false, "Acepta el permiso de Bluetooth y vuelve a pulsar.") }
            else {
                runOnUiThread {
                    try {
                        startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 180))
                    } catch (_: Exception) {}
                }
                result(true, "Acepta en el móvil y busca el móvil desde el Fire TV")
            }
        }

        "/api/bt/devices" -> {
            val a = JSONArray()
            bt.bonded().forEach { (name, addr) -> a.put(JSONObject().put("name", name).put("addr", addr)) }
            JSONObject().put("ok", true).put("devices", a)
        }

        "/api/bt/use" -> {
            val addr = d.optString("addr")
            if (!Regex("^[0-9A-Fa-f:]{17}$").matches(addr)) result(false, "Dispositivo no válido")
            else {
                prefs.edit().putString("bt_addr", addr).putBoolean("bt_on", true).apply()
                bt.wantedAddress = addr
                if (hasBtPerms()) bt.start()
                bt.tryConnectWanted()
                var waited = 0
                while (!bt.connected && waited < 8000) { Thread.sleep(250); waited += 250 }
                if (bt.connected) result(true, "Conectado por Bluetooth a ${bt.hostName()}")
                else result(false, "No se ha conectado todavía. Comprueba que el Fire TV tiene emparejado este móvil y vuelve a probar.")
            }
        }

        "/api/app" -> {
            val pkg = d.optString("pkg")
            if (!pkgRe.matches(pkg)) result(false, "Paquete inválido")
            else {
                val (ok, out) = shell("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
                if (out.contains("No activities found")) result(false, "Esa app no está instalada o no se puede abrir.")
                else result(ok, out)
            }
        }

        "/api/packages" -> {
            val (ok, out) = shell("pm list packages -3")
            val list = out.lines().filter { it.startsWith("package:") }
                .map { it.removePrefix("package:").trim() }.sorted()
            JSONObject().put("ok", ok).put("packages", JSONArray(list)).put("msg", if (ok) "" else out)
        }

        "/api/apps" -> {
            val src = d.optJSONArray("apps") ?: JSONArray()
            val clean = JSONArray()
            for (i in 0 until minOf(src.length(), 40)) {
                val a = src.optJSONObject(i) ?: continue
                val name = a.optString("name").trim().take(30)
                val pkg = a.optString("pkg").trim()
                if (name.isNotEmpty() && pkgRe.matches(pkg)) clean.put(JSONObject().put("name", name).put("pkg", pkg))
            }
            prefs.edit().putString("apps", clean.toString()).apply()
            JSONObject().put("ok", true).put("apps", clean)
        }

        else -> result(false, "No encontrado")
    }

    // ------------------------------------------------------------ ADB
    // ------------------------------------------------------------ varias teles
    // Los ajustes de la tele activa viven en las claves normales; las de las demás, guardadas con prefijo pN_
    private val TV_KEYS = listOf("ip", "apps", "lg_ip", "lg_key", "ir_set", "ir_set_name", "ir_on", "bt_on", "bt_addr")

    private fun tvIds(): List<Int> {
        val raw = prefs.getString("tv_ids", null)
        if (raw == null) { saveTvIds(listOf(1)); prefs.edit().putInt("tv_current", 1).apply(); return listOf(1) }
        return raw.split(",").mapNotNull { it.trim().toIntOrNull() }.ifEmpty { listOf(1) }
    }
    private fun saveTvIds(ids: List<Int>) { prefs.edit().putString("tv_ids", ids.joinToString(",")).apply() }
    private fun currentTv(): Int { tvIds(); return prefs.getInt("tv_current", 1) }
    private fun tvName(id: Int): String = prefs.getString("tv_name_$id", null) ?: "Tele $id"
    private fun tvList(): JSONArray {
        val a = JSONArray()
        for (id in tvIds()) a.put(JSONObject().put("id", id).put("name", tvName(id)))
        return a
    }

    private fun switchTv(id: Int) {
        val cur = currentTv()
        if (id == cur) return
        closeConnection()
        lg.close()
        val all = prefs.all
        val e = prefs.edit()
        for (k in TV_KEYS) {
            // guardar la tele actual
            when (val v = all[k]) {
                null -> e.remove("p${cur}_$k")
                is Boolean -> e.putBoolean("p${cur}_$k", v)
                else -> e.putString("p${cur}_$k", v.toString())
            }
            // cargar la nueva
            when (val v = all["p${id}_$k"]) {
                null -> e.remove(k)
                is Boolean -> e.putBoolean(k, v)
                else -> e.putString(k, v.toString())
            }
        }
        e.putInt("tv_current", id)
        e.commit()
        // Bluetooth: conectar con el Fire TV de esta tele
        bt.wantedAddress = prefs.getString("bt_addr", null)
        if (btOn()) { if (hasBtPerms()) bt.start(); bt.tryConnectWanted() } else bt.disconnect()
    }

    private fun btOn(): Boolean = prefs.getBoolean("bt_on", false)

    private fun hasBtPerms(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED &&
               checkSelfPermission("android.permission.BLUETOOTH_ADVERTISE") == PackageManager.PERMISSION_GRANTED
    }

    private fun requestBtPerms() {
        if (Build.VERSION.SDK_INT >= 31) runOnUiThread {
            requestPermissions(arrayOf("android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_ADVERTISE"), 7)
        }
    }

    private fun irOn(): Boolean = prefs.getBoolean("ir_on", false)

    private fun ip(): String = prefs.getString("ip", "") ?: ""

    private fun apps(): JSONArray = try {
        JSONArray(prefs.getString("apps", null) ?: defaultApps)
    } catch (e: Exception) {
        JSONArray(defaultApps)
    }

    private fun keyPair(): AdbKeyPair {
        val priv = File(filesDir, "adbkey")
        val pub = File(filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    private fun connection(): Dadb {
        dadb?.let { return it }
        val ip = ip()
        if (ip.isEmpty()) throw IllegalStateException("Configura primero la IP del Fire TV.")
        val host = ip.substringBefore(":")
        val port = if (":" in ip) ip.substringAfter(":").toInt() else 5555
        return Dadb.create(host, port, keyPair()).also { dadb = it }
    }

    // ------------------------------------------------------------ canal rápido (monkey)
    private fun closeMonkey() {
        try { monkeySock?.close() } catch (_: Exception) {}
        try { monkeyProc?.close() } catch (_: Exception) {}
        monkeySock = null
        monkeyProc = null
    }

    private fun drain(stream: AdbStream) {
        Thread {
            try { while (true) { if (stream.source.readUtf8Line() == null) break } } catch (_: Exception) {}
        }.apply { isDaemon = true }.start()
    }

    /** Abre (o reutiliza) el monkey en modo red dentro del Fire TV. */
    private fun monkey(): AdbStream? {
        monkeySock?.let { return it }
        if (System.currentTimeMillis() - monkeyFailedAt < 30_000) return null   // si falló hace poco, usa el modo normal
        val conn = try { connection() } catch (e: Exception) { return null }
        // ¿Ya hay uno escuchando (de una vez anterior)?
        try { return conn.open("tcp:1080").also { monkeySock = it } } catch (_: Exception) {}
        try {
            monkeyProc = conn.open("shell:monkey --port 1080").also { drain(it) }
            repeat(15) {
                Thread.sleep(200)
                try { return conn.open("tcp:1080").also { monkeySock = it } } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        closeMonkey()
        monkeyFailedAt = System.currentTimeMillis()
        return null
    }

    /** Envía una tecla: primero por el canal rápido; si no va, con "input keyevent". */
    private fun sendKey(code: Int, long: Boolean): Pair<Boolean, String> {
        // Volumen y silencio: primero infrarrojos del móvil, luego la tele LG por WiFi, si no el Fire TV
        if (irOn()) ir.cmdForKey(code)?.let { return ir.send(it) }
        if (lg.configured() && (code == 24 || code == 25 || code == 164)) return lg.key(code)
        if (btOn() && bt.connected) {
            if (bt.sendKey(code, long) == true) return true to ""
            // tecla sin equivalente Bluetooth (p. ej. Ajustes) o fallo: se intenta por WiFi
        }
        if (!long) {
            val sock = monkey()
            if (sock != null) {
                try {
                    val name = KeyEvent.keyCodeToString(code)
                    sock.sink.writeUtf8("press $name\n")
                    sock.sink.flush()
                    // Esperar la confirmación del Fire TV; si no llega, el canal está colgado
                    val reply = monkeyReader.submit<String?> { sock.source.readUtf8Line() }
                        .get(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (reply != null && reply.startsWith("OK")) {
                        connected = true
                        return true to ""
                    }
                    closeMonkey()
                } catch (_: Exception) {
                    closeMonkey()
                    monkeyFailedAt = System.currentTimeMillis()
                }
            }
        }
        val longFlag = if (long) "--longpress " else ""
        return shell("input keyevent $longFlag$code")
    }

    private fun closeConnection() {
        closeMonkey()
        try { dadb?.close() } catch (_: Exception) {}
        dadb = null
        connected = false
    }

    /** Ejecuta una orden en el Fire TV; si la conexión se cayó, reconecta una vez. */
    private fun shell(cmd: String): Pair<Boolean, String> {
        var last: Exception? = null
        repeat(2) {
            try {
                val r = connection().shell(cmd)
                connected = true
                return (r.exitCode == 0) to (r.output + r.errorOutput).trim()
            } catch (e: Exception) {
                last = e
                closeConnection()
                if (e is IllegalStateException) return false to (e.message ?: "")
            }
        }
        return false to friendly(last)
    }

    private fun friendly(e: Exception?): String {
        val name = e?.javaClass?.simpleName ?: ""
        val msg = e?.message ?: ""
        return when {
            name.contains("Auth", true) ->
                "Acepta el aviso 'Permitir depuración' en la tele (marca 'Permitir siempre') y vuelve a conectar."
            name.contains("Connect", true) || name.contains("Timeout", true) || msg.contains("refused", true) ->
                "No puedo conectar con el Fire TV. Revisa la IP, que esté encendido y que la Depuración ADB esté activada."
            else -> "Error: $name ${msg}".trim()
        }
    }

    private fun escapeText(t: String): String {
        val sb = StringBuilder()
        for (c in t.take(200)) {
            when {
                c == ' ' -> sb.append("%s")
                c.code < 128 && c.isLetterOrDigit() -> sb.append(c)
                c.code in 33..126 -> sb.append('\\').append(c)
                // caracteres no ASCII (ñ, tildes…) se omiten: "input text" no los admite
            }
        }
        return sb.toString()
    }
}
