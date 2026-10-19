package com.rabie.bmwobd.obd

import java.util.Locale

/**
 * Como estaba el motor cuando la centralita apunto una averia: las condiciones de entorno del
 * detalle (17 y el codigo). Segun la descripcion de la DDE (FUMWELTMATRIX, FUMWELTTEXTE y los
 * grupos ANALOG de d70n47b0), cada averia guarda revoluciones, refrigerante y presion maxima del
 * rail, y las veintiuna medidas de su grupo. El detalle trae dos fotos seguidas, la primera vez y
 * la ultima, cada una con su kilometraje delante. Comprobado con el detalle de 4D00 del 14 de
 * octubre: 379.160 km a 2.260 rpm con 52 mg y el modo normal, y 379.328 km con el modo de
 * proteccion, 464 km desde la regeneracion y 21,3 g de ceniza, lo mismo que se leia ese dia.
 */
data class EnvValue(val name: String, val value: Double, val unit: String, val translated: Boolean) {

    /** El valor con su unidad; el modo del motor en hexadecimal y la distancia en km. */
    val text: String
        get() = when (unit) {
            HEX -> "0x%08X".format(value.toLong())
            METERS -> String.format(Locale.US, "%.1f km", value / 1000).replace('.', ',')
            MILLIVOLTS -> if (value >= 1000) String.format(Locale.US, "%.1f V", value / 1000).replace('.', ',') else number(value) + " mV"
            else -> number(value) + if (unit.isEmpty()) "" else " $unit"
        }

    private fun number(v: Double): String {
        val decimals = when {
            kotlin.math.abs(v) >= 100 -> 0
            kotlin.math.abs(v) >= 10 -> 1
            else -> 2
        }
        return String.format(Locale.US, "%.${decimals}f", v).replace('.', ',')
    }

    private companion object {
        const val HEX = "hex"
        const val METERS = "m"
        const val MILLIVOLTS = "mV"
    }
}

/** Una foto de las condiciones: el kilometraje y los valores en el orden de la centralita. */
data class FaultSnapshot(val km: Int?, val values: List<EnvValue>)

object FaultEnvironment {

    private class Measure(val bytes: Int, val factor: Double, val offset: Double, val unit: String, val name: String, val translated: Boolean)

    private class Tables(val measures: Map<Int, Measure>, val faults: Map<Int, List<Int>>)

    private val tables: Tables by lazy {
        val measures = HashMap<Int, Measure>()
        val faults = HashMap<Int, List<Int>>()
        val stream = FaultEnvironment::class.java.getResourceAsStream(FILE)
        stream?.bufferedReader(Charsets.UTF_8)?.useLines { lines ->
            for (line in lines) {
                val c = line.split('\t')
                when (c.firstOrNull()) {
                    "U" -> if (c.size >= 8) {
                        measures[c[1].toInt(16)] = Measure(c[2].toInt(), c[3].toDouble(), c[4].toDouble(), c[5], c[6], c[7] == "1")
                    }
                    "F" -> if (c.size >= 3) faults[c[1].toInt(16)] = c[2].split(',').filter { it.isNotEmpty() }.map { it.toInt(16) }
                }
            }
        }
        Tables(measures, faults)
    }

    /**
     * Las fotos del detalle de [code] ([payload] es lo que sigue al 57). Vacio si la averia no
     * esta en la tabla o la respuesta no es de ella. Una foto que no cabe entera no se da.
     */
    fun snapshots(payload: IntArray, code: Int): List<FaultSnapshot> {
        if (payload.size <= HEADER || payload[1] * 256 + payload[2] != code) return emptyList()
        val ids = tables.faults[code] ?: return emptyList()
        val measures = ids.map { tables.measures[it] ?: return emptyList() }
        val block = KM_BYTES + measures.sumOf { it.bytes }
        val shots = ArrayList<FaultSnapshot>()
        var at = HEADER
        while (at + block <= payload.size) {
            val km = (payload[at] * 256 + payload[at + 1]) * KM_STEP
            var pos = at + KM_BYTES
            val values = measures.map { m ->
                val raw = (0 until m.bytes).fold(0L) { acc, i -> acc * 256 + payload[pos + i] }
                pos += m.bytes
                EnvValue(m.name, raw * m.factor + m.offset, m.unit, m.translated)
            }
            shots += FaultSnapshot(km.takeIf { it > 0 }, values)
            at += block
        }
        return shots
    }

    // Tras el 57: cuantas, el codigo, el estado y cinco bytes mas antes de la primera foto.
    private const val HEADER = 9
    private const val KM_BYTES = 2
    private const val KM_STEP = 8
    private const val FILE = "/entorno_dde.tsv"
}
