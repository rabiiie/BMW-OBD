package com.rabie.bmwobd.obd

enum class PidGroup(val title: String) {
    ENGINE("Motor"),
    AIR("Turbo y admisión"),
    FUEL("Combustible"),
    EXHAUST("Escape y filtro de partículas"),
    ELECTRIC("Eléctrico"),
    COUNTERS("Contadores"),
}

/**
 * Una medida del modo 01 con la formula del estandar SAE J1979. [pid] es lo que se pide al coche
 * e [id] identifica la medida; coinciden salvo en los PIDs que devuelven varias medidas a la vez.
 * Los [fast] se leen en cada vuelta; el resto, uno por vuelta, porque cambian despacio.
 * [decode] devuelve null si la respuesta dice que ese sensor no existe.
 */
data class PidDef(
    val id: Int,
    val name: String,
    val unit: String,
    val bytes: Int,
    val decimals: Int,
    val fast: Boolean = false,
    val group: PidGroup = PidGroup.ENGINE,
    val pid: Int = id,
    val decode: (IntArray) -> Double?,
) {
    fun decodeOrNull(data: IntArray): Double? = if (data.size >= bytes) decode(data) else null
}

object Pids {

    const val LOAD = 0x04
    const val COOLANT = 0x05
    const val MAP = 0x0B
    const val RPM = 0x0C
    const val SPEED = 0x0D
    const val BAROMETRIC = 0x33
    const val OIL = 0x5C
    const val FUEL_RATE = 0x5E

    /** La tension que mide el propio adaptador con ATRV: no es un PID y no depende del coche. */
    const val ADAPTER_PID = -1
    const val ADAPTER_VOLTAGE = 0x10000

    private fun word(d: IntArray, i: Int = 0) = d[i] * 256 + d[i + 1]
    private fun signedWord(d: IntArray, i: Int) = word(d, i).let { if (it >= 0x8000) it - 0x10000 else it }
    private fun long(d: IntArray, i: Int) = d[i].toLong() shl 24 or (d[i + 1].toLong() shl 16) or (word(d, i + 2).toLong())
    private fun temp(d: IntArray, i: Int = 0) = d[i] - 40.0
    private fun wideTemp(d: IntArray, i: Int) = word(d, i) / 10.0 - 40.0
    private fun percent(d: IntArray, i: Int = 0) = d[i] * 100.0 / 255

    /** En los PIDs de varias medidas el primer byte dice, bit a bit, cuales existen. */
    private fun has(d: IntArray, bit: Int) = (d[0] shr bit) and 1 == 1

    /** Identificador de la medida numero [index] de un PID de varias medidas. */
    fun part(pid: Int, index: Int) = pid * 0x100 + index

    private val engine = listOf(
        PidDef(RPM, "RPM", "rpm", 2, 0, fast = true) { word(it) / 4.0 },
        PidDef(SPEED, "Velocidad", "km/h", 1, 0, fast = true) { it[0].toDouble() },
        PidDef(COOLANT, "Refrigerante", "°C", 1, 0) { temp(it) },
        PidDef(OIL, "Aceite", "°C", 1, 0) { temp(it) },
        PidDef(LOAD, "Carga motor", "%", 1, 0, fast = true) { percent(it) },
        PidDef(0x43, "Carga absoluta", "%", 2, 0) { word(it) * 100.0 / 255 },
        PidDef(0x49, "Pedal acelerador", "%", 1, 0) { percent(it) },
        PidDef(0x5A, "Pedal relativo", "%", 1, 0) { percent(it) },
        PidDef(0x11, "Mariposa", "%", 1, 0) { percent(it) },
        PidDef(0x45, "Mariposa relativa", "%", 1, 0) { percent(it) },
        PidDef(0x4C, "Mariposa mandada", "%", 1, 0) { percent(it) },
        PidDef(0x61, "Par pedido", "%", 1, 0) { it[0] - 125.0 },
        PidDef(0x62, "Par real", "%", 1, 0) { it[0] - 125.0 },
        PidDef(0x63, "Par de referencia", "Nm", 2, 0) { word(it).toDouble() },
    )

