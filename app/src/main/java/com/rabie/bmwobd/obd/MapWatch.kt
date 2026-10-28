package com.rabie.bmwobd.obd

/** Lo que enseña la prueba del sensor MAP mientras dura y al acabar. */
data class MapWatchState(
    val running: Boolean = false,
    val kpa: Double? = null,
    val baro: Double? = null,
    val min: Double? = null,
    val max: Double? = null,
    val perSecond: Double = 0.0,
    val samples: Int = 0,
    val drops: Int = 0,
    val rpm: Double? = null,
    val message: String? = null,
    /** El CSV de la ultima prueba, para compartirlo. */
    val file: java.io.File? = null,
)

/**
 * La prueba del sensor MAP: el MAP leido solo y en bucle, contra la presion atmosferica. Al ralenti
 * el turbo no sopla y el MAP tiene que marcar lo mismo que la calle; si cae mas de [DROP_KPA] por
 * debajo es el sensor o su conector, como la 41AB del 27 de octubre (439 hPa con la calle a 920).
 * Acelerando sube por el turbo y la comparacion no vale: las caidas solo cuentan al ralenti.
 */
class MapWatch(private val baro: Double) {

    private var state = MapWatchState(running = true, baro = baro)
    private val recent = ArrayDeque<Long>()
    private var inDrop = false

    val now: MapWatchState get() = state

    /** Apunta una lectura del MAP. Devuelve true si empieza una caida. */
    fun sample(tMs: Long, kpa: Double): Boolean {
        recent.addLast(tMs)
        while (tMs - recent.first() > RATE_WINDOW_MS) recent.removeFirst()
        val span = tMs - recent.first()
        val idle = state.rpm?.let { it < IDLE_MAX_RPM } ?: false
        val low = kpa < baro - DROP_KPA
        val starts = idle && low && !inDrop
        if (starts) inDrop = true
        if (inDrop && kpa >= baro - RECOVERED_KPA) inDrop = false
        state = state.copy(
            kpa = kpa,
            min = minOf(kpa, state.min ?: kpa),
            max = maxOf(kpa, state.max ?: kpa),
            samples = state.samples + 1,
            drops = state.drops + if (starts) 1 else 0,
            perSecond = if (span > 0) (recent.size - 1) * 1000.0 / span else 0.0,
        )
        return starts
    }

    fun rpm(value: Double) {
        state = state.copy(rpm = value)
    }

    companion object {
        const val DROP_KPA = 10.0
        const val RECOVERED_KPA = 5.0
        const val IDLE_MAX_RPM = 1100.0
        const val RATE_WINDOW_MS = 2_000L

        /** Una de cada tantas preguntas es de revoluciones: dicen si sigue al ralenti. */
        const val RPM_EVERY = 10
    }
}
