package com.rabie.bmwobd.obd

/**
 * Cada cuanto se lee una medida. Las rapidas, en cada vuelta. De las medias, una por vuelta. De
 * las lentas (temperaturas, tension, contadores), una cada varias vueltas. Las que no cambian
 * durante un trayecto, una sola vez al conectar.
 */
enum class Tier { FAST, MEDIUM, SLOW, ONCE }

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
 * [tier] dice cada cuanto se lee: lo que cambia deprisa, en cada vuelta; lo demas, por turnos.
 * [decode] devuelve null si la respuesta dice que ese sensor no existe.
 */
data class PidDef(
    val id: Int,
    val name: String,
    val unit: String,
    val bytes: Int,
    val decimals: Int,
    val tier: Tier = Tier.SLOW,
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
    const val MAF = 0x10
    const val LAMBDA = 0x24
    const val PEDAL = 0x49
    const val ODOMETER = 0xA6

    /**
     * Medidas propias de la centralita del motor de BMW. No son PIDs: se piden con el servicio 2C
     * y la direccion de dos bytes de la medida, y lo que se pide al coche es BMW_BASE + direccion.
     */
    const val BMW_BASE = 0x20000

    fun bmw(address: Int) = BMW_BASE + address

    fun isBmw(pid: Int) = pid >= BMW_BASE

    /** Dos medidas de BMW pedidas en una sola consulta: contesta con los dos valores seguidos. */
    private const val BMW_PAIR = 0x4000_0000

    fun bmwPair(first: Int, second: Int) = BMW_PAIR or (first shl 16) or second

    /** Las direcciones que hay que pedir a la centralita para la consulta [pid]. */
    fun bmwAddresses(pid: Int): List<Int> =
        if (pid >= BMW_PAIR) listOf((pid shr 16) and 0x3FFF, pid and 0xFFFF) else listOf(pid - BMW_BASE)

    /** Refrigerante por el protocolo de BMW: tambien sale por OBD, y sirve para saber si contesta. */
    val BMW_CHECK = bmw(0x0547)
    val BMW_INJECTION = bmw(0x0500)

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
        PidDef(RPM, "RPM", "rpm", 2, 0, tier = Tier.FAST) { word(it) / 4.0 },
        PidDef(SPEED, "Velocidad", "km/h", 1, 0, tier = Tier.FAST) { it[0].toDouble() },
        PidDef(COOLANT, "Refrigerante", "°C", 1, 0) { temp(it) },
        PidDef(OIL, "Aceite", "°C", 1, 0) { temp(it) },
        PidDef(LOAD, "Carga motor", "%", 1, 0, tier = Tier.FAST) { percent(it) },
        PidDef(0x43, "Carga absoluta", "%", 2, 0) { word(it) * 100.0 / 255 },
        PidDef(0x49, "Pedal acelerador", "%", 1, 0, tier = Tier.MEDIUM) { percent(it) },
        PidDef(0x5A, "Pedal relativo", "%", 1, 0) { percent(it) },
        PidDef(0x11, "Mariposa", "%", 1, 0) { percent(it) },
        PidDef(0x45, "Mariposa relativa", "%", 1, 0) { percent(it) },
        PidDef(0x4C, "Mariposa mandada", "%", 1, 0) { percent(it) },
        PidDef(0x61, "Par pedido", "%", 1, 0) { it[0] - 125.0 },
        PidDef(0x62, "Par real", "%", 1, 0) { it[0] - 125.0 },
        PidDef(0x63, "Par de referencia", "Nm", 2, 0) { word(it).toDouble() },
    )

    private val air = listOf(
        PidDef(MAP, "Presión admisión", "kPa", 1, 0, tier = Tier.FAST) { it[0].toDouble() },
        PidDef(BAROMETRIC, "Presión barométrica", "kPa", 1, 0) { it[0].toDouble() },
        PidDef(0x0F, "Temp. admisión", "°C", 1, 0) { temp(it) },
        PidDef(0x10, "Caudal aire (MAF)", "g/s", 2, 1, tier = Tier.FAST) { word(it) / 100.0 },
        PidDef(part(0x6F, 0), "Presión entrada compresor", "kPa", 2, 0, pid = 0x6F) {
            if (has(it, 0)) it[1].toDouble() else null
        },
        PidDef(part(0x70, 0), "Presión turbo mandada", "kPa", 3, 0, pid = 0x70, tier = Tier.MEDIUM) {
            if (has(it, 0)) word(it, 1) / 32.0 else null
        },
        PidDef(part(0x70, 1), "Presión turbo real", "kPa", 5, 0, pid = 0x70, tier = Tier.MEDIUM) {
            if (has(it, 1)) word(it, 3) / 32.0 else null
        },
        PidDef(part(0x71, 0), "Geometría variable mandada", "%", 2, 0, pid = 0x71, tier = Tier.MEDIUM) {
            if (has(it, 0)) percent(it, 1) else null
        },
        PidDef(part(0x71, 1), "Geometría variable real", "%", 3, 0, pid = 0x71, tier = Tier.MEDIUM) {
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
        PidDef(0x2C, "EGR mandada", "%", 1, 0, tier = Tier.MEDIUM) { percent(it) },
        PidDef(0x2D, "Error EGR", "%", 1, 1, tier = Tier.MEDIUM) { it[0] * 100.0 / 128 - 100.0 },
        PidDef(part(0x69, 1), "EGR real", "%", 3, 0, pid = 0x69, tier = Tier.MEDIUM) {
            if (has(it, 1)) percent(it, 2) else null
        },
    )

    private val fuel = listOf(
        PidDef(0x23, "Presión raíl", "bar", 2, 0, tier = Tier.MEDIUM) { word(it) * 10.0 / 100.0 },
        PidDef(part(0x6D, 0), "Presión raíl mandada", "bar", 3, 0, pid = 0x6D, tier = Tier.MEDIUM) {
            if (has(it, 0)) word(it, 1) / 10.0 else null
        },
        PidDef(part(0x6D, 1), "Presión raíl (sensor A)", "bar", 5, 0, pid = 0x6D, tier = Tier.MEDIUM) {
            if (has(it, 1)) word(it, 3) / 10.0 else null
        },
        PidDef(part(0x6D, 2), "Temp. combustible en raíl", "°C", 6, 0, pid = 0x6D, tier = Tier.MEDIUM) {
            if (has(it, 2)) temp(it, 5) else null
        },
        PidDef(0x22, "Presión raíl relativa", "kPa", 2, 0) { word(it) * 0.079 },
        PidDef(0x0A, "Presión combustible", "kPa", 1, 0) { it[0] * 3.0 },
        PidDef(0x5D, "Avance inyección", "°", 2, 1) { word(it) / 128.0 - 210.0 },
        PidDef(0x44, "Lambda mandada", "λ", 2, 2) { word(it) * 2.0 / 65536 },
        // Sonda lambda de banda ancha. Por el estandar no pasa de 2: con mezcla mas pobre se queda ahi.
        PidDef(0x24, "Lambda medida", "λ", 2, 2, tier = Tier.MEDIUM) { word(it) * 2.0 / 65536 },
        PidDef(FUEL_RATE, "Consumo", "L/h", 2, 1, tier = Tier.MEDIUM) { word(it) / 20.0 },
        PidDef(0x2F, "Nivel combustible", "%", 1, 0) { percent(it) },
    )

    private val exhaust = listOf(
        PidDef(0x3C, "Temp. catalizador 1", "°C", 2, 0, tier = Tier.MEDIUM) { wideTemp(it, 0) },
        PidDef(0x3E, "Temp. catalizador 2", "°C", 2, 0) { wideTemp(it, 0) },
    ) + (0..3).map { sensor ->
        PidDef(part(0x78, sensor), "Temp. gases escape ${sensor + 1}", "°C", 3 + sensor * 2, 0, pid = 0x78, tier = Tier.MEDIUM) {
            if (has(it, sensor)) wideTemp(it, 1 + sensor * 2) else null
        }
    } + listOf(
        PidDef(part(0x73, 0), "Presión escape", "kPa", 3, 1, pid = 0x73) {
            if (has(it, 0)) word(it, 1) / 100.0 else null
        },
        PidDef(part(0x7A, 0), "Filtro: presión diferencial", "kPa", 3, 2, pid = 0x7A, tier = Tier.MEDIUM) {
            if (has(it, 0)) signedWord(it, 1) / 100.0 else null
        },
        PidDef(part(0x7A, 1), "Filtro: presión entrada", "kPa", 5, 1, pid = 0x7A, tier = Tier.MEDIUM) {
            if (has(it, 1)) word(it, 3) / 100.0 else null
        },
        PidDef(part(0x7A, 2), "Filtro: presión salida", "kPa", 7, 1, pid = 0x7A, tier = Tier.MEDIUM) {
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
        PidDef(ODOMETER, "Cuentakilómetros", "km", 4, 0) { long(it, 0) / 10.0 },
        PidDef(0x31, "Km desde el borrado de averías", "km", 2, 0) { word(it).toDouble() },
        PidDef(0x4E, "Tiempo desde el borrado", "min", 2, 0) { word(it).toDouble() },
        PidDef(0x30, "Calentamientos desde el borrado", "", 1, 0) { it[0].toDouble() },
        PidDef(0x21, "Km con el testigo encendido", "km", 2, 0) { word(it).toDouble() },
        PidDef(0x4D, "Tiempo con el testigo encendido", "min", 2, 0) { word(it).toDouble() },
    )

    /** Una medida de la centralita de BMW: direccion, tamaño en bytes y escala de su tabla. */
    private class BmwSpec(
        val address: Int,
        val name: String,
        val unit: String,
        val decimals: Int,
        val group: PidGroup,
        val size: Int = 2,
        val id: Int = bmw(address),
        val scale: (Long) -> Double,
    ) {
        /** La medida leida con la consulta [pid], cuyo valor empieza en el byte [offset]. */
        fun def(pid: Int, offset: Int, tier: Tier) =
            PidDef(id, name, unit, offset + size, decimals, tier, group, pid) { data ->
                var raw = 0L
                for (i in offset until offset + size) raw = raw * 256 + data[i]
                scale(raw)
            }
    }

    /**
     * Las medidas de [requests], primero pedidas de dos en dos y luego cada una por su cuenta. Al
     * conectar vale la primera forma que conteste, asi que si la centralita no admite dos
     * direcciones en una consulta se leen sueltas.
     */
    private fun bmwDefs(tier: Tier, vararg requests: List<BmwSpec>): List<PidDef> {
        val together = requests.filter { it.size == 2 }.flatMap { (first, second) ->
            val pid = bmwPair(first.address, second.address)
            listOf(first.def(pid, 0, tier), second.def(pid, first.size, tier))
        }
        val alone = requests.flatMap { specs -> specs.map { it.def(bmw(it.address), 0, tier) } }
        return together + alone
    }

    private val E = PidGroup.ENGINE
    private val A = PidGroup.AIR
    private val F = PidGroup.FUEL
    private val X = PidGroup.EXHAUST

    /**
     * Medidas de la DDE 7 del motor N47, con la direccion y la formula de la tabla MESSWERTETAB de
     * su descripcion (d70n47b0). El valor llega con el byte alto primero. Comprobadas en el coche:
     * inyeccion, rail y turbo pedidos, gasoleo, hollin y distancia desde la regeneracion. Del
     * aceite, del actuador del turbo y de la presion del filtro hay varias direcciones y todavia
     * no se sabe cual vale: se graban todas para compararlas.
     */
    val bmwDiesel: List<PidDef> =
        bmwDefs(
            Tier.MEDIUM,
            listOf(BmwSpec(0x0500, "Cantidad inyectada", "mg/emb", 1, F) { it * 0.003052 - 100.0 }),
            listOf(
                BmwSpec(0x01F4, "Presión turbo pedida", "kPa", 0, A) { it * 0.0091554 },
                BmwSpec(0x0641, "Presión raíl pedida", "bar", 0, F) { it * 0.045777 },
            ),
        ) + bmwDefs(
            Tier.SLOW,
            listOf(
                BmwSpec(0x0A8C, "Aceite", "°C", 0, E, id = OIL) { it * 0.01 - 100.0 },
                BmwSpec(0x0458, "Aceite (valor filtrado)", "°C", 0, E) { it * 0.01 - 100.0 },
            ),
            listOf(
                BmwSpec(0x01BA, "Aceite (valor para el cuadro)", "°C", 0, E) { it * 0.005417 - 100.0 },
                BmwSpec(0x0385, "Temp. gasóleo", "°C", 0, F) { it * 0.01 - 50.0 },
            ),
            listOf(
                BmwSpec(0x0BEB, "Actuador del turbo (salida)", "%", 0, A) { it * 0.001526 },
                BmwSpec(0x0BEA, "Actuador del turbo (consigna)", "%", 0, A) { it * 0.003052 },
            ),
            listOf(
                BmwSpec(0x0BF2, "Actuador del turbo (posición)", "%", 0, A) { it * 0.001526 },
                BmwSpec(0x041B, "Escape antes del filtro", "°C", 0, X) { it * 0.031281 - 50.0 },
            ),
            listOf(
                BmwSpec(0x03EA, "Filtro: hollín", "g", 1, X) { it * 0.015259 },
                BmwSpec(0x03ED, "Filtro: hollín calculado", "g", 1, X) { it * 0.01 },
            ),
            listOf(
                BmwSpec(0x043A, "Filtro: presión diferencial (sensor)", "hPa", 0, X) { it * 0.038148 + 500.0 },
                BmwSpec(0x0426, "Filtro: presión diferencial (filtrada)", "hPa", 0, X) { it * 0.045777 - 1000.0 },
            ),
            listOf(
                BmwSpec(0x0424, "Filtro: presión diferencial (corregida)", "hPa", 0, X) { it * 0.045777 - 1000.0 },
                BmwSpec(0x0432, "Filtro: presión de entrada", "hPa", 0, X) { it * 0.1 },
            ),
            listOf(
                BmwSpec(0x0D16, "Depósito", "L", 1, F) { it * 0.01 },
                BmwSpec(0x0384, "Depósito (volumen)", "L", 1, F) { it * 0.001907 },
            ),
            listOf(BmwSpec(0x03EB, "Filtro: desde la última regeneración", "km", 1, X, size = 4) { it / 1000.0 }),
        ) + bmwDefs(
            Tier.ONCE,
            listOf(BmwSpec(0x03F3, "Filtro: intervalo medio entre regeneraciones", "km", 0, X) { it.toDouble() }),
            listOf(BmwSpec(0x16B2, "Km guardados en la centralita", "km", 0, PidGroup.COUNTERS, size = 4) { it.toDouble() }),
        )

    /** Si a un coche se le pueden pedir las medidas de [bmwDiesel]. */
    fun hasBmwMeasures(make: String?, diesel: Boolean) = diesel && make != null && make.startsWith("BMW")

    private fun List<PidDef>.inGroup(group: PidGroup) = map { it.copy(group = group) }

    /** En el orden en que se enseñan en el panel. */
    val all: List<PidDef> =
        engine.inGroup(PidGroup.ENGINE) +
            air.inGroup(PidGroup.AIR) +
            fuel.inGroup(PidGroup.FUEL) +
            exhaust.inGroup(PidGroup.EXHAUST) +
            electric.inGroup(PidGroup.ELECTRIC) +
            counters.inGroup(PidGroup.COUNTERS) +
            bmwDiesel

    // Donde una medida de BMW comparte identificador con una estandar, manda la estandar.
    val byId: Map<Int, PidDef> = all.asReversed().associateBy { it.id }

    /** Sobrealimentación: presión de admisión menos presión barométrica, en bar. */
    fun boostBar(values: Map<Int, Double>): Double? {
        val map = values[MAP] ?: return null
        val baro = values[BAROMETRIC] ?: return null
        return (map - baro) / 100.0
    }

    /** Las medidas de las que sale el consumo, medido o estimado. */
    val FUEL_INPUTS = listOf(FUEL_RATE, BMW_INJECTION, MAF, LAMBDA, PEDAL, RPM)

    /** Si con lo que anuncia el coche se puede dar un consumo. */
    fun hasFuelRate(supported: Set<Int>) =
        FUEL_RATE in supported || BMW_INJECTION in supported || (MAF in supported && LAMBDA in supported)

    /** Si el consumo sale de la estimacion con la lambda y no de un dato de combustible del coche. */
    fun fuelRateIsEstimated(supported: Set<Int>) =
        FUEL_RATE !in supported && BMW_INJECTION !in supported && hasFuelRate(supported)

    /**
     * Consumo en L/h a partir de lo que la centralita inyecta en cada embolada: en un motor de
     * cuatro cilindros y cuatro tiempos hay dos inyecciones por vuelta. Un valor fuera de lo
     * posible se descarta, por si la direccion no es la de esta centralita.
     */
    private fun injectedRate(values: Map<Int, Double>): Double? {
        val perStroke = values[BMW_INJECTION]?.takeIf { it in -1.0..MAX_INJECTION_MG } ?: return null
        val rpm = values[RPM] ?: return null
        val gramsPerHour = perStroke.coerceAtLeast(0.0) / 1000.0 * rpm * INJECTIONS_PER_TURN * 60.0
        return gramsPerHour / DIESEL_GRAMS_PER_LITER
    }

    /**
     * Consumo en L/h. El que da el coche si lo da, o el que sale de la cantidad inyectada si la
     * centralita la da. Si no, estimado: el aire que entra dividido por
     * la proporcion de aire por gramo de combustible que mide la sonda lambda. Con la sonda fria la
     * lambda marca 0 y no hay estimacion. En retencion la sonda se queda en su tope y la cuenta
     * daria combustible que no se inyecta: sin pedal y por encima del ralenti se toma como cero.
     */
    fun fuelRate(values: Map<Int, Double>, diesel: Boolean = true): Double? {
        values[FUEL_RATE]?.let { return it }
        injectedRate(values)?.let { return it }
        val air = values[MAF] ?: return null
        val lambda = values[LAMBDA]?.takeIf { it >= MIN_LAMBDA } ?: return null
        val overrun = lambda >= LAMBDA_TOP && values[PEDAL] == 0.0 && (values[RPM] ?: 0.0) > OVERRUN_MIN_RPM
        if (overrun) return 0.0
        val airPerFuel = if (diesel) DIESEL_AIR_PER_FUEL else PETROL_AIR_PER_FUEL
        val gramsPerLiter = if (diesel) DIESEL_GRAMS_PER_LITER else PETROL_GRAMS_PER_LITER
        return air / (airPerFuel * lambda) * SECONDS_PER_HOUR / gramsPerLiter
    }

    /** Consumo instantáneo en L/100 km. Parado o casi parado no tiene sentido y devuelve null. */
    fun litersPer100Km(values: Map<Int, Double>, diesel: Boolean = true): Double? {
        val rate = fuelRate(values, diesel) ?: return null
        val speed = values[SPEED] ?: return null
        return if (speed >= MIN_SPEED_FOR_CONSUMPTION) rate / speed * 100.0 else null
    }

    private const val MIN_SPEED_FOR_CONSUMPTION = 5.0
    private const val MAX_INJECTION_MG = 120.0
    private const val INJECTIONS_PER_TURN = 2
    private const val MIN_LAMBDA = 0.5
    private const val LAMBDA_TOP = 1.99
    private const val OVERRUN_MIN_RPM = 1100.0
    private const val DIESEL_AIR_PER_FUEL = 14.5
    private const val PETROL_AIR_PER_FUEL = 14.7
    private const val DIESEL_GRAMS_PER_LITER = 835.0
    private const val PETROL_GRAMS_PER_LITER = 745.0
    private const val SECONDS_PER_HOUR = 3600.0
}
