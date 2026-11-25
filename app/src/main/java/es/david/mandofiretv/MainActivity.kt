package es.david.mandofiretv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import dadb.AdbKeyPair
import dadb.Dadb
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var prefs: SharedPreferences
    private val exec = Executors.newSingleThreadExecutor()   // las órdenes ADB van en fila, una a una
    private var dadb: Dadb? = null
    @Volatile private var connected = false

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
        web = WebView(this)
        web.setBackgroundColor(0xFF111318.toInt())
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }

    override fun onDestroy() {
        exec.execute { try { dadb?.close() } catch (_: Exception) {} }
        exec.shutdown()
        super.onDestroy()
    }

    // Los botones de volumen del móvil controlan el volumen de la tele cuando hay conexión
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (connected && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            val code = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 24 else 25
            exec.execute { shell("input keyevent $code") }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------ puente JS
    inner class Bridge {
        @JavascriptInterface
        fun call(id: Int, path: String, body: String) {
            exec.execute {
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
            .put("ip", ip())
            .put("connected", connected)
            .put("apps", apps())

        "/api/connect" -> {
            val newIp = d.optString("ip", ip()).trim()
            if (newIp.isNotEmpty() && !ipRe.matches(newIp)) {
                result(false, "IP no válida")
            } else {
                if (newIp != ip()) {
                    prefs.edit().putString("ip", newIp).apply()
                    closeConnection()
                }
                val (ok, out) = shell("echo ok")
                if (ok && out.contains("ok")) result(true, "Conectado") else result(false, out)
            }
        }

        "/api/key" -> {
            val code = d.optInt("code", -1)
            if (code < 0) result(false, "Código inválido")
            else {
                val long = if (d.optBoolean("long")) "--longpress " else ""
                val (ok, out) = shell("input keyevent $long$code")
                result(ok, out)
            }
        }

        "/api/text" -> {
            val t = escapeText(d.optString("text"))
            if (t.isEmpty()) result(false, "Texto vacío o no válido")
            else { val (ok, out) = shell("input text $t"); result(ok, out) }
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

    private fun closeConnection() {
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
