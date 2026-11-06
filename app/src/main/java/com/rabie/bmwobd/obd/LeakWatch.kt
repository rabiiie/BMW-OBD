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
    /** Vueltas en las que no llego la lambda: si son todas, la prueba no puede decir nada. */
    val noLambda: Int = 0,
    val perSecond: Double = 0.0,
    val bands: List<LeakBand> = LeakWatch.emptyBands(),
    val verdict: String? = null,
    val message: String? = null,
    /** El CSV de la ultima prueba, para compartirlo. */
    val file: java.io.File? = null,
)

/** Una vuelta de la prueba: todas las medidas leidas seguidas. [mode] es el modo del motor, null si no se sabe. */
data class LeakSample(
    val tMs: Long,
    val rpm: Double?,
    val mapKpa: Double?,
    val mafGs: Double?,
    val lambda: Double?,
    val mg: Double?,
    val egr: Double?,
    val coolant: Double?,
    val mode: Int? = null,
)

/**
 * La prueba de fuga de admision. Si el tubo del intercooler pierde con presion, el caudalimetro
 * cuenta aire que no llega a los cilindros: la lambda que sale de el y del gasoleo inyectado queda
 * por encima de la que mide la sonda, y mas cuanto mas sopla el turbo.
 *
 * Vale con la EGR abierta: el caudalimetro solo ve aire fresco y la sonda ve todo ese aire con todo
 * el gasoleo, recircule lo que recircule. Solo cuentan los momentos estables (la sonda va por detras
 * de un cambio brusco) y fuera de las regeneraciones, cuya postinyeccion no entra en la cantidad
 * inyectada.
 *
 * Referencia de este coche, de 909 lecturas estables entre el 20 de octubre y el 5 de noviembre de
 * 2026 con la lambda del OBD: la relacion queda en 1,01 de mediana, y entre 110-140 kPa y mas de
 * 140 kPa baja un ~10 % (1,02 a 0,91). Una fuga con presion la haria subir.
 */
class LeakWatch {

    private val ratios = List(BANDS.size) { ArrayList<Double>() }
    private val recent = ArrayDeque<Long>()
    private val last = ArrayDeque<LeakSample>()
    private var state = LeakWatchState(running = true)

    val now: LeakWatchState get() = state

    /** Apunta una vuelta. Devuelve la relacion si la lectura vale para la prueba. */
    fun sample(s: LeakSample): Double? {
        recent.addLast(s.tMs)
        while (s.tMs - recent.first() > RATE_WINDOW_MS) recent.removeFirst()
        val span = s.tMs - recent.first()
        last.addLast(s)
        while (last.size > STEADY_SAMPLES) last.removeFirst()
        val ratio = ratioOf(s)?.takeIf { steady() }
        val band = ratio?.let { bandOf(s.mapKpa!!) }
        if (ratio != null && band != null) ratios[band].add(ratio)
        state = state.copy(
            kpa = s.mapKpa,
            rpm = s.rpm,
            lambda = s.lambda,
            lambdaFromAir = ratio?.let { it * s.lambda!! } ?: state.lambdaFromAir,
            rows = state.rows + 1,
            valid = state.valid + if (ratio != null) 1 else 0,
            noLambda = state.noLambda + if (s.lambda == null) 1 else 0,
            perSecond = if (span > 0) (recent.size - 1) * 1000.0 / span else 0.0,
        )
        state = state.copy(bands = bands(), verdict = verdict())
        return ratio
    }

    /** Las ultimas vueltas sin cambios bruscos de revoluciones ni de presion. */
    private fun steady(): Boolean {
        if (last.size < STEADY_SAMPLES) return false
        val rpm = last.map { it.rpm ?: return false }
        val kpa = last.map { it.mapKpa ?: return false }
        return rpm.max() - rpm.min() <= STEADY_RPM && kpa.max() - kpa.min() <= STEADY_KPA
    }

