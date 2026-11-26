package es.david.mandofiretv

import android.content.Context
import android.content.SharedPreferences
import android.hardware.ConsumerIrManager
import org.json.JSONObject

/**
 * Mando universal de infrarrojos usando el emisor IR del móvil.
 * Por defecto lleva los códigos de LG; se puede elegir cualquier marca/mando de IRDB.
 */
class IrTv(context: Context, private val prefs: SharedPreferences) {

    private val ir: ConsumerIrManager? =
        context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager

    val db = IrDb(context.filesDir)

    fun available(): Boolean = try { ir?.hasIrEmitter() == true } catch (_: Exception) { false }

    // Códigos LG integrados (NEC1, device 4): funcionan sin Internet
    private val builtinLg = mapOf(
        "power" to 8, "volup" to 2, "voldown" to 3, "mute" to 9,
        "chup" to 0, "chdown" to 1, "input" to 11
    ).mapValues { IrDb.Code("NEC1", 4, -1, it.value) }

    /** Mando elegido: nombre + teclas */
    fun setName(): String = prefs.getString("ir_set_name", null) ?: "LG (integrado)"

    private fun current(): Map<String, IrDb.Code> {
        val raw = prefs.getString("ir_set", null) ?: return builtinLg
        return try {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { IrDb.jsonToCode(o.getJSONObject(it)) }
        } catch (_: Exception) { builtinLg }
    }

    fun saveSet(name: String, map: Map<String, IrDb.Code>) {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, IrDb.codeToJson(v)) }
        prefs.edit().putString("ir_set", o.toString()).putString("ir_set_name", name).apply()
    }

    fun resetToLg() {
        prefs.edit().remove("ir_set").remove("ir_set_name").apply()
    }

    /** Código de tecla Android → orden IR (para volumen y silencio) */
    fun cmdForKey(code: Int): String? = when (code) {
        24 -> "volup"
        25 -> "voldown"
        164 -> "mute"
        else -> null
    }

    fun sendCode(c: IrDb.Code): Pair<Boolean, String> {
        val mgr = ir ?: return false to "Este móvil no tiene infrarrojos accesibles."
        if (!available()) return false to "Este móvil no tiene emisor de infrarrojos."
        val sig = IrEncoder.encode(c.protocol, c.dev, c.sub, c.fn)
            ?: return false to "Este mando usa el protocolo ${c.protocol}, que todavía no está soportado. Prueba otro mando de la lista."
        return try {
            mgr.transmit(sig.freq, sig.pattern)
            true to ""
        } catch (e: Exception) {
            false to "Error al enviar infrarrojos: ${e.message}"
        }
    }

    fun send(cmd: String): Pair<Boolean, String> {
        val c = current()[cmd] ?: return false to "Este mando no tiene la tecla '$cmd'."
        return sendCode(c)
    }
}
