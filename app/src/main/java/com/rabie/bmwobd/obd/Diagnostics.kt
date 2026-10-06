package com.rabie.bmwobd.obd

/**
 * Lo que devuelve una lectura de averias. Una lista a null significa que el coche no contesto a
 * esa consulta; vacia, que contesto que no hay ninguna.
 */
data class DiagnosticsReport(
    val milOn: Boolean? = null,
    val dtcCount: Int? = null,
    val stored: List<String>? = null,
    val pending: List<String>? = null,
    val permanent: List<String>? = null,
    val freezeDtc: String? = null,
    val freeze: Map<Int, Double> = emptyMap(),
    val vin: String? = null,
    val calibration: String? = null,
    val readiness: Readiness? = null,
)

object Dtcs {

    /**
     * Codigos de una respuesta a los modos 03, 07 o 0A. En CAN el primer byte es el numero de
     * codigos y el resto van de dos en dos; en los protocolos antiguos no hay ese primer byte.
     * Los pares a cero son relleno.
     */
    fun parse(payloads: List<IntArray>): List<String> {
        val codes = LinkedHashSet<String>()
        for (data in payloads) {
            var i = data.size % 2
            while (i + 1 < data.size) {
                if (data[i] != 0 || data[i + 1] != 0) codes += code(data[i], data[i + 1])
                i += 2
            }
        }
        return codes.toList()
    }

    /** Dos bytes a su codigo: los dos primeros bits son la letra, y el resto, cuatro cifras. */
    fun code(high: Int, low: Int): String =
        "%c%d%X%02X".format("PCBU"[high shr 6], (high shr 4) and 3, high and 0xF, low)

    fun describe(code: String): String = known[code] ?: family(code)

    private fun family(code: String): String = when {
        code.startsWith("P1") || code.startsWith("P3") -> "Código propio del fabricante"
        code.startsWith("P00") || code.startsWith("P01") || code.startsWith("P21") || code.startsWith("P22") ->
            "Dosificación de aire y combustible"
        code.startsWith("P02") -> "Circuito de inyectores"
        code.startsWith("P03") -> "Encendido o fallo de combustión"
        code.startsWith("P04") || code.startsWith("P20") || code.startsWith("P24") -> "Control de emisiones"
        code.startsWith("P05") -> "Velocidad, ralentí y entradas auxiliares"
        code.startsWith("P06") -> "Centralita y sus salidas"
        code.startsWith("P07") || code.startsWith("P08") || code.startsWith("P09") -> "Cambio"
        code.startsWith("C") -> "Chasis"
        code.startsWith("B") -> "Carrocería"
        code.startsWith("U") -> "Comunicación entre centralitas"
        else -> "Sin descripción"
    }

