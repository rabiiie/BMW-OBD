package com.rabie.bmwobd.obd

/** Una autocomprobacion del coche: si este coche la tiene y si ya la ha terminado. */
data class Monitor(val name: String, val available: Boolean, val complete: Boolean)

/**
 * Estado de las autocomprobaciones. Tras borrar averias o desconectar la bateria vuelven todas a
 * "sin completar" y se van terminando con el uso normal del coche.
 */
data class Readiness(val diesel: Boolean, val monitors: List<Monitor>) {
    val available: List<Monitor> get() = monitors.filter { it.available }
    val pending: List<Monitor> get() = available.filter { !it.complete }

    companion object {

        private val CONTINUOUS = listOf("Fallos de combustión", "Sistema de combustible", "Componentes")

        private val SPARK = listOf(
            "Catalizador", "Catalizador calefactado", "Vapores de combustible", "Aire secundario",
            "Refrigerante del aire acondicionado", "Sonda lambda", "Calefacción de la sonda lambda", "EGR o distribución variable",
        )

        private val COMPRESSION = listOf(
            "Catalizador de oxidación", "Tratamiento de NOx", null, "Presión de turbo",
            null, "Sensor de gases de escape", "Filtro de partículas", "EGR o distribución variable",
        )

        /**
         * Los cuatro bytes del PID 01. El segundo dice si el motor es diesel y lleva las tres
         * comprobaciones continuas; el tercero, cuales de las otras ocho existen; el cuarto, cuales
         * faltan por terminar. En los bits de "falta", un 1 es que no ha terminado.
         */
        fun parse(data: IntArray): Readiness? {
            if (data.size < 4) return null
            val b = data[1]
            val diesel = (b shr 3) and 1 == 1
            val monitors = mutableListOf<Monitor>()
            for (i in CONTINUOUS.indices) {
                monitors += Monitor(CONTINUOUS[i], (b shr i) and 1 == 1, (b shr (i + 4)) and 1 == 0)
            }
            val names = if (diesel) COMPRESSION else SPARK
            for (i in names.indices) {
                val name = names[i] ?: continue
                monitors += Monitor(name, (data[2] shr i) and 1 == 1, (data[3] shr i) and 1 == 0)
            }
            return Readiness(diesel, monitors)
        }
    }
}

/**
 * Resultado de una prueba interna del coche (modo 06): lo que midio y los limites que el
 * fabricante le puso. [raw], [rawMin] y [rawMax] son los numeros tal cual llegan; pasan a unidades
 * con la escala que indica [unitId].
 */
data class MonitorTest(val mid: Int, val tid: Int, val unitId: Int, val raw: Int, val rawMin: Int, val rawMax: Int) {

    private val scaling: Scaling get() = Mode06.scaling(unitId)

    /** Dentro de los limites del fabricante. No depende de conocer la unidad. */
    val passed: Boolean get() = raw in rawMin..rawMax

    val unit: String get() = scaling.unit
    val decimals: Int get() = scaling.decimals
    val value: Double get() = scaling.apply(raw)
    val min: Double get() = scaling.apply(rawMin)
    val max: Double get() = scaling.apply(rawMax)

    /** La escala de esta prueba esta en la tabla; si no, los numeros son los crudos. */
    val unitKnown: Boolean get() = Mode06.knows(unitId)
}

class Scaling(val factor: Double, val unit: String, val decimals: Int, val offset: Double = 0.0) {
    fun apply(raw: Int): Double = raw * factor + offset
}

object Mode06 {

    private const val RECORD_BYTES = 9
    private const val FIRST_SIGNED_UNIT = 0x80

    /**
     * Las pruebas de una respuesta: tras el 46 vienen registros de nueve bytes con monitor,
     * prueba, unidad, valor, minimo y maximo. Con una unidad de 0x80 en adelante los tres numeros
     * llevan signo.
     */
    fun parse(payload: IntArray): List<MonitorTest> {
        val tests = mutableListOf<MonitorTest>()
        var i = 0
        while (i + RECORD_BYTES <= payload.size) {
            val unitId = payload[i + 2]
            fun number(at: Int): Int {
                val word = payload[at] * 256 + payload[at + 1]
                return if (unitId >= FIRST_SIGNED_UNIT && word >= 0x8000) word - 0x10000 else word
            }
            tests += MonitorTest(payload[i], payload[i + 1], unitId, number(i + 3), number(i + 5), number(i + 7))
            i += RECORD_BYTES
        }
        return tests
    }

