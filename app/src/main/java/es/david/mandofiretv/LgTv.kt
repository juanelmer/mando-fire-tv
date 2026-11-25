package es.david.mandofiretv

import android.content.SharedPreferences
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Control directo de una tele LG webOS por WiFi (protocolo SSAP por WebSocket).
 * Se usa para volumen y silencio, que el Fire Stick no puede mandar a la tele.
 */
class LgTv(private val prefs: SharedPreferences) {

    // La tele usa un certificado propio (autofirmado): se acepta solo para esta conexión local
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val client: OkHttpClient = run {
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), SecureRandom()) }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    private var ws: WebSocket? = null
    @Volatile private var registered = false
    private var regFuture: CompletableFuture<Boolean>? = null
    private val pending = ConcurrentHashMap<String, CompletableFuture<JSONObject>>()
    private var seq = 0
    private var localMute = false

    fun ip(): String = prefs.getString("lg_ip", "") ?: ""
    fun configured() = ip().isNotEmpty()

    fun setIp(ip: String) {
        prefs.edit().putString("lg_ip", ip).apply()
        close()
    }

    @Synchronized
    fun close() {
        try { ws?.close(1000, null) } catch (_: Exception) {}
        ws = null
        registered = false
        pending.values.forEach { it.completeExceptionally(Exception("cerrado")) }
        pending.clear()
    }

    private fun registerMessage(): String {
        val manifest = JSONObject()
            .put("manifestVersion", 1)
            .put("appVersion", "1.2")
            .put("permissions", org.json.JSONArray(listOf(
                "CONTROL_AUDIO", "CONTROL_POWER", "CONTROL_INPUT_MEDIA_PLAYBACK",
                "READ_INSTALLED_APPS", "LAUNCH", "READ_RUNNING_APPS"
            )))
        val payload = JSONObject()
            .put("forcePairing", false)
            .put("pairingType", "PROMPT")
            .put("manifest", manifest)
        prefs.getString("lg_key", null)?.let { payload.put("client-key", it) }
        return JSONObject().put("type", "register").put("id", "register_0").put("payload", payload).toString()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(registerMessage())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = try { JSONObject(text) } catch (_: Exception) { return }
            val id = msg.optString("id")
            when (msg.optString("type")) {
                "registered" -> {
                    msg.optJSONObject("payload")?.optString("client-key")?.takeIf { it.isNotEmpty() }?.let {
                        prefs.edit().putString("lg_key", it).apply()
                    }
                    registered = true
                    regFuture?.complete(true)
                }
                "error" -> {
                    if (id == "register_0") regFuture?.completeExceptionally(Exception(msg.optString("error", "La tele rechazó la conexión")))
                    else pending.remove(id)?.complete(msg)
                }
                else -> if (id != "register_0") pending.remove(id)?.complete(msg)
                // "response" a register_0 = la tele está mostrando el aviso: se espera a "registered"
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            registered = false
            regFuture?.completeExceptionally(t)
            pending.values.forEach { it.completeExceptionally(t) }
            pending.clear()
            if (ws === webSocket) ws = null
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            registered = false
            if (ws === webSocket) ws = null
        }
    }

    /** Conecta y se registra. La primera vez la tele muestra un aviso que hay que aceptar. */
    @Synchronized
    fun connect(waitMs: Long): Pair<Boolean, String> {
        if (registered && ws != null) return true to "Conectado"
        val ip = ip()
        if (ip.isEmpty()) return false to "Configura la IP de la tele LG."
        close()
        var lastErr = ""
        for (url in listOf("wss://$ip:3001", "ws://$ip:3000")) {
            val f = CompletableFuture<Boolean>()
            regFuture = f
            ws = client.newWebSocket(Request.Builder().url(url).build(), listener)
            try {
                f.get(waitMs, TimeUnit.MILLISECONDS)
                return true to "Tele LG vinculada"
            } catch (e: java.util.concurrent.TimeoutException) {
                close()
                return false to "No se aceptó el aviso en la tele LG a tiempo. Vuelve a pulsar Vincular."
            } catch (e: Exception) {
                lastErr = (e.cause ?: e).message ?: e.toString()
                close()
            }
        }
        return false to "No puedo conectar con la tele LG ($lastErr). Revisa la IP y que la tele esté encendida."
    }

    private fun request(uri: String, payload: JSONObject? = null): JSONObject {
        val (ok, msg) = connect(8000)
        if (!ok) throw Exception(msg)
        val id = "req_" + (++seq)
        val f = CompletableFuture<JSONObject>()
        pending[id] = f
        val m = JSONObject().put("id", id).put("type", "request").put("uri", uri)
        if (payload != null) m.put("payload", payload)
        if (ws?.send(m.toString()) != true) {
            pending.remove(id)
            close()
            throw Exception("Se perdió la conexión con la tele LG")
        }
        return f.get(4, TimeUnit.SECONDS)
    }

    /** code: 24 subir, 25 bajar, 164 silencio */
    fun key(code: Int): Pair<Boolean, String> = try {
        val res = when (code) {
            24 -> request("ssap://audio/volumeUp")
            25 -> request("ssap://audio/volumeDown")
            else -> {
                val current = try {
                    request("ssap://audio/getStatus").optJSONObject("payload")?.optBoolean("mute", localMute) ?: localMute
                } catch (_: Exception) { localMute }
                localMute = !current
                request("ssap://audio/setMute", JSONObject().put("mute", localMute))
            }
        }
        if (res.optString("type") == "error") false to ("La tele LG dijo: " + res.optString("error"))
        else true to ""
    } catch (e: Exception) {
        false to ((e.cause ?: e).message ?: "Error con la tele LG")
    }
}
