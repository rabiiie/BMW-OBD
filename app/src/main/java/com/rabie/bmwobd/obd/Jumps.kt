package com.rabie.bmwobd.obd

import kotlin.math.abs

/**
 * Los saltos sueltos de las medidas de BMW: una lectura que se va lejos y vuelve en la siguiente.
 * Muchos son de verdad, como el pedal al cambiar de marcha; otros no tienen sentido fisico, como el
 * giro del turbo bajando a un tercio en medio segundo. Para saber si son respuestas de otra medida
 * del mismo tamaño, se apunta la trama del salto y si coincide con la ultima de alguna otra medida.
 */
class Jumps(private val nameOf: (Int) -> String) {

    private class Suspect(val before: Double, val jump: Double, val raw: String)

    private val last = HashMap<Int, Double>()
    private val lastRaw = HashMap<Int, String>()
    private val suspects = HashMap<Int, Suspect>()

    /** Lo apuntado, como lineas del registro; como mucho [MAX_FOUND]. */
    val found = ArrayList<String>()

    /** Lo que da cada lectura de una medida de BMW: su valor y los bytes de la respuesta. Devuelve la linea si cierra un salto. */
    fun seen(id: Int, value: Double, raw: String): String? {
        var line: String? = null
        val suspect = suspects.remove(id)
        if (suspect != null) {
            if (near(value, suspect.before)) {
                val twins = lastRaw.filter { (other, bytes) -> other != id && bytes == suspect.raw }.keys.map(nameOf)
                line = "## Salto suelto en ${nameOf(id)}: ${n(suspect.before)} -> ${n(suspect.jump)} -> ${n(value)}. " +
                    "Trama del salto: ${suspect.raw}. " +
                    if (twins.isEmpty()) "No coincide con la última de ninguna otra medida." else "Coincide con la última de: ${twins.joinToString(", ")}."
                if (found.size < MAX_FOUND) found += line
            }
            last[id] = value
        } else {
            val before = last[id]
            if (before != null && far(before, value)) suspects[id] = Suspect(before, value, raw) else last[id] = value
        }
        lastRaw[id] = raw
        return line
    }

    private fun span(a: Double, b: Double) = maxOf(abs(a), abs(b), 1.0)

    private fun far(a: Double, b: Double) = abs(a - b) > JUMP_SHARE * span(a, b) && abs(a - b) > JUMP_MIN

    private fun near(a: Double, b: Double) = abs(a - b) <= BACK_SHARE * span(a, b)

    private fun n(value: Double) = String.format(java.util.Locale.US, "%.2f", value)

    companion object {
        private const val JUMP_SHARE = 0.5
        private const val JUMP_MIN = 5.0
        private const val BACK_SHARE = 0.05
        const val MAX_FOUND = 40
    }
}