    fun scaling(unitId: Int): Scaling = SCALINGS[unitId] ?: RAW

    fun knows(unitId: Int): Boolean = unitId in SCALINGS

    /** A que sistema pertenece un monitor. Los de E1 en adelante los define cada fabricante. */
    fun midName(mid: Int): String = when (mid) {
        in 0x01..0x10 -> "Sonda lambda ${mid}"
        0x21, 0x22 -> "Catalizador"
        0x31, 0x32 -> "EGR"
        0x35, 0x36 -> "Distribución variable"
        in 0x39..0x3D -> "Vapores de combustible"
        in 0x41..0x50 -> "Calefacción de sonda lambda"
        0x71 -> "Aire secundario"
        0x81, 0x82 -> "Sistema de combustible"
        0x85, 0x86 -> "Presión de turbo"
        in 0x90..0x97 -> "Acumulador de NOx"
        0x98, 0x99 -> "Catalizador de NOx"
        0xA1 -> "Fallos de combustión"
        in 0xA2..0xAD -> "Fallos de combustión, cilindro ${mid - 0xA1}"
        in 0xB0..0xB3 -> "Filtro de partículas"
        in 0xE1..0xFF -> "Monitor del fabricante %02X".format(mid)
        else -> "Monitor %02X".format(mid)
    }

    private val RAW = Scaling(1.0, "", 0)

    // Escalas del estandar SAE J1979 para las unidades mas comunes; las de 0x80 en adelante son con signo.
    private val SCALINGS: Map<Int, Scaling> = mapOf(
        0x01 to Scaling(1.0, "", 0),
        0x02 to Scaling(0.1, "", 1),
        0x03 to Scaling(0.01, "", 2),
        0x04 to Scaling(0.001, "", 3),
        0x07 to Scaling(0.25, "rpm", 0),
        0x08 to Scaling(0.01, "km/h", 1),
        0x09 to Scaling(1.0, "km/h", 0),
        0x0A to Scaling(0.000122, "V", 3),
        0x0B to Scaling(0.001, "V", 3),
        0x0C to Scaling(0.01, "V", 2),
        0x0E to Scaling(0.001, "A", 3),
        0x0F to Scaling(0.01, "A", 2),
        0x10 to Scaling(1.0, "ms", 0),
        0x11 to Scaling(100.0, "ms", 0),
        0x12 to Scaling(1.0, "s", 0),
        0x14 to Scaling(1.0, "Ω", 0),
        0x16 to Scaling(0.1, "°C", 1, offset = -40.0),
        0x17 to Scaling(0.01, "kPa", 2),
        0x18 to Scaling(0.0117, "kPa", 2),
        0x19 to Scaling(0.079, "kPa", 1),
        0x1A to Scaling(1.0, "kPa", 0),
        0x1B to Scaling(10.0, "kPa", 0),
        0x1C to Scaling(0.01, "°", 2),
        0x1D to Scaling(0.5, "°", 1),
        0x1E to Scaling(0.0000305, "λ", 3),
        0x20 to Scaling(0.00390625, "", 3),
        0x24 to Scaling(1.0, "", 0),
        0x25 to Scaling(1.0, "km", 0),
        0x27 to Scaling(0.01, "g/s", 2),
        0x28 to Scaling(1.0, "g/s", 0),
        0x2B to Scaling(1.0, "", 0),
        0x2D to Scaling(0.01, "mg/embolada", 2),
        0x2F to Scaling(0.01, "%", 2),
        0x30 to Scaling(0.001526, "%", 2),
        0x34 to Scaling(1.0, "min", 0),
        0x35 to Scaling(10.0, "ms", 0),
        0x41 to Scaling(1.0, "ppm", 0),
        0x81 to Scaling(1.0, "", 0),
        0x82 to Scaling(0.1, "", 1),
        0x83 to Scaling(0.01, "", 2),
        0x84 to Scaling(0.001, "", 3),
        0x8B to Scaling(0.001, "V", 3),
        0x8C to Scaling(0.01, "V", 2),
        0x90 to Scaling(1.0, "ms", 0),
        0x96 to Scaling(0.1, "°C", 1),
        0x9C to Scaling(0.01, "°", 2),
        0x9D to Scaling(0.5, "°", 1),
        0xA8 to Scaling(1.0, "g/s", 0),
        0xAF to Scaling(0.01, "%", 2),
        0xB0 to Scaling(0.003052, "%", 2),
        0xFC to Scaling(0.01, "kPa", 2),
        0xFD to Scaling(0.001, "kPa", 3),
    )
}