    // Los genericos que tienen sentido en un diesel con turbo, EGR y filtro de particulas.
    private val known: Map<String, String> = mapOf(
        "P0016" to "Cigüeñal y árbol de levas desincronizados (distribución)",
        "P0045" to "Electroválvula de control del turbo: circuito abierto",
        "P0046" to "Electroválvula de control del turbo: funcionamiento incorrecto",
        "P0047" to "Electroválvula de control del turbo: señal baja",
        "P0048" to "Electroválvula de control del turbo: señal alta",
        "P0069" to "Presión de admisión y barométrica no concuerdan",
        "P0071" to "Sensor de temperatura ambiente: valor no plausible",
        "P0072" to "Sensor de temperatura ambiente: señal baja",
        "P0073" to "Sensor de temperatura ambiente: señal alta",
        "P0087" to "Presión del raíl demasiado baja",
        "P0088" to "Presión del raíl demasiado alta",
        "P0093" to "Fuga grande en el sistema de combustible",
        "P0100" to "Caudalímetro (MAF): fallo de circuito",
        "P0101" to "Caudalímetro (MAF): valor no plausible",
        "P0102" to "Caudalímetro (MAF): señal baja",
        "P0103" to "Caudalímetro (MAF): señal alta",
        "P0105" to "Sensor de presión de admisión: fallo de circuito",
        "P0106" to "Sensor de presión de admisión: valor no plausible",
        "P0107" to "Sensor de presión de admisión: señal baja",
        "P0108" to "Sensor de presión de admisión: señal alta",
        "P0110" to "Sensor de temperatura de admisión: fallo de circuito",
        "P0111" to "Sensor de temperatura de admisión: valor no plausible",
        "P0112" to "Sensor de temperatura de admisión: señal baja",
        "P0113" to "Sensor de temperatura de admisión: señal alta",
        "P0115" to "Sensor de temperatura del refrigerante: fallo de circuito",
        "P0116" to "Sensor de temperatura del refrigerante: valor no plausible",
        "P0117" to "Sensor de temperatura del refrigerante: señal baja",
        "P0118" to "Sensor de temperatura del refrigerante: señal alta",
        "P0128" to "El motor no alcanza la temperatura de trabajo (termostato)",
        "P0180" to "Sensor de temperatura del combustible: fallo de circuito",
        "P0181" to "Sensor de temperatura del combustible: valor no plausible",
        "P0182" to "Sensor de temperatura del combustible: señal baja",
        "P0183" to "Sensor de temperatura del combustible: señal alta",
        "P0190" to "Sensor de presión del raíl: fallo de circuito",
        "P0191" to "Sensor de presión del raíl: valor no plausible",
        "P0192" to "Sensor de presión del raíl: señal baja",
        "P0193" to "Sensor de presión del raíl: señal alta",
        "P0195" to "Sensor de temperatura del aceite: fallo de circuito",
        "P0196" to "Sensor de temperatura del aceite: valor no plausible",
        "P0197" to "Sensor de temperatura del aceite: señal baja",
        "P0198" to "Sensor de temperatura del aceite: señal alta",
        "P0200" to "Circuito de inyectores",
        "P0201" to "Inyector del cilindro 1: fallo de circuito",
        "P0202" to "Inyector del cilindro 2: fallo de circuito",
        "P0203" to "Inyector del cilindro 3: fallo de circuito",
        "P0204" to "Inyector del cilindro 4: fallo de circuito",
        "P0234" to "Sobrepresión del turbo",
        "P0235" to "Sensor de presión del turbo: fallo de circuito",
        "P0236" to "Sensor de presión del turbo: valor no plausible",
        "P0237" to "Sensor de presión del turbo: señal baja",
        "P0238" to "Sensor de presión del turbo: señal alta",
        "P0263" to "Cilindro 1: aportación desequilibrada",
        "P0266" to "Cilindro 2: aportación desequilibrada",
        "P0269" to "Cilindro 3: aportación desequilibrada",
        "P0272" to "Cilindro 4: aportación desequilibrada",
        "P0299" to "Falta de presión del turbo",
        "P0300" to "Fallos de combustión en varios cilindros",
        "P0301" to "Fallos de combustión en el cilindro 1",
        "P0302" to "Fallos de combustión en el cilindro 2",
        "P0303" to "Fallos de combustión en el cilindro 3",
        "P0304" to "Fallos de combustión en el cilindro 4",
        "P0335" to "Sensor de cigüeñal: fallo de circuito",
        "P0336" to "Sensor de cigüeñal: valor no plausible",
        "P0340" to "Sensor de árbol de levas: fallo de circuito",
        "P0341" to "Sensor de árbol de levas: valor no plausible",
        "P0380" to "Calentadores: fallo de circuito",
        "P0400" to "EGR: caudal incorrecto",
        "P0401" to "EGR: caudal insuficiente",
        "P0402" to "EGR: caudal excesivo",
        "P0403" to "EGR: fallo de circuito de mando",
        "P0404" to "EGR: funcionamiento incorrecto",
        "P0405" to "Sensor de posición de la EGR: señal baja",
        "P0406" to "Sensor de posición de la EGR: señal alta",
        "P0420" to "Catalizador: rendimiento por debajo del umbral",
        "P0470" to "Sensor de presión de escape: fallo de circuito",
        "P0471" to "Sensor de presión de escape: valor no plausible",
        "P0472" to "Sensor de presión de escape: señal baja",
        "P0473" to "Sensor de presión de escape: señal alta",
        "P0489" to "Mando de la EGR: señal baja",
        "P0490" to "Mando de la EGR: señal alta",
        "P0500" to "Sensor de velocidad del vehículo",
        "P0520" to "Sensor de presión de aceite: fallo de circuito",
        "P0521" to "Sensor de presión de aceite: valor no plausible",
        "P0522" to "Sensor de presión de aceite: señal baja",
        "P0523" to "Sensor de presión de aceite: señal alta",
        "P0544" to "Sensor de temperatura de gases de escape 1: fallo de circuito",
        "P0545" to "Sensor de temperatura de gases de escape 1: señal baja",
        "P0546" to "Sensor de temperatura de gases de escape 1: señal alta",
        "P0562" to "Tensión del sistema baja",
        "P0563" to "Tensión del sistema alta",
        "P0571" to "Interruptor de freno: fallo de circuito",
        "P0600" to "Fallo de comunicación de la centralita",
        "P0606" to "Fallo interno de la centralita",
        "P0638" to "Actuador de la mariposa: funcionamiento incorrecto",
        "P0641" to "Tensión de referencia A de los sensores",
        "P0651" to "Tensión de referencia B de los sensores",
        "P0670" to "Unidad de mando de los calentadores",
        "P0671" to "Calentador del cilindro 1",
        "P0672" to "Calentador del cilindro 2",
        "P0673" to "Calentador del cilindro 3",
        "P0674" to "Calentador del cilindro 4",
        "P0685" to "Relé principal de la centralita",
        "P0700" to "Avería en el control del cambio",
        "P0704" to "Interruptor de embrague: fallo de circuito",
        "P2002" to "Filtro de partículas: rendimiento por debajo del umbral",
        "P2031" to "Sensor de temperatura de gases de escape 2: fallo de circuito",
        "P2032" to "Sensor de temperatura de gases de escape 2: señal baja",
        "P2033" to "Sensor de temperatura de gases de escape 2: señal alta",
        "P2226" to "Sensor de presión barométrica: fallo de circuito",
        "P2227" to "Sensor de presión barométrica: valor no plausible",
        "P2228" to "Sensor de presión barométrica: señal baja",
        "P2229" to "Sensor de presión barométrica: señal alta",
        "P2263" to "Turbo: rendimiento incorrecto",
        "P2279" to "Fuga en la admisión",
        "P242F" to "Filtro de partículas obstruido por ceniza",
        "P244A" to "Filtro de partículas: presión diferencial demasiado baja",
        "P244B" to "Filtro de partículas: presión diferencial demasiado alta",
        "P2452" to "Sensor de presión del filtro de partículas: fallo de circuito",
        "P2453" to "Sensor de presión del filtro de partículas: valor no plausible",
        "P2454" to "Sensor de presión del filtro de partículas: señal baja",
        "P2455" to "Sensor de presión del filtro de partículas: señal alta",
        "P2458" to "Regeneración del filtro de partículas: duración incorrecta",
        "P2459" to "Regeneración del filtro de partículas: demasiado frecuente",
        "P2463" to "Filtro de partículas obstruido por hollín",
        "P2562" to "Sensor de posición del turbo: fallo de circuito",
        "P2563" to "Sensor de posición del turbo: valor no plausible",
        "P2564" to "Sensor de posición del turbo: señal baja",
        "P2565" to "Sensor de posición del turbo: señal alta",
    )
}
