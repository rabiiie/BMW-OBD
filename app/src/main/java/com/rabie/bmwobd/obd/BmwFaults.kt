package com.rabie.bmwobd.obd

/**
 * Una averia de la memoria propia de la centralita del motor de BMW. No sale por el OBD estandar,
 * que solo enseña las relacionadas con emisiones. [active] dice si esta presente ahora mismo o
 * solo guardada de antes.
 */
data class BmwFault(val code: Int, val status: Int) {

    val label: String get() = "%04X".format(code)

    /** En el estado, los bits 5 y 6 dicen si la averia esta presente (los dos) o solo guardada. */
    val active: Boolean get() = status and PRESENT == PRESENT

    /** Donde esta la averia, en español; null si la centralita da un codigo que no esta en su lista. */
    val text: String? get() = BmwFaultTexts.of(code)?.first

    /** Que le pasa a eso, segun la lista de la centralita: cortado, en corto, fuera de rango... */
    val kind: String? get() = BmwFaultTexts.of(code)?.second?.takeIf { it.isNotEmpty() }

    companion object {
        private const val PRESENT = 0x60
        private const val BYTES_PER_FAULT = 3

        /** La consulta que las pide todas, con cualquier estado. */
        const val QUERY = "1802FFFF"
        const val ANSWER = "58"

        /**
         * Lo que sigue al 58 de la respuesta: cuantas hay y, por cada una, dos bytes de codigo y
         * uno de estado.
         */
        fun parse(payload: IntArray): List<BmwFault> {
            if (payload.isEmpty()) return emptyList()
            val count = minOf(payload[0], (payload.size - 1) / BYTES_PER_FAULT)
            return List(count) { i ->
                val at = 1 + i * BYTES_PER_FAULT
                BmwFault(payload[at] * 256 + payload[at + 1], payload[at + 2])
            }
        }

    }
}

/**
 * Los textos de las 719 averias que conoce la DDE 7 del N47, traducidos de su descripcion
 * (d70n47b0): por cada codigo, el lugar y lo que le pasa. Se cargan la primera vez que hacen falta.
 */
internal object BmwFaultTexts {

    private val all: Map<Int, Pair<String, String>> by lazy {
        val stream = BmwFaultTexts::class.java.getResourceAsStream(FILE) ?: return@lazy emptyMap()
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.mapNotNull { line ->
                val cells = line.split('	')
                val code = cells[0].toIntOrNull(16) ?: return@mapNotNull null
                code to (cells.getOrElse(1) { "" } to cells.getOrElse(2) { "" })
            }.toMap()
        }
    }

    val size: Int get() = all.size

    fun of(code: Int): Pair<String, String>? = all[code]

    private const val FILE = "/averias_dde.tsv"
}
