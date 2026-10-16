package com.rabie.bmwobd.obd

/**
 * Lo que la centralita del motor guarda de la bateria (gestion de energia) y de los inyectores,
 * pedido una vez al conectar. Solo lectura.
 */
data class BmwCarInfo(
    val battery: BmwBattery? = null,
    val ima: BmwIma? = null,
)

/**
 * Los campos de la bateria cuyas posiciones estan comprobadas con el coche: la documentacion de la
 * centralita (STATUS_SYSTEMCHECK_PM_INFO_1 y _2 de d70n47b0) da su orden pero no sus bytes. Los km
 * coinciden con el cuentakilometros y la carga sale igual en dos campos y sube conduciendo. La
 * capacidad y el estado de salud no se enseñan: su posicion y su escala no estan comprobadas.
 */
data class BmwBattery(
    /** La carga de ahora, en %. */
    val charge: Double,
    /** La carga de cada uno de los cinco dias anteriores, del mas reciente al mas antiguo. */
    val chargeDays: List<Double>,
    /** La carga por debajo de la cual la centralita considera que puede no arrancar, en %. */
    val startLimit: Double,
    /** La temperatura de la temporada que apunta la centralita, en °C. */
    val seasonTemperature: Int,
    /** El cuentakilometros de hoy y de los cinco dias anteriores. */
    val kmDays: List<Int>,
    /**
     * A que km se registro el ultimo cambio de bateria, o null si no hay ninguno. La documentacion
     * nombra cinco cambios, pero en el coche solo el primero tiene un valor claro: se toma el mayor
     * de los que no pasan del cuentakilometros.
     */
    val lastChange: Int?,
    /** Horas con la bateria bajo 0 °C, de 0 a 20, de 20 a 40, de 40 a 60 y a mas de 60. */
    val hoursByTemperature: List<Double>,
) {
    companion object {
        const val QUERY_1 = "224022"
        const val ANSWER_1 = "624022"
        const val QUERY_2 = "224023"
        const val ANSWER_2 = "624023"

        private const val PERCENT_PER_BIT = 0.5
        private const val KM_PER_BIT = 10
        private const val MINUTES_PER_BIT = 5.0
        private const val TEMPERATURE_FIELDS = 5
        private const val DAYS = 5

        // En la respuesta a 22 40 22, a partir del dato.
        private const val TEMPERATURES_AT = 14
        private const val KM_AT = 24
        private const val CHANGES_AT = 36
        private const val FIRST_SIZE = CHANGES_AT + 2 * DAYS

        // En la respuesta a 22 40 23.
        private const val SEASON_AT = 3
        private const val START_LIMIT_AT = 11
        private const val CHARGE_AT = 17
        private const val SECOND_SIZE = CHARGE_AT + DAYS + 1

        /** Null si falta alguna de las dos respuestas o no tiene el tamaño de la del coche. */
        fun parse(first: IntArray?, second: IntArray?): BmwBattery? {
            if (first == null || second == null || first.size < FIRST_SIZE || second.size < SECOND_SIZE) return null
            fun word(at: Int) = first[at] * 256 + first[at + 1]
            return BmwBattery(
                charge = second[CHARGE_AT] * PERCENT_PER_BIT,
                chargeDays = (1..DAYS).map { second[CHARGE_AT + it] * PERCENT_PER_BIT },
                startLimit = second[START_LIMIT_AT] * PERCENT_PER_BIT,
                seasonTemperature = second[SEASON_AT].toByte().toInt(),
                kmDays = (0..DAYS).map { word(KM_AT + it * 2) * KM_PER_BIT },
                lastChange = (0 until DAYS).map { word(CHANGES_AT + it * 2) * KM_PER_BIT }
                    .filter { it in 1..word(KM_AT) * KM_PER_BIT }.maxOrNull(),
                hoursByTemperature = (0 until TEMPERATURE_FIELDS).map { word(TEMPERATURES_AT + it * 2) * MINUTES_PER_BIT / 60.0 },
            )
        }
    }
}

/**
 * Los codigos de calibracion de los cuatro inyectores (ABGLEICH_IMA_LESEN: servicio 30, identificador
 * BB, leer el estado actual). La documentacion dice que van "en el formato del inyector", pero no
 * cuantos bytes ni como: si son letras se enseñan como texto, y si no, en hexadecimal tal cual.
 * Si la centralita lo rechaza, [refusal] lleva su codigo de motivo.
 */
data class BmwIma(
    val codes: List<String> = emptyList(),
    val text: Boolean = false,
    val refusal: Int? = null,
) {
    companion object {
        const val QUERY = "30BB01"
        const val ANSWER = "70BB01"
        const val REFUSAL = "7F30"
        private const val INJECTORS = 4

        fun parse(response: String?): BmwIma? {
            if (response == null) return null
            ObdParser.payloads(response, REFUSAL).firstOrNull { it.isNotEmpty() }?.let { return BmwIma(refusal = it[0]) }
            val data = ObdParser.payloads(response, ANSWER).lastOrNull()?.takeIf { it.isNotEmpty() } ?: return null
            val text = data.all { it in 0x20..0x7E }
            val size = data.size / INJECTORS
            val parts = if (size > 0 && data.size % INJECTORS == 0) {
                (0 until INJECTORS).map { data.copyOfRange(it * size, it * size + size) }
            } else {
                listOf(data)
            }
            val codes = parts.map { part ->
                if (text) part.joinToString("") { it.toChar().toString() }.trim() else part.joinToString(" ") { "%02X".format(it) }
            }
            return BmwIma(codes = codes, text = text)
        }

        /** El motivo de un rechazo, segun KWP2000. */
        fun refusalText(code: Int): String = when (code) {
            0x11 -> "no conoce el servicio"
            0x12 -> "no admite esa forma de pedirlo"
            0x22 -> "no se dan las condiciones"
            0x31 -> "fuera de rango"
            0x33 -> "hace falta un acceso de seguridad"
            0x78 -> "sigue procesando"
            0x80 -> "no se admite en la sesión de diagnosis actual"
            else -> "motivo %02X".format(code)
        }
    }
}