    private fun bands(): List<LeakBand> = BANDS.mapIndexed { i, (from, to) ->
        LeakBand(from, to, ratios[i].takeIf { it.size >= MIN_SAMPLES }?.let(::median), ratios[i].size)
    }

    /**
     * Compara 110-140 kPa con la franja mas alta que tenga lecturas. Por debajo de 110 la relacion
     * sale muy dispersa en los trayectos de este coche (1,0-1,4), por eso no se usa de referencia.
     */
    private fun verdict(): String {
        if (state.rows >= MIN_ROWS_FOR_LAMBDA && state.noLambda == state.rows) {
            return "El coche no está dando la lambda: así la prueba no puede decir nada."
        }
        val all = bands()
        val reference = all[REFERENCE_BAND].takeIf { it.ratio != null }
            ?: return "Faltan lecturas estables entre 110 y 140 kPa: conduce normal, con alguna subida a ritmo constante."
        val high = all.drop(REFERENCE_BAND + 1).lastOrNull { it.ratio != null }
            ?: return "Faltan lecturas estables por encima de 140 kPa: hace falta alguna subida o aceleración larga con el turbo soplando."
        val change = (high.ratio!! / reference.ratio!! - 1) * 100
        val text = "de %.2f a %.2f (%+.0f %%); en octubre bajaba un ~10 %%".format(java.util.Locale.US, reference.ratio, high.ratio, change)
        return when {
            change > SUSPECT_PERCENT -> "Posible fuga con presión: la relación sube $text."
            change < CLEAR_PERCENT -> "Sin señal de fuga: la relación baja $text."
            else -> "Dudoso: la relación va $text. Compara con otro trayecto."
        }
    }

    fun stopped(message: String, file: java.io.File?) {
        state = state.copy(running = false, message = message, file = file)
    }

    companion object {
        const val RPM_MIN = 1200.0
        const val MG_MIN = 8.0
        const val WARM_COOLANT = 75.0
        const val STOICHIOMETRIC = 14.5

        /** La lambda del OBD se queda en 2,0 por arriba: lo que llega ahi no es una medida. */
        const val LAMBDA_MAX = 1.95
        const val LAMBDA_MIN = 0.8
        const val STEADY_SAMPLES = 3
        const val STEADY_RPM = 100.0
        const val STEADY_KPA = 8.0
        const val MIN_SAMPLES = 5
        const val MIN_ROWS_FOR_LAMBDA = 20
        const val REFERENCE_BAND = 1
        const val SUSPECT_PERCENT = 5.0
        const val CLEAR_PERCENT = -5.0
        const val RATE_WINDOW_MS = 4_000L
        private const val CYLINDERS = 4

        /** Una de cada tantas vueltas se leen tambien el refrigerante y el modo del motor: no cambian deprisa. */
        const val SLOW_EVERY = 10

        val BANDS: List<Pair<Double, Double?>> = listOf(90.0 to 110.0, 110.0 to 140.0, 140.0 to 180.0, 180.0 to null)

        fun emptyBands(): List<LeakBand> = BANDS.map { (from, to) -> LeakBand(from, to, null, 0) }

        fun bandOf(kpa: Double): Int? = BANDS.indexOfFirst { (from, to) -> kpa >= from && (to == null || kpa < to) }.takeIf { it >= 0 }

        /**
         * Lambda del caudalimetro y del gasoleo frente a la de la sonda; null si la lectura no vale:
         * motor frio, poca carga, regenerando o la sonda fuera de lo que mide.
         */
        fun ratioOf(s: LeakSample): Double? {
            val rpm = s.rpm ?: return null
            val maf = s.mafGs ?: return null
            val lambda = s.lambda ?: return null
            val mg = s.mg ?: return null
            if (s.mapKpa == null || s.coolant == null) return null
            if (s.mode != null && s.mode != Pids.MODE_NORMAL) return null
            if (s.coolant < WARM_COOLANT || rpm < RPM_MIN || mg < MG_MIN) return null
            if (lambda !in LAMBDA_MIN..LAMBDA_MAX) return null
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