    private val air = listOf(
        PidDef(MAP, "Presión admisión", "kPa", 1, 0, fast = true) { it[0].toDouble() },
        PidDef(BAROMETRIC, "Presión barométrica", "kPa", 1, 0) { it[0].toDouble() },
        PidDef(0x0F, "Temp. admisión", "°C", 1, 0) { temp(it) },
        PidDef(0x10, "Caudal aire (MAF)", "g/s", 2, 1) { word(it) / 100.0 },
        PidDef(part(0x6F, 0), "Presión entrada compresor", "kPa", 2, 0, pid = 0x6F) {
            if (has(it, 0)) it[1].toDouble() else null
        },
        PidDef(part(0x70, 0), "Presión turbo mandada", "kPa", 3, 0, pid = 0x70) {
            if (has(it, 0)) word(it, 1) / 32.0 else null
        },
        PidDef(part(0x70, 1), "Presión turbo real", "kPa", 5, 0, pid = 0x70) {
            if (has(it, 1)) word(it, 3) / 32.0 else null
        },
        PidDef(part(0x71, 0), "Geometría variable mandada", "%", 2, 0, pid = 0x71) {
            if (has(it, 0)) percent(it, 1) else null
        },
        PidDef(part(0x71, 1), "Geometría variable real", "%", 3, 0, pid = 0x71) {
            if (has(it, 1)) percent(it, 2) else null
        },
        PidDef(part(0x74, 0), "Turbo", "rpm", 3, 0, pid = 0x74) {
            if (has(it, 0)) word(it, 1) * 10.0 else null
        },
        PidDef(part(0x75, 0), "Temp. entrada compresor", "°C", 2, 0, pid = 0x75) {
            if (has(it, 0)) temp(it, 1) else null
        },
        PidDef(part(0x75, 1), "Temp. salida compresor", "°C", 3, 0, pid = 0x75) {
            if (has(it, 1)) temp(it, 2) else null
        },
        PidDef(part(0x75, 2), "Temp. entrada turbina", "°C", 5, 0, pid = 0x75) {
            if (has(it, 2)) wideTemp(it, 3) else null
        },
        PidDef(part(0x75, 3), "Temp. salida turbina", "°C", 7, 0, pid = 0x75) {
            if (has(it, 3)) wideTemp(it, 5) else null
        },
        PidDef(part(0x77, 0), "Temp. intercooler", "°C", 2, 0, pid = 0x77) {
            if (has(it, 0)) temp(it, 1) else null
        },
        PidDef(0x2C, "EGR mandada", "%", 1, 0) { percent(it) },
        PidDef(0x2D, "Error EGR", "%", 1, 1) { it[0] * 100.0 / 128 - 100.0 },
        PidDef(part(0x69, 1), "EGR real", "%", 3, 0, pid = 0x69) {
            if (has(it, 1)) percent(it, 2) else null
        },
    )

    private val fuel = listOf(
        PidDef(0x23, "Presión raíl", "bar", 2, 0) { word(it) * 10.0 / 100.0 },
        PidDef(part(0x6D, 0), "Presión raíl mandada", "bar", 3, 0, pid = 0x6D) {
            if (has(it, 0)) word(it, 1) / 10.0 else null
        },
        PidDef(part(0x6D, 1), "Presión raíl (sensor A)", "bar", 5, 0, pid = 0x6D) {
            if (has(it, 1)) word(it, 3) / 10.0 else null
        },
        PidDef(part(0x6D, 2), "Temp. combustible en raíl", "°C", 6, 0, pid = 0x6D) {
            if (has(it, 2)) temp(it, 5) else null
        },
        PidDef(0x22, "Presión raíl relativa", "kPa", 2, 0) { word(it) * 0.079 },
        PidDef(0x0A, "Presión combustible", "kPa", 1, 0) { it[0] * 3.0 },
        PidDef(0x5D, "Avance inyección", "°", 2, 1) { word(it) / 128.0 - 210.0 },
        PidDef(0x44, "Lambda mandada", "λ", 2, 2) { word(it) * 2.0 / 65536 },
        PidDef(FUEL_RATE, "Consumo", "L/h", 2, 1) { word(it) / 20.0 },
        PidDef(0x2F, "Nivel combustible", "%", 1, 0) { percent(it) },
    )

