package com.rabie.bmwobd.obd

/** Una franja de presion de admision de la prueba de fuga: la relacion mediana y cuantas lecturas valen. */
data class LeakBand(val fromKpa: Double, val toKpa: Double?, val ratio: Double?, val samples: Int)

/** Lo que enseña la prueba de fuga de admision mientras dura y al acabar. */
data class LeakWatchState(
    val running: Boolean = false,
    val kpa: Double? = null,
    val rpm: Double? = null,
    val lambda: Double? = null,
    /** La lambda que sale del caudalimetro y del gasoleo inyectado en la ultima lectura que vale. */
    val lambdaFromAir: Double? = null,
    val rows: Int = 0,
    val valid: Int = 0,
    val perSecond: Double = 0.0,
    val bands: List<LeakBand> = LeakWatch.emptyBands(),
    val verdict: String? = null,
    val message: String? = null,
    /** El CSV de la ultima prueba, para compartirlo. */
    val file: java.io.File? = null,
)

/** Una vuelta de la prueba: todas las medidas leidas seguidas. */
data class LeakSample(
    val tMs: Long,
    val rpm: Double?,
    val mapKpa: Double?,
    val mafGs: Double?,
    val lambda: Double?,
    val mg: Double?,
    val egr: Double?,
    val coolant: Double?,
)

/**
 * La prueba de fuga de admision. Si el tubo del intercooler pierde con presion, el caudalimetro
 * cuenta aire que no llega a los cilindros: la lambda que sale de el y del gasoleo inyectado queda
 * por encima de la que mide la sonda, y mas cuanto mas sopla el turbo. Se compara por franjas de
 * presion, con la EGR cerrada (el caudalimetro ve todo el aire que entra) y con carga, porque con
 * poco pedal la lambda es muy alta y la cuenta no dice nada. No hay referencia de BMW: vale para
 * comparar antes y despues de un cambio, en el mismo tramo.
 */
class LeakWatch {

    private val ratios = List(BANDS.size) { ArrayList<Double>() }
    private val recent = ArrayDeque<Long>()
    private var state = LeakWatchState(running = true)

    val now: LeakWatchState get() = state

    /** Apunta una vuelta. Devuelve la relacion si la lectura vale para la prueba. */
    fun sample(s: LeakSample): Double? {
        recent.addLast(s.tMs)
        while (s.tMs - recent.first() > RATE_WINDOW_MS) recent.removeFirst()
        val span = s.tMs - recent.first()
        val ratio = ratioOf(s)
        val band = ratio?.let { BANDS.indexOfFirst { (from, to) -> s.mapKpa!! >= from && (to == null || s.mapKpa < to) } }
        if (ratio != null && band != null && band >= 0) ratios[band].add(ratio)
        state = state.copy(
            kpa = s.mapKpa,
            rpm = s.rpm,
            lambda = s.lambda,
            lambdaFromAir = ratio?.let { it * s.lambda!! } ?: state.lambdaFromAir,
            rows = state.rows + 1,
            valid = state.valid + if (ratio != null) 1 else 0,
            perSecond = if (span > 0) (recent.size - 1) * 1000.0 / span else 0.0,
            bands = bands(),
            verdict = verdict(),
        )
        return ratio
    }

    private fun bands(): List<LeakBand> = BANDS.mapIndexed { i, (from, to) ->
        LeakBand(from, to, ratios[i].takeIf { it.size >= MIN_SAMPLES }?.let(::median), ratios[i].size)
    }

    /** Compara la franja mas baja con datos con la mas alta, si esta llega a soplar de verdad. */
    private fun verdict(): String {
        val usable = bands().filter { it.ratio != null }
        val low = usable.firstOrNull()
        val high = usable.lastOrNull()
        if (low == null || high == null || high === low || high.fromKpa < BOOSTED_KPA) {
            return "Faltan lecturas con el turbo soplando: con el motor caliente, acelera fuerte en 3ª o 4ª de 1.500 a 3.500 rpm."
        }
        val growth = (high.ratio!! / low.ratio!! - 1) * 100
        val change = "de %.2f a %.2f (%+.0f %%)".format(java.util.Locale.US, low.ratio, high.ratio, growth)
        return if (growth > SUSPECT_GROWTH_PERCENT) {
            "Posible fuga con presión: la relación sube $change al soplar el turbo."
        } else {
            "Sin señal de fuga: la relación va $change."
        }
    }

    fun stopped(message: String, file: java.io.File?) {
        state = state.copy(running = false, message = message, file = file)
    }

    companion object {
        const val RPM_MIN = 1200.0
        const val MG_MIN = 8.0
        const val EGR_CLOSED_MAX = 2.0
        const val WARM_COOLANT = 75.0
        const val STOICHIOMETRIC = 14.5
        const val MIN_SAMPLES = 5
        const val BOOSTED_KPA = 140.0
        const val SUSPECT_GROWTH_PERCENT = 10.0
        const val RATE_WINDOW_MS = 4_000L
        private const val CYLINDERS = 4

        /** Una de cada tantas vueltas se lee tambien el refrigerante: no cambia deprisa. */
        const val COOLANT_EVERY = 10

        val BANDS: List<Pair<Double, Double?>> = listOf(90.0 to 110.0, 110.0 to 140.0, 140.0 to 180.0, 180.0 to null)

        fun emptyBands(): List<LeakBand> = BANDS.map { (from, to) -> LeakBand(from, to, null, 0) }

        /**
         * Lambda del caudalimetro y del gasoleo frente a la de la sonda; null si la lectura no vale:
         * motor frio, EGR abierta, poca carga o la sonda fuera de lo que da en marcha.
         */
        fun ratioOf(s: LeakSample): Double? {
            val rpm = s.rpm ?: return null
            val maf = s.mafGs ?: return null
            val lambda = s.lambda ?: return null
            val mg = s.mg ?: return null
            if (s.mapKpa == null || s.egr == null || s.coolant == null) return null
            if (s.coolant < WARM_COOLANT || s.egr > EGR_CLOSED_MAX || rpm < RPM_MIN || mg < MG_MIN) return null
            if (lambda !in 0.8..4.0) return null
            val fuelGs = mg / 1000.0 * rpm / 120.0 * CYLINDERS
            return maf / fuelGs / STOICHIOMETRIC / lambda
        }

        private fun median(values: List<Double>): Double {
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
        }
    }
}
