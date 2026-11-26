package es.david.mandofiretv

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Acceso a IRDB (github.com/probonopd/irdb): base de datos abierta de códigos IR por marca.
 * Se descarga lo necesario y se guarda en caché en el móvil.
 */
class IrDb(private val dir: File) {

    private val base = "https://raw.githubusercontent.com/probonopd/irdb/master/codes/"
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun cacheFile(path: String) =
        File(dir, "irdb_" + path.replace(Regex("[^A-Za-z0-9,._-]"), "_"))

    private fun download(path: String): String {
        val url = base + path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw Exception("No se pudo descargar ($path): HTTP ${r.code}")
            return r.body?.string() ?: ""
        }
    }

    private fun cached(path: String, maxAgeMs: Long): String {
        val f = cacheFile(path)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < maxAgeMs) return f.readText()
        return try {
            download(path).also { f.writeText(it) }
        } catch (e: Exception) {
            if (f.exists()) f.readText() else throw Exception("Sin conexión a Internet para descargar los códigos. (${e.message})")
        }
    }

    /** Lista de todos los archivos de la base (marca/tipo/device,subdevice.csv) */
    fun index(): String = cached("index", 7L * 24 * 3600 * 1000)

    class Code(val protocol: String, val dev: Int, val sub: Int, val fn: Int)

    /** Lee un CSV: nombre de función → código */
    fun codes(path: String): List<Pair<String, Code>> {
        val text = cached(path, 365L * 24 * 3600 * 1000)
        val out = ArrayList<Pair<String, Code>>()
        for (line in text.lines().drop(1)) {
            // functionname puede llevar comas: los 4 últimos campos son fijos
            val parts = line.trim().split(",")
            if (parts.size < 5) continue
            val n = parts.size
            try {
                val name = parts.subList(0, n - 4).joinToString(",").trim().trim('"')
                out += name to Code(parts[n - 4].trim(), parts[n - 3].trim().toInt(), parts[n - 2].trim().toInt(), parts[n - 1].trim().toInt())
            } catch (_: Exception) {}
        }
        return out
    }

    /** Busca en el CSV las teclas que usa la app */
    fun mapButtons(list: List<Pair<String, Code>>): Map<String, Code> {
        fun n(s: String) = " " + s.uppercase().replace("_", " ").replace("-", " - ").replace("+", " + ") + " "
        val items = list.map { n(it.first) to it.second }
        fun find(vararg tests: (String) -> Boolean): Code? {
            for (t in tests) items.firstOrNull { t(it.first) }?.let { return it.second }
            return null
        }
        val vol = { s: String -> s.contains("VOL") }
        val ch = { s: String -> s.contains(" CH") || s.contains("CHAN") || s.contains("PROG") || s.contains(" P ") }
        val up = { s: String -> s.contains(" + ") || s.contains("UP") }
        val down = { s: String -> s.contains(" - ") || s.contains("DOWN") || s.contains(" DN") }
        val m = HashMap<String, Code>()
        find({ it.trim() == "POWER" }, { it.contains("POWER") && it.contains("TOGGLE") },
            { it.contains("POWER") && !it.contains(" ON") && !it.contains("OFF") }, { it.contains("POWER") }, { it.contains("STANDBY") })
            ?.let { m["power"] = it }
        find({ vol(it) && up(it) })?.let { m["volup"] = it }
        find({ vol(it) && down(it) })?.let { m["voldown"] = it }
        find({ it.contains("MUTE") })?.let { m["mute"] = it }
        find({ ch(it) && up(it) && !vol(it) })?.let { m["chup"] = it }
        find({ ch(it) && down(it) && !vol(it) })?.let { m["chdown"] = it }
        find({ it.contains("INPUT") }, { it.contains("SOURCE") }, { it.contains("TV/AV") || it.contains("TV/VIDEO") }, { it.trim() == "AV" })
            ?.let { m["input"] = it }
        return m
    }

    companion object {
        fun codeToJson(c: Code) = JSONObject().put("p", c.protocol).put("d", c.dev).put("s", c.sub).put("f", c.fn)
        fun jsonToCode(o: JSONObject) = Code(o.getString("p"), o.getInt("d"), o.getInt("s"), o.getInt("f"))
    }
}
