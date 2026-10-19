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

        /**
         * Las medidas que no se pueden leer de verdad con estas averias presentes: con el sensor
         * de nivel de aceite averiado, el nivel que da la centralita es un valor viejo, y la
         * temperatura de aceite que publica es la del refrigerante.
         */
        fun unreadable(faults: List<BmwFault>?): Set<Int> =
            if (faults.orEmpty().any { it.active && it.code in OIL_SENSOR }) OIL_MEASURES else emptySet()

        private val OIL_SENSOR = setOf(0x498E, 0x3EFC)

        /** Filtro muy cargado (contrapresion alta) y por encima del maximo. */
        const val FILTER_HIGH = 0x480A
        const val FILTER_MAX = 0x481A

        /**
         * 2 si 481A esta presente, 1 si solo lo esta 480A, 0 si ninguna; null si no se leyeron.
         * Mientras una de las dos este presente la centralita no hace regeneraciones normales
         * (boletin de BMW SI B18 03 10).
         */
        fun filterLevel(faults: List<BmwFault>?): Double? = faults?.let { list ->
            when {
                list.any { it.active && it.code == FILTER_MAX } -> 2.0
                list.any { it.active && it.code == FILTER_HIGH } -> 1.0
                else -> 0.0
            }
        }

        /** Cuantas averias del precalentamiento (calentadores y su mando) estan presentes; null si no se leyeron. */
        fun glowCount(faults: List<BmwFault>?): Double? =
            faults?.count { it.active && it.text?.contains("alentador", ignoreCase = true) == true }?.toDouble()

        /** Las averias de los calentadores y de su mando, presentes o guardadas. */
        fun glow(faults: List<BmwFault>): List<BmwFault> = faults.filter { it.text?.contains("alentador", ignoreCase = true) == true }

        /** El cilindro de una averia de calentador, del texto de la centralita; null si es del mando en general. */
        fun cylinder(fault: BmwFault): Int? = fault.text?.let { CYLINDER.find(it) }?.groupValues?.get(1)?.toInt()

        /** Los cilindros con averia de calentador presente, un digito por cilindro (14 = el 1 y el 4); 0 si ninguno. */
        fun glowCylinders(faults: List<BmwFault>?): Double? = faults?.let { list ->
            glow(list).filter { it.active }.mapNotNull(::cylinder).distinct().sorted().joinToString("").ifEmpty { "0" }.toDouble()
        }

        /** Las averias de calentadores en una linea: cada una con si esta presente o solo guardada. */
        fun glowText(faults: List<BmwFault>): String = glow(faults).joinToString("; ") { fault ->
            "${fault.label} ${if (fault.active) "presente" else "guardada, no presente"} (${fault.text}${fault.kind?.let { " · $it" } ?: ""})"
        }.ifEmpty { "ninguna avería de calentadores, ni presente ni guardada" }

        private val CYLINDER = Regex("cilindro (\\d)")

        /** Lo que la app apunta de las averias en el trayecto, por su columna. */
        fun appValues(faults: List<BmwFault>?): Map<Int, Double> = listOfNotNull(
            filterLevel(faults)?.let { Pids.BMW_FILTER_FAULTS to it },
            glowCount(faults)?.let { Pids.BMW_GLOW_FAULT_COUNT to it },
            glowCylinders(faults)?.let { Pids.BMW_GLOW_FAULT_CYLINDERS to it },
        ).toMap()

        fun filterLevelText(level: Double): String = when {
            level >= 2 -> "481A presente: filtro por encima del máximo"
            level >= 1 -> "480A presente: filtro muy cargado"
            else -> "480A y 481A no presentes"
        }
        private val OIL_MEASURES = setOf(Pids.bmw(Pids.BMW_OIL_LEVEL_ADDRESS), Pids.OIL)

        /** 480A y 481A, las del filtro que impiden la regeneracion normal mientras estan presentes. */
        val FILTER_CODES = listOf(FILTER_HIGH, FILTER_MAX)

        /**
         * Borrar una sola averia: 14 y su codigo (trabajo FS_LOESCHEN de d70n47b0, servicio
         * ClearDiagnosticInformation con un codigo concreto). Las demas se quedan como estan.
         * Contesta 54 si lo acepta y 7F 14 con el motivo si no.
         */
        fun clearQuery(code: Int) = "14%04X".format(code)
        const val CLEAR_ANSWER = "54"
        const val CLEAR_REFUSED = "7F14"

        /** La consulta que las pide todas, con cualquier estado. */
        const val QUERY = "1802FFFF"
        const val ANSWER = "58"

        /**
         * La memoria de informacion: avisos que la centralita apunta sin encender ningun testigo.
         * La respuesta, tras 62 20 00, lleva lo mismo que la de las averias: cuantos y, por cada uno,
         * codigo y estado. El 14 de octubre dio 4CE0, la regeneracion de proteccion del filtro.
         */
        const val INFO_QUERY = "222000"
        const val INFO_ANSWER = "622000"

        /**
         * El historial (HISTORY_BUFFER_LESEN de d70n47b0): tras 62 21 00, lo mismo que la memoria
         * de averias, cuantas y por cada una codigo y estado. Una averia sale una vez por cada vez
         * que se apunto. Comprobado con la respuesta del coche del 19 de octubre: 14 entradas, cinco
         * de ellas 480A.
         */
        const val HISTORY_QUERY = "222100"
        const val HISTORY_ANSWER = "622100"

        fun parseHistory(payload: IntArray): List<BmwFault> = parse(payload).filter { it.code != 0 }

        /** El detalle de una averia: 17 y su codigo. Contesta con 57. */
        fun detailQuery(code: Int) = "17%04X".format(code)
        const val DETAIL_ANSWER = "57"

        /**
         * El kilometraje en que se apunto la averia, del detalle. Tras el 57 vienen cuantas, el
         * codigo, el estado y el entorno; el kilometraje va en los bytes 9 y 10, en pasos de 8 km
         * (la tabla lo da de 0 a 524.280 km). Comprobado el 14 de octubre: las averias de la junta
         * del escape salieron entre 379.136 y 379.160 km, y el coche tenia 379.192 la tarde del 13.
         * Del resto del detalle no se sabe con seguridad que es cada byte y no se enseña.
         */
        fun detailKm(payload: IntArray, code: Int): Int? {
            if (payload.size <= DETAIL_KM_LOW) return null
            if (payload[1] * 256 + payload[2] != code) return null
            val km = (payload[DETAIL_KM_HIGH] * 256 + payload[DETAIL_KM_LOW]) * DETAIL_KM_STEP
            return km.takeIf { it > 0 }
        }

        private const val DETAIL_KM_HIGH = 9
        private const val DETAIL_KM_LOW = 10
        private const val DETAIL_KM_STEP = 8

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
