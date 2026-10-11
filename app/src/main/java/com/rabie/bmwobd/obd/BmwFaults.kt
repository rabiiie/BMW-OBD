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

    val text: String? get() = TEXTS[code]

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

        // Solo los codigos ya vistos en un coche, traducidos de la descripcion de la DDE 7 del N47.
        // Los demas salen con su numero, que se puede buscar.
        private val TEXTS = mapOf(
            0x498E to "Sensor de nivel de aceite",
            0x3EFC to "Sensor térmico de nivel de aceite",
            0x4A6E to "Calentador del cilindro 1: mando",
            0x480A to "Sistema del filtro de partículas",
        )
    }
}
