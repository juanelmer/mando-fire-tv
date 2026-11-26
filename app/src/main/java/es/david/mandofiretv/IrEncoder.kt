package es.david.mandofiretv

/**
 * Convierte un código IR en notación (protocolo, device, subdevice, function) —la que usa IRDB—
 * en la secuencia de pulsos (microsegundos) que necesita el emisor del móvil.
 * Duraciones positivas = luz encendida (mark), negativas = apagada (space).
 */
object IrEncoder {

    class Signal(val freq: Int, val pattern: IntArray)

    private class Frame {
        val d = ArrayList<Int>()
        var elapsed = 0
        fun add(vararg us: Int) { for (x in us) { d += x; elapsed += kotlin.math.abs(x) } }
        fun bits(value: Int, n: Int, msb: Boolean, zero: IntArray, one: IntArray) {
            for (k in 0 until n) {
                val i = if (msb) n - 1 - k else k
                add(*(if ((value shr i) and 1 == 1) one else zero))
            }
        }
        /** Rellena con silencio hasta que la trama dure totalUs */
        fun extent(totalUs: Int) { val rest = totalUs - elapsed; if (rest > 0) add(-rest) else add(-1000) }
    }

    private var toggle = 0

    fun supported(protocol: String): Boolean = norm(protocol) in SUPPORTED

    private val SUPPORTED = setOf(
        "NEC", "NEC1", "NEC2", "NECX", "NECX1", "NECX2", "PIONEER",
        "SONY12", "SONY15", "SONY20", "RC5", "RC6", "PANASONIC", "JVC",
        "DENON", "SHARP", "RCA", "RCA38", "MITSUBISHI"
    )

    private fun norm(p: String) = p.uppercase().replace("-", "").replace("_", "").replace(" ", "")

    fun encode(protocol: String, dev: Int, sub: Int, fn: Int): Signal? {
        val p = norm(protocol)
        val f = Frame()
        var freq = 38000
        var repeats = 1
        when (p) {
            "NEC", "NEC1", "NEC2", "PIONEER" -> {
                val u = 564
                val s = if (sub < 0) 255 - dev else sub
                f.add(16 * u, -8 * u)
                for (v in intArrayOf(dev, s, fn, fn.inv() and 0xFF)) f.bits(v, 8, false, intArrayOf(u, -u), intArrayOf(u, -3 * u))
                f.add(u); f.extent(108000)
                if (p == "PIONEER") freq = 40000
            }
            "NECX", "NECX1", "NECX2" -> {
                val u = 564
                val s = if (sub < 0) dev else sub
                f.add(8 * u, -8 * u)
                for (v in intArrayOf(dev, s, fn, fn.inv() and 0xFF)) f.bits(v, 8, false, intArrayOf(u, -u), intArrayOf(u, -3 * u))
                f.add(u); f.extent(108000)
            }
            "SONY12", "SONY15", "SONY20" -> {
                val u = 600
                freq = 40000; repeats = 3
                f.add(4 * u, -u)
                val zero = intArrayOf(u, -u); val one = intArrayOf(2 * u, -u)
                f.bits(fn, 7, false, zero, one)
                when (p) {
                    "SONY12" -> f.bits(dev, 5, false, zero, one)
                    "SONY15" -> f.bits(dev, 8, false, zero, one)
                    else -> { f.bits(dev, 5, false, zero, one); f.bits(maxOf(sub, 0), 8, false, zero, one) }
                }
                f.extent(45000)
            }
            "RC5" -> {
                val u = 889
                freq = 36000
                toggle = 1 - toggle
                val zero = intArrayOf(u, -u); val one = intArrayOf(-u, u)
                f.bits(1, 1, true, zero, one)
                f.bits(((fn shr 6) and 1).inv() and 1, 1, true, zero, one)
                f.bits(toggle, 1, true, zero, one)
                f.bits(dev, 5, true, zero, one)
                f.bits(fn and 0x3F, 6, true, zero, one)
                f.extent(114000)
            }
            "RC6" -> {
                val u = 444
                freq = 36000
                toggle = 1 - toggle
                val zero = intArrayOf(-u, u); val one = intArrayOf(u, -u)
                f.add(6 * u, -2 * u)
                f.bits(1, 1, true, zero, one)
                f.bits(0, 3, true, zero, one)                                       // modo 0
                f.bits(toggle, 1, true, intArrayOf(-2 * u, 2 * u), intArrayOf(2 * u, -2 * u))
                f.bits(dev, 8, true, zero, one)
                f.bits(fn, 8, true, zero, one)
                f.extent(107000)
            }
            "PANASONIC" -> {
                val u = 432
                freq = 37000
                val s = maxOf(sub, 0)
                f.add(8 * u, -4 * u)
                for (v in intArrayOf(2, 32, dev, s, fn, dev xor s xor fn)) f.bits(v, 8, false, intArrayOf(u, -u), intArrayOf(u, -3 * u))
                f.add(u, -173 * u)
            }
            "JVC" -> {
                val u = 525
                f.add(16 * u, -8 * u)
                f.bits(dev, 8, false, intArrayOf(u, -u), intArrayOf(u, -3 * u))
                f.bits(fn, 8, false, intArrayOf(u, -u), intArrayOf(u, -3 * u))
                f.add(u, -45 * u)
            }
            "DENON", "SHARP" -> {
                val u = 264
                val zero = intArrayOf(u, -3 * u); val one = intArrayOf(u, -7 * u)
                val (e1, e2) = if (p == "DENON") 0 to 3 else 1 to 2
                f.bits(dev, 5, false, zero, one); f.bits(fn, 8, false, zero, one); f.bits(e1, 2, false, zero, one)
                f.add(u, -165 * u)
                f.bits(dev, 5, false, zero, one); f.bits(fn.inv() and 0xFF, 8, false, zero, one); f.bits(e2, 2, false, zero, one)
                f.add(u, -165 * u)
            }
            "RCA", "RCA38" -> {
                val u = 460
                freq = if (p == "RCA") 56000 else 38000
                val zero = intArrayOf(u, -2 * u); val one = intArrayOf(u, -4 * u)
                f.add(8 * u, -8 * u)
                f.bits(dev, 4, true, zero, one); f.bits(fn, 8, true, zero, one)
                f.bits(dev.inv() and 0xF, 4, true, zero, one); f.bits(fn.inv() and 0xFF, 8, true, zero, one)
                f.add(u, -16 * u)
            }
            "MITSUBISHI" -> {
                val u = 300
                freq = 33000
                f.bits(dev, 8, false, intArrayOf(u, -3 * u), intArrayOf(u, -7 * u))
                f.bits(fn, 8, false, intArrayOf(u, -3 * u), intArrayOf(u, -7 * u))
                f.add(u, -80 * u)
            }
            else -> return null
        }
        val all = ArrayList<Int>()
        repeat(repeats) { all.addAll(f.d) }
        return Signal(freq, finish(all))
    }

    /** Junta tramos seguidos del mismo tipo, quita un silencio inicial y pasa a valores positivos */
    private fun finish(d: List<Int>): IntArray {
        val out = ArrayList<Int>()
        for (x in d) {
            if (x == 0) continue
            if (out.isEmpty()) { if (x < 0) continue; out += x; continue }
            val last = out.last()
            if ((last > 0) == (x > 0)) out[out.size - 1] = last + x else out += x
        }
        return out.map { kotlin.math.abs(it) }.toIntArray()
    }
}