    private val exhaust = listOf(
        PidDef(0x3C, "Temp. catalizador 1", "°C", 2, 0) { wideTemp(it, 0) },
        PidDef(0x3E, "Temp. catalizador 2", "°C", 2, 0) { wideTemp(it, 0) },
    ) + (0..3).map { sensor ->
        PidDef(part(0x78, sensor), "Temp. gases escape ${sensor + 1}", "°C", 3 + sensor * 2, 0, pid = 0x78) {
            if (has(it, sensor)) wideTemp(it, 1 + sensor * 2) else null
        }
    } + listOf(
        PidDef(part(0x73, 0), "Presión escape", "kPa", 3, 1, pid = 0x73) {
            if (has(it, 0)) word(it, 1) / 100.0 else null
        },
        PidDef(part(0x7A, 0), "Filtro: presión diferencial", "kPa", 3, 2, pid = 0x7A) {
            if (has(it, 0)) signedWord(it, 1) / 100.0 else null
        },
        PidDef(part(0x7A, 1), "Filtro: presión entrada", "kPa", 5, 1, pid = 0x7A) {
            if (has(it, 1)) word(it, 3) / 100.0 else null
        },
        PidDef(part(0x7A, 2), "Filtro: presión salida", "kPa", 7, 1, pid = 0x7A) {
            if (has(it, 2)) word(it, 5) / 100.0 else null
        },
        PidDef(part(0x7C, 0), "Filtro: temp. entrada", "°C", 3, 0, pid = 0x7C) {
            if (has(it, 0)) wideTemp(it, 1) else null
        },
        PidDef(part(0x7C, 1), "Filtro: temp. salida", "°C", 5, 0, pid = 0x7C) {
            if (has(it, 1)) wideTemp(it, 3) else null
        },
        PidDef(part(0x83, 0), "NOx", "ppm", 3, 0, pid = 0x83) {
            if (has(it, 0)) word(it, 1).toDouble() else null
        },
    )

    private val electric = listOf(
        PidDef(0x42, "Tensión módulo", "V", 2, 1) { word(it) / 1000.0 },
        PidDef(ADAPTER_VOLTAGE, "Batería (adaptador)", "V", 0, 1, pid = ADAPTER_PID) { null },
        PidDef(0x46, "Temp. ambiente", "°C", 1, 0) { temp(it) },
    )

    private val counters = listOf(
        PidDef(0x1F, "Tiempo desde el arranque", "min", 2, 0) { word(it) / 60.0 },
        PidDef(part(0x7F, 0), "Horas de motor", "h", 5, 0, pid = 0x7F) {
            if (has(it, 0)) long(it, 1) / 3600.0 else null
        },
        PidDef(0xA6, "Cuentakilómetros", "km", 4, 0) { long(it, 0) / 10.0 },
        PidDef(0x31, "Km desde el borrado de averías", "km", 2, 0) { word(it).toDouble() },
        PidDef(0x4E, "Tiempo desde el borrado", "min", 2, 0) { word(it).toDouble() },
        PidDef(0x30, "Calentamientos desde el borrado", "", 1, 0) { it[0].toDouble() },
        PidDef(0x21, "Km con el testigo encendido", "km", 2, 0) { word(it).toDouble() },
        PidDef(0x4D, "Tiempo con el testigo encendido", "min", 2, 0) { word(it).toDouble() },
    )

    private fun List<PidDef>.inGroup(group: PidGroup) = map { it.copy(group = group) }

    /** En el orden en que se enseñan en el panel. */
    val all: List<PidDef> =
        engine.inGroup(PidGroup.ENGINE) +
            air.inGroup(PidGroup.AIR) +
            fuel.inGroup(PidGroup.FUEL) +
            exhaust.inGroup(PidGroup.EXHAUST) +
            electric.inGroup(PidGroup.ELECTRIC) +
            counters.inGroup(PidGroup.COUNTERS)

    val byId: Map<Int, PidDef> = all.associateBy { it.id }

    /** Sobrealimentación: presión de admisión menos presión barométrica, en bar. */
    fun boostBar(values: Map<Int, Double>): Double? {
        val map = values[MAP] ?: return null
        val baro = values[BAROMETRIC] ?: return null
        return (map - baro) / 100.0
    }

    /** Consumo instantáneo en L/100 km. Parado o casi parado no tiene sentido y devuelve null. */
    fun litersPer100Km(values: Map<Int, Double>): Double? {
        val rate = values[FUEL_RATE] ?: return null
        val speed = values[SPEED] ?: return null
        return if (speed >= MIN_SPEED_FOR_CONSUMPTION) rate / speed * 100.0 else null
    }

    private const val MIN_SPEED_FOR_CONSUMPTION = 5.0
}
