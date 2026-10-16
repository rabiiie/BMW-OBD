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
    /**
     * Una medida de BMW tiene que traer justo sus bytes: su respuesta no dice de que medida es, y
     * una de otro tamaño es la de otra medida que ha llegado cruzada.
     */
    fun decodeOrNull(data: IntArray): Double? {
        val fits = if (Pids.isBmw(pid)) data.size == bytes else data.size >= bytes
        return if (fits) decode(data) else null
    }
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


    /** Refrigerante por el protocolo de BMW: tambien sale por OBD, y sirve para saber si contesta. */
    val BMW_CHECK = bmw(0x0547)
    val BMW_INJECTION = bmw(0x0500)
    val BMW_SOOT_MEASURED = bmw(0x03EA)
    val BMW_SOOT_MODEL = bmw(0x03ED)

    /**
     * La peticion de regeneracion: lo que la centralita quiere hacer con el filtro, no lo que hace.
     * Visto en el coche: 0 nada y 1 pendiente. El 5 coincidio con una regeneracion el 12 de
     * octubre, pero el 13, con una averia del escape, siguio en 5 y luego en 7 sin regenerar.
     */
    val BMW_REGEN_REQUEST = bmw(0x0404)

    /**
     * El modo en que esta el motor, segun su coordinador de modos ("Regenerationsstatus"). La
     * tabla no da los valores; de los trayectos: [MODE_NORMAL] y [MODE_REGENERATING], este solo
     * mientras regenera de verdad, con el escape a 500 o 600 grados.
     */
    val BMW_ENGINE_MODE = bmw(0x05AA)
    const val MODE_NORMAL = 0x00100001
    const val MODE_REGENERATING = 0x02010002

    /**
     * La regeneracion de proteccion: con el filtro muy cargado lo quema despacio, sin pasar de unos
     * 500 grados, para no dañarlo. Visto el 13 y el 14 de octubre, cuando la memoria de informacion
     * apunto 4CE0, "regeneracion de proteccion del motor activada".
     */
    const val MODE_PROTECTION = 0x00210002

    /** Con el contacto puesto y el motor parado. */
    const val MODE_STOPPED = 0x80100001.toInt()

    val KNOWN_MODES = setOf(MODE_NORMAL, MODE_REGENERATING, MODE_PROTECTION, MODE_STOPPED)

    fun protecting(values: Map<Int, Double>): Boolean = values[BMW_ENGINE_MODE]?.toLong()?.toInt() == MODE_PROTECTION

    /**
     * Si el filtro esta regenerando ahora. Con el modo del motor leido, lo dice el; un modo que no
     * se conoce no cuenta. Sin modo, en grabaciones de antes de leerlo, vale la peticion.
     */
    fun regenerating(request: Double?, mode: Double?): Boolean = when {
        mode != null && !mode.isNaN() -> mode.toLong().toInt().let { it == MODE_REGENERATING || it == MODE_PROTECTION }
        else -> request != null && request >= BMW_REGEN_ACTIVE
    }

    fun regenerating(values: Map<Int, Double>): Boolean =
        if (BMW_REGEN_REQUEST in values || BMW_ENGINE_MODE in values) {
            regenerating(values[BMW_REGEN_REQUEST], values[BMW_ENGINE_MODE])
        } else {
            // En los demas coches, lo que diga el OBD estandar si lo publica.
            values[OBD_REGENERATING] == 1.0
        }

    /** Si el filtro regenera segun el OBD estandar (PID 8B), en los coches que lo publican. */
    val OBD_REGENERATING = part(0x8B, 0)

    /** La presion diferencial del filtro segun el OBD estandar (PID 7A), en kPa. */
    val OBD_FILTER_PRESSURE = part(0x7A, 0)

    /** Los minutos que llevaba el coche parado antes de este arranque, segun la centralita. */
    val BMW_PARKED_MINUTES = bmw(0x10CE)

    /** A partir de este valor de [BMW_REGEN_REQUEST] la centralita la da por empezada, aunque puede no estar haciendola. */
    const val BMW_REGEN_ACTIVE = 2.0

    /** Lo que la centralita suma o quita a cada inyector para igualar los cilindros, del 1 al 4. */
    val BMW_CYLINDER_CORRECTIONS = listOf(0x14B4, 0x14B5, 0x14B6, 0x14B7).map(::bmw)

    /**
     * El tiempo de mas que ha aprendido a dar a cada inyector, en los tres puntos de presion en
     * que lo calibra: una lista por punto, con los cilindros del 1 al 4.
     */
    val BMW_INJECTOR_TIMES = listOf(0x1461, 0x1467, 0x146D).map { first -> (0..3).map { bmw(first + it) } }

    /** La presion del rail de cada punto de calibracion (ABGLEICH_NMK_LESEN de d70n47b0). */
    val BMW_CALIBRATION_BAR = listOf(400, 700, 1000)

    /**
     * Los fallos de calentadores que apunta su mando, cada uno con lo que significa. El valor
     * lleva un bit por calentador; cero es que no hay ninguno.
     */
    val BMW_GLOW_FAULTS = listOf(
        0x1600 to "resistencia alta en frío",
        0x1601 to "resistencia alta en caliente",
        0x1602 to "resistencia baja en frío",
        0x1603 to "resistencia baja en caliente",
        0x1604 to "salida cortada",
        0x1605 to "salida siempre encendida",
    ).map { (address, text) -> bmw(address) to text }

    const val BMW_TANK_ADDRESS = 0x0384
    const val BMW_ODOMETER_ADDRESS = 0x05DD
    const val BMW_TOTAL_FUEL_ADDRESS = 0x0939
    const val BMW_FAULTS_ADDRESS = 0x04A7
    const val BMW_OIL_KM_ADDRESS = 0x044C
    const val BMW_OIL_LEVEL_ADDRESS = 0x044D
    const val BMW_TIMING_OFFSET_ADDRESS = 0x15E5

    // Lo que la centralita ha aprendido para compensar el desgaste de cada pieza.
    val BMW_RAIL_VALVE_LEARNED = bmw(0x0258)
    val BMW_LAMBDA_LEARNED = bmw(0x0A40)
    val BMW_EGR_ZERO_FIRST = bmw(0x0BBD)
    val BMW_EGR_ZERO_LAST = bmw(0x0BBE)
    val BMW_FILTER_ZERO = bmw(0x0428)
    val BMW_EGR_COOLER = bmw(0x0C99)

    /** Lo que marcaba la distancia total en cada una de las cinco ultimas regeneraciones, de la mas reciente a la mas antigua. */
    val BMW_REGENERATION_ADDRESSES = listOf(0x03FD, 0x03FE, 0x03FF, 0x0400, 0x0401)

    /** Cuantos contadores de regeneracion por tipo tiene la tabla, desde 03F5. */
    const val BMW_REGEN_STEP_COUNTERS = 8

    /** La tension que mide el propio adaptador con ATRV: no es un PID y no depende del coche. */
    const val ADAPTER_PID = -1
    const val ADAPTER_VOLTAGE = 0x10000

    /** No se pide al coche: lo apunta la app con las averias del filtro, que relee cada pocos minutos. */
    const val BMW_FILTER_FAULTS = 0x10001
    private const val APP_PID = -2

    /**
     * Lo que significa la peticion de regeneracion (0404). No esta en la documentacion: es lo visto
     * en este coche. El 7 aparecio con 480A y 481A presentes y el motor en regeneracion de proteccion.
     */
    fun regenRequestText(code: Double): String = when (code.toInt()) {
        0 -> "no pide regenerar"
        1 -> "pendiente: la hará en cuanto pueda"
        5 -> "regenerando"
        7 -> "protección del motor por filtro muy cargado; sin regeneración normal"
        else -> "código sin identificar"
    }

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
        // El estado del tratamiento de gases de los diesel, segun la norma: el primer byte dice que
        // datos da el coche; el segundo, en su primer bit, si esta regenerando; los dos ultimos, la
        // distancia media entre regeneraciones. Sin comprobar todavia en un coche que lo publique.
        PidDef(part(0x8B, 0), "Filtro: regenerando (1 = sí, OBD)", "", 2, 0, pid = 0x8B) {
            if (has(it, 0)) (it[1] and 1).toDouble() else null
        },
        PidDef(part(0x8B, 1), "Filtro: km medios entre regeneraciones (OBD)", "km", 7, 0, pid = 0x8B) {
            if (has(it, 6)) word(it, 5).toDouble() else null
        },
        PidDef(part(0x83, 0), "NOx", "ppm", 3, 0, pid = 0x83) {
            if (has(it, 0)) word(it, 1).toDouble() else null
        },
    )

    private val electric = listOf(
        PidDef(0x42, "Tensión módulo", "V", 2, 1, tier = Tier.MEDIUM) { word(it) / 1000.0 },
        PidDef(ADAPTER_VOLTAGE, "Batería (adaptador)", "V", 0, 1, pid = ADAPTER_PID) { null },
        PidDef(BMW_FILTER_FAULTS, "Filtro: averías 480A/481A (0 ninguna, 1 480A, 2 481A)", "", 0, 0, pid = APP_PID) { null },
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
        PidDef(0x30, "Calentamientos desde el borrado (255 = 255 o más)", "", 1, 0) { it[0].toDouble() },
        PidDef(0x21, "Km con el testigo encendido", "km", 2, 0) { word(it).toDouble() },
        PidDef(0x4D, "Tiempo con el testigo encendido", "min", 2, 0) { word(it).toDouble() },
    )

    /** Una medida de la centralita de BMW: direccion, tamaño en bytes y escala de su tabla. */
    private fun bmwDef(
        address: Int,
        name: String,
        unit: String,
        decimals: Int,
        group: PidGroup,
        tier: Tier = Tier.SLOW,
        size: Int = 2,
        scale: (Long) -> Double,
    ) = PidDef(bmw(address), name, unit, size, decimals, tier, group) { data ->
        var raw = 0L
        for (i in 0 until size) raw = raw * 256 + data[i]
        scale(raw)
    }

    /**
     * Medidas de la DDE 7 del motor N47, con la direccion y la formula de la tabla MESSWERTETAB de
     * su descripcion (d70n47b0). El valor llega con el byte alto primero. Cada una se pide en su
     * propia consulta: con dos direcciones la centralita solo contesta bien a la primera. Todas
     * han dado en el coche un valor coherente con su formula. Las direcciones de temperatura de
     * aceite repetian la del refrigerante en el coche donde se probo, que tenia guardada una averia
     * del sensor de aceite: no se leen. Tampoco hay sensor de presion diferencial: la del filtro
     * sale del sensor de presion absoluta que lleva delante.
     */
    // Lo aprendido de cada inyector cambia muy despacio: se lee una vez al conectar, como el
    // resto del desgaste, y con el caso de los inyectores puesto se sigue leyendo.
    private val bmwInjectorTimes: List<PidDef> = BMW_INJECTOR_TIMES.flatMapIndexed { point, ids ->
        ids.mapIndexed { cylinder, id ->
            PidDef(id, "Inyector ${cylinder + 1}: tiempo aprendido (${BMW_CALIBRATION_BAR[point]} bar)", "µs", 2, 1, Tier.ONCE, PidGroup.FUEL) {
                word(it) * 0.15259 - 5000.0
            }
        }
    }

    val bmwDiesel: List<PidDef> = bmwInjectorTimes + listOf(
        // Se lee siempre: es el que dice si el filtro esta regenerando de verdad.
        bmwDef(0x05AA, "Motor: modo de funcionamiento (código)", "", 0, PidGroup.EXHAUST, size = 4) { it.toDouble() },
        // Se lee siempre: dice cuanto llevaba parado el coche, que es de lo que depende un arranque.
        bmwDef(0x10CE, "Tiempo aparcado antes de arrancar", "min", 0, PidGroup.ELECTRIC) { it.toDouble() },
        bmwDef(0x0500, "Cantidad inyectada", "mg/emb", 1, PidGroup.FUEL, Tier.MEDIUM) { it * 0.003052 - 100.0 },
        bmwDef(0x01F4, "Presión turbo pedida", "kPa", 0, PidGroup.AIR, Tier.MEDIUM) { it * 0.0091554 },
        bmwDef(0x0641, "Presión raíl pedida", "bar", 0, PidGroup.FUEL, Tier.MEDIUM) { it * 0.045777 },

        bmwDef(0x0BEB, "Actuador del turbo", "%", 0, PidGroup.AIR) { it * 0.001526 },
        bmwDef(0x076F, "Aire tras el intercooler", "°C", 0, PidGroup.AIR) { it * 0.01 - 100.0 },
        bmwDef(0x0BB4, "EGR: posición de la válvula", "%", 0, PidGroup.AIR) { it * 0.001526 },
        bmwDef(0x0A29, "Lambda real", "λ", 2, PidGroup.FUEL) { it * 0.001 },
        bmwDef(0x0385, "Temp. gasóleo", "°C", 0, PidGroup.FUEL) { it * 0.01 - 50.0 },
        bmwDef(BMW_TANK_ADDRESS, "Depósito", "L", 1, PidGroup.FUEL) { it * 0.001907 },
        bmwDef(0x03EA, "Filtro: hollín medido", "g", 1, PidGroup.EXHAUST) { it * 0.015259 },
        bmwDef(0x03ED, "Filtro: hollín calculado", "g", 1, PidGroup.EXHAUST) { it * 0.01 },
        bmwDef(0x03EB, "Filtro: desde la última regeneración", "km", 1, PidGroup.EXHAUST, size = 4) { it / 1000.0 },
        bmwDef(0x03E8, "Filtro: gasóleo desde la última regeneración", "L", 1, PidGroup.EXHAUST) { it * 0.01 },
        bmwDef(0x0424, "Filtro: presión diferencial", "hPa", 0, PidGroup.EXHAUST, Tier.MEDIUM) { it * 0.045777 - 1000.0 },
        bmwDef(0x0404, "Filtro: regeneración pedida (0 = no)", "", 0, PidGroup.EXHAUST, size = 1) { it.toDouble() },
        bmwDef(0x0405, "Filtro: hollín medido corregido", "g", 1, PidGroup.EXHAUST) { it * 0.01 },
        // El gas que pasa por el filtro: la presion que hace depende de el, asi que dividiendo una
        // por otro sale una resistencia comparable entre trayectos. Presion, caudal y escape antes
        // del turbo van en el turno medio: se comparan entre si y con el turbo, y en el lento se
        // leian con veinte segundos de diferencia.
        bmwDef(0x0C83, "Filtro: caudal de gas", "m³/h", 0, PidGroup.EXHAUST, Tier.MEDIUM, size = 4) { it * 0.1 },
        bmwDef(0x0426, "Filtro: presión diferencial filtrada", "hPa", 0, PidGroup.EXHAUST) { it * 0.045777 - 1000.0 },
        bmwDef(0x03E0, "Escape: presión antes del turbo", "hPa", 0, PidGroup.EXHAUST, Tier.MEDIUM) { it * 0.038148 + 500.0 },
        // Lo que recorta la fuerza del motor: que limite actua (codigo de bits sin descifrar) y
        // cuanto deja cada uno.
        bmwDef(0x057A, "Par: límite que actúa (código)", "", 0, PidGroup.ENGINE) { it.toDouble() },
        bmwDef(0x05D4, "Par: límite por lo que pide el motor", "Nm", 0, PidGroup.ENGINE) { it * 0.114443 - 2500.0 },
        bmwDef(0x1329, "Par: límite de la vigilancia", "Nm", 0, PidGroup.ENGINE) { it * 0.114443 - 2500.0 },
        bmwDef(0x06C2, "Inyección: límite de humo", "mg/emb", 1, PidGroup.FUEL) { it * 0.01 },
        bmwDef(0x0406, "Filtro: hollín calculado de forma continua", "g", 1, PidGroup.EXHAUST) { it * 0.01 },
        bmwDef(0x01B2, "Corriente de batería", "A", 1, PidGroup.ELECTRIC) { it * 0.08 - 200.0 },
        bmwDef(0x09C4, "Ventilador", "%", 0, PidGroup.ENGINE) { it * 0.001526 },
        bmwDef(0x0839, "Control de crucero (1 = puesto)", "", 0, PidGroup.ENGINE, size = 1) { it.toDouble() },
        bmwDef(0x07C6, "Aire acondicionado (1 = puesto)", "", 0, PidGroup.ENGINE, size = 1) { it.toDouble() },
        // Lo que el arbol de levas va desfasado del cigueñal, en grados de cigueñal. Crece si la
        // cadena se estira. Cambia de un arranque a otro, asi que se lee durante todo el trayecto.
        bmwDef(BMW_TIMING_OFFSET_ADDRESS, "Distribución: desfase del árbol de levas", "°", 2, PidGroup.ENGINE) {
            it * 0.003052 - 100.0
        },

        // El enfriador de la EGR: cuanto enfria respecto a lo que deberia. Se mueve con la marcha.
        bmwDef(0x0C99, "EGR: rendimiento del enfriador", "", 2, PidGroup.AIR) { it * 0.000122 },

        // Lo que no cambia durante un trayecto se lee una vez al conectar.
        bmwDef(BMW_ODOMETER_ADDRESS, "Cuentakilómetros", "km", 0, PidGroup.COUNTERS, Tier.ONCE, size = 4) { it.toDouble() },
        bmwDef(0x0AF0, "Horas de motor", "h", 0, PidGroup.COUNTERS, Tier.ONCE, size = 4) { it / 3600.0 },
        bmwDef(BMW_TOTAL_FUEL_ADDRESS, "Gasóleo inyectado en total", "L", 0, PidGroup.COUNTERS, Tier.ONCE) { it.toDouble() },
        bmwDef(BMW_FAULTS_ADDRESS, "Averías guardadas en la centralita", "", 0, PidGroup.COUNTERS, Tier.ONCE, size = 1) {
            it.toDouble()
        },
        bmwDef(BMW_OIL_KM_ADDRESS, "Aceite: km desde el cambio", "km", 0, PidGroup.ENGINE, Tier.ONCE) { it * 10.0 },
        bmwDef(BMW_OIL_LEVEL_ADDRESS, "Aceite: nivel", "mm", 0, PidGroup.ENGINE, Tier.ONCE, size = 1) { it * 0.292969 },
        bmwDef(0x03F3, "Filtro: intervalo medio entre regeneraciones", "km", 0, PidGroup.EXHAUST, Tier.ONCE) {
            it.toDouble()
        },
        bmwDef(0x0407, "Filtro: regeneraciones hechas", "", 0, PidGroup.EXHAUST, Tier.ONCE) { it.toDouble() },
        bmwDef(0x0BA5, "Filtro: km desde su cambio", "km", 0, PidGroup.EXHAUST, Tier.ONCE, size = 4) { it * 10.0 },
        bmwDef(0x03E9, "Filtro: ceniza calculada", "g", 0, PidGroup.EXHAUST, Tier.ONCE) { it * 0.015259 },
        bmwDef(0x042E, "Escape antes del filtro (sensor)", "°C", 0, PidGroup.EXHAUST, Tier.ONCE) { it * 0.031281 - 50.0 },
        bmwDef(0x03DF, "Filtro: horas regenerando en total", "h", 0, PidGroup.EXHAUST, Tier.ONCE) { it * 23.002976 / 3600.0 },
        bmwDef(0x0BA4, "Filtro: km que le calcula el coche", "km", 0, PidGroup.EXHAUST, Tier.ONCE) { it * 10.0 },
        // Valores aprendidos. Los factores van como desviacion en tanto por ciento de su valor de nuevo.
        bmwDef(0x0258, "Raíl: corrección aprendida de la válvula de presión", "%", 2, PidGroup.FUEL, Tier.ONCE) {
            (it * 0.000031 - 1.0) * 100.0
        },
        bmwDef(0x0A40, "Sonda lambda: corrección aprendida", "%", 2, PidGroup.FUEL, Tier.ONCE) { (it * 0.001526 - 50.0 - 1.0) * 100.0 },
        bmwDef(0x0BBD, "EGR: cero aprendido, el primero", "%", 2, PidGroup.AIR, Tier.ONCE) { it * 0.003052 - 100.0 },
        bmwDef(0x0BBE, "EGR: cero aprendido, el último", "%", 2, PidGroup.AIR, Tier.ONCE) { it * 0.003052 - 100.0 },
        bmwDef(0x0428, "Filtro: cero aprendido del sensor de presión", "hPa", 1, PidGroup.EXHAUST, Tier.ONCE) { it * 0.030518 - 1000.0 },
    ) + (0 until BMW_REGEN_STEP_COUNTERS).map { step ->
        // Contadores de un byte de la tabla ("Regenerationszähler Typ 1" a 8). Que uno suba durante
        // un trayecto dice que se ha completado una regeneracion de ese tipo; los que estan en 255
        // ya no suben.
        bmwDef(0x03F5 + step, "Filtro: regeneraciones de tipo ${step + 1}", "", 0, PidGroup.EXHAUST, size = 1) { it.toDouble() }
    } + BMW_REGENERATION_ADDRESSES.mapIndexed { index, address ->
        bmwDef(address, "Filtro: km en la regeneración ${index + 1} hacia atrás", "km", 0, PidGroup.EXHAUST, Tier.ONCE, size = 4) {
            it / 1000.0
        }
    }

    /**
     * Medidas de la misma tabla que solo se piden con un caso puesto. Todas contestaron en el
     * barrido completo del coche. La resistencia va en Pa para que quepa en dos decimales.
     */
    val bmwCases: List<PidDef> = listOf(
        bmwDef(0x0432, "Filtro: presión absoluta delante (filtrada)", "hPa", 0, PidGroup.EXHAUST) { it * 0.1 },
        bmwDef(0x043B, "Filtro: presión absoluta delante (sensor)", "hPa", 0, PidGroup.EXHAUST) { it * 0.038148 + 500.0 },
        bmwDef(0x0C81, "Filtro: presión detrás (calculada)", "hPa", 0, PidGroup.EXHAUST) { it.toDouble() },
        bmwDef(0x0409, "Filtro: corrección de ceniza del ciclo anterior", "g", 1, PidGroup.EXHAUST) { it * 0.015259 },
        bmwDef(0x0BEA, "Turbo: mando pedido al actuador", "%", 0, PidGroup.AIR) { it * 0.003052 },
        bmwDef(0x01F7, "Turbo: presión de base pedida", "hPa", 0, PidGroup.AIR) { it.toDouble() },
        // Factores que la centralita aplica al caudalimetro para compensar que se desvia con los años.
        bmwDef(0x0712, "Caudalímetro: corrección al ralentí", "", 3, PidGroup.AIR) { it * 0.000122 },
        bmwDef(0x0713, "Caudalímetro: corrección con carga", "", 3, PidGroup.AIR) { it * 0.000122 },
        bmwDef(0x0776, "EGR: temperatura tras el enfriador", "°C", 0, PidGroup.AIR) { it * 0.016022 - 50.0 },
        bmwDef(0x0C89, "EGR: gas que recircula", "kg/h", 1, PidGroup.AIR) { it * 0.009155 - 100.0 },
        bmwDef(0x05D3, "Par: límite de protección del motor", "Nm", 0, PidGroup.ENGINE) { it * 0.114443 - 2500.0 },
        bmwDef(0x03F1, "Filtro: resistencia al paso", "Pa·h/m³", 1, PidGroup.EXHAUST) { it * 0.01 },
        bmwDef(0x0420, "Filtro: presión delante", "hPa", 0, PidGroup.EXHAUST) { it * 0.091554 },
        bmwDef(0x041E, "Escape antes del catalizador", "°C", 0, PidGroup.EXHAUST) { it * 0.031281 - 50.0 },
        bmwDef(0x0434, "Escape antes del filtro (calculada)", "°C", 0, PidGroup.EXHAUST) { it * 0.031281 - 50.0 },
        bmwDef(0x0408, "Filtro: tiempo desde la última regeneración", "min", 0, PidGroup.EXHAUST, size = 4) { it / 60.0 },
        bmwDef(0x03EC, "Filtro: código de interrupción", "", 0, PidGroup.EXHAUST) { it.toDouble() },
        bmwDef(0x03DE, "Filtro: velocidad media desde la regeneración", "km/h", 0, PidGroup.EXHAUST) { it * 0.003815 },
        bmwDef(0x03EE, "Filtro: regeneración bloqueada (0 = no)", "", 0, PidGroup.EXHAUST, size = 1) { it.toDouble() },
    ) + BMW_CYLINDER_CORRECTIONS.mapIndexed { cylinder, id ->
        PidDef(id, "Inyector ${cylinder + 1}: corrección de cantidad", "mg", 2, 2, group = PidGroup.FUEL) {
            word(it) * 0.003052 - 100.0
        }
    } + listOf(
        bmwDef(0x14D3, "Inyección: corrección general de cantidad", "mg", 2, PidGroup.FUEL) { it * 0.01 - 327.67 },
        bmwDef(0x04B1, "Inyección principal: duración", "µs", 0, PidGroup.FUEL) { it * 0.038148 },
        bmwDef(0x04B0, "Preinyección: volumen", "mm³", 2, PidGroup.FUEL) { it * 0.003052 - 100.0 },
        bmwDef(0x1964, "Calibración de cantidad mínima: estado", "", 0, PidGroup.FUEL, size = 1) { it.toDouble() },
    ) + (0..2).map { point ->
        bmwDef(0x145E + point, "Calibración de inyectores: ciclos (${BMW_CALIBRATION_BAR[point]} bar)", "", 0, PidGroup.FUEL) { it.toDouble() }
    } + listOf(
        bmwDef(0x01F9, "Turbo: desviación que ve la centralita", "hPa", 0, PidGroup.AIR) { it.toDouble() },
        bmwDef(0x0C86, "Turbo: giro calculado", "rpm", 0, PidGroup.AIR) { it * 3.814755 },
        bmwDef(0x0709, "Aire por cilindro", "mg", 0, PidGroup.AIR) { it * 0.024414 },
        bmwDef(0x079E, "Aire por cilindro pedido", "mg", 0, PidGroup.AIR) { it * 0.030518 },
        bmwDef(0x07A2, "Aire: desviación", "mg", 1, PidGroup.AIR) { it * 0.1 },
        bmwDef(0x07AD, "EGR: gas que recircula (calculado)", "%", 0, PidGroup.AIR) { it * 0.012207 },
        bmwDef(0x0793, "EGR: gas que pide recircular", "%", 0, PidGroup.AIR) { it * 0.01 },
        bmwDef(0x0B1F, "Mariposa: posición", "%", 0, PidGroup.AIR) { it * 0.001526 },
        bmwDef(0x0C87, "Aire tras el compresor (calculada)", "°C", 0, PidGroup.AIR) { it * 0.009155 - 100.0 },
        bmwDef(0x0777, "EGR: gas tras su enfriador", "°C", 0, PidGroup.AIR) { it * 0.016022 - 50.0 },
        bmwDef(0x0C1C, "Presión ambiente (centralita)", "hPa", 0, PidGroup.AIR) { it * 0.030518 },
        bmwDef(0x1609, "Calentadores: potencia", "W", 0, PidGroup.ENGINE) { it.toDouble() },
        bmwDef(0x160B, "Calentadores: temperatura", "°C", 0, PidGroup.ENGINE) { it * 0.1 - 273.14 },
        bmwDef(0x15FE, "Calentadores: estado (código)", "", 0, PidGroup.ENGINE) { it.toDouble() },
        bmwDef(0x15FF, "Calentadores: testigo (1 = encendido)", "", 0, PidGroup.ENGINE, size = 1) { it.toDouble() },
        bmwDef(0x012C, "Batería: tensión (centralita)", "V", 2, PidGroup.ELECTRIC) { it * 0.000389105 },
        bmwDef(0x01B1, "Batería: margen para arrancar", "", 1, PidGroup.ELECTRIC) { it * 0.003052 },
        bmwDef(0x01B8, "Batería: temperatura", "°C", 0, PidGroup.ELECTRIC) { it * 0.009237 - 50.0 },
        bmwDef(0x01B9, "Batería: tensión en el borne", "V", 2, PidGroup.ELECTRIC) { it * 0.00025 + 6.0 },
        // El gasoleo antes de la bomba de alta: lo que da la bomba del deposito y lo que le cuesta.
        bmwDef(0x02DC, "Gasóleo: presión de baja", "bar", 2, PidGroup.FUEL) { it * 0.001 },
        bmwDef(0x0327, "Bomba del depósito: corriente", "A", 1, PidGroup.FUEL, size = 1) { it * 0.1 },
        bmwDef(0x0324, "Bomba del depósito: tensión", "V", 1, PidGroup.FUEL, size = 1) { it * 0.1 },
        bmwDef(0x05DF, "Bomba del depósito: mando", "%", 0, PidGroup.FUEL) { it * 0.012207 },
        bmwDef(0x05DE, "Bomba del depósito: giro", "rpm", 0, PidGroup.FUEL) { it.toDouble() },
        bmwDef(0x0674, "Raíl: presión real (centralita)", "bar", 0, PidGroup.FUEL) { it * 0.045777 },
        bmwDef(0x0644, "Raíl: desviación", "bar", 1, PidGroup.FUEL) { it * 0.1 },
        // La centralita manda en el motor de arranque: lo suelta cuando el motor pasa de unas vueltas.
        bmwDef(0x069A, "Motor de arranque (1 = actuando)", "", 0, PidGroup.ENGINE, size = 1) { it.toDouble() },
        bmwDef(0x0D8E, "Motor de arranque: se suelta a", "rpm", 0, PidGroup.ENGINE) { it * 0.091554 },
        bmwDef(0x01BC, "Alternador: corriente", "", 0, PidGroup.ELECTRIC, size = 1) { it.toDouble() },
        bmwDef(0x01AF, "Alternador: tensión que se le pide", "V", 1, PidGroup.ELECTRIC) { it * 0.1 },
        bmwDef(0x0186, "Alternador: carga", "%", 0, PidGroup.ELECTRIC) { it * 0.003052 },
        bmwDef(0x0E43, "Alternador: temperatura", "°C", 0, PidGroup.ELECTRIC) { it * 0.1 },
        bmwDef(0x10D5, "Energía recuperada en retenciones", "Ah", 2, PidGroup.ELECTRIC) { it * 0.018204 },
        bmwDef(0x0EA6, "Marcha (0 = punto muerto)", "", 0, PidGroup.ENGINE, size = 1) { it.toDouble() },
        bmwDef(0x07D1, "Par motor", "Nm", 0, PidGroup.ENGINE) { it * 0.114443 - 2500.0 },
        bmwDef(0x0578, "Par: parte del máximo", "%", 0, PidGroup.ENGINE) { it * 0.001526 },
        bmwDef(0x0579, "Par: límite ahora", "Nm", 0, PidGroup.ENGINE) { it * 0.1 },
        bmwDef(0x1036, "Par perdido en rozamiento", "Nm", 0, PidGroup.ENGINE) { it * 0.114443 - 2500.0 },
        bmwDef(0x0DE3, "Control de crucero: estado (0 apagado, 1 en espera, 2 llevando)", "", 0, PidGroup.ENGINE, size = 1) {
            it.toDouble()
        },
        bmwDef(0x0DE2, "Control de crucero: velocidad fijada", "km/h", 0, PidGroup.ENGINE) { it * 0.01 },
        bmwDef(0x0DDF, "Control de crucero: motivo de desconexión (código)", "", 0, PidGroup.ENGINE, size = 1) { it.toDouble() },
        bmwDef(0x106B, "Freno pisado (1 = sí)", "", 0, PidGroup.ENGINE, size = 1) { it.toDouble() },
        bmwDef(0x0F0A, "Embrague: estado (código)", "", 0, PidGroup.ENGINE, size = 1) { it.toDouble() },
        bmwDef(0x1068, "Servofreno: presión", "hPa", 0, PidGroup.ENGINE) { it.toDouble() },
    ) + BMW_GLOW_FAULTS.map { (id, text) ->
        PidDef(id, "Calentadores: $text (0 = no)", "", 1, 0, group = PidGroup.ENGINE) { it[0].toDouble() }
    }

    /**
     * Medidas de la tabla del N47 que el coche da tambien por el OBD normal: refrigerante,
     * revoluciones, tension y presion ambiente. Sirven para comprobar que la tabla es la de la
     * centralita que contesta.
     */
    val bmwChecks: List<PidDef> = listOf(
        bmwDef(0x0547, "Refrigerante (BMW)", "°C", 0, PidGroup.ENGINE) { it * 0.01 - 100.0 },
        bmwDef(0x10CF, "Revoluciones (BMW)", "rpm", 0, PidGroup.ENGINE) { it.toDouble() },
        bmwDef(0x0042, "Tensión (BMW)", "V", 1, PidGroup.ELECTRIC) { it * 0.001 },
        bmwDef(0x0C1C, "Presión ambiente (BMW)", "hPa", 0, PidGroup.AIR) { it * 0.030518 },
    )

    /** Si a un coche merece la pena preguntarle por el protocolo propio de BMW. */
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
            bmwDiesel +
            bmwCases

    val byId: Map<Int, PidDef> = all.associateBy { it.id }

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
