package com.rabie.bmwobd.cases

import com.rabie.bmwobd.advice.Moment
import com.rabie.bmwobd.health.HealthCard
import com.rabie.bmwobd.health.HealthLevel
import com.rabie.bmwobd.health.HealthSystem
import com.rabie.bmwobd.obd.Pids
import java.util.Locale

/**
 * Un caso es lo que se quiere mirar de cerca. Las medidas de [watch] se leen una por vuelta en
 * vez de esperar su turno lento; las de [also] se suman al turno lento mientras el caso esta puesto.
 */
enum class Case(val title: String, val watch: List<Int>, val also: List<Int>) {
    NORMAL("Normal", emptyList(), emptyList()),
    FILTER(
        "Filtro de partículas",
        watch = listOf(0x0404, 0x03EA, 0x03ED, 0x03F1, 0x0424, 0x0420, 0x041E, 0x0434).map(Pids::bmw),
        also = listOf(0x0405, 0x0408, 0x03EC, 0x05AA, 0x03DE, 0x03E0).map(Pids::bmw),
    ),
}

/** Lo que pasa ahora en lo que mira un caso, dicho sin jerga. */
object CaseCards {

    private val FILTER_DISTANCE = Pids.bmw(0x03EB)
    private val FILTER_MINUTES = Pids.bmw(0x0408)
    private val FILTER_FUEL = Pids.bmw(0x03E8)
    private val FILTER_PRESSURE = Pids.bmw(0x0424)
    private val FILTER_RESISTANCE = Pids.bmw(0x03F1)
    private val BEFORE_CATALYST = Pids.bmw(0x041E)
    private val BEFORE_FILTER = Pids.bmw(0x0434)
    private val ENGINE_MODE = Pids.bmw(0x05AA)

    // Lo que marcaba el modo del motor en el coche fuera de una regeneracion. Regenerando marco 0x02010002.
    private const val MODE_NORMAL = 0x00100001.toDouble()

    fun card(case: Case, values: Map<Int, Double>): HealthCard? = when (case) {
        Case.NORMAL -> null
        Case.FILTER -> filter(values)
    }

    private fun filter(values: Map<Int, Double>): HealthCard {
        val asked = values[Pids.BMW_REGEN_REQUEST]
        val measured = values[Pids.BMW_SOOT_MEASURED]
        val model = values[Pids.BMW_SOOT_MODEL]
        val distance = values[FILTER_DISTANCE]
        val minutes = values[FILTER_MINUTES]
        val catalyst = values[BEFORE_CATALYST] ?: Moment.EXHAUST_TEMPS.mapNotNull { values[it] }.maxOrNull()
        val facts = listOfNotNull(
            if (measured != null && model != null) {
                "Hollín: ${n(measured, 1)} g medidos por la presión y ${n(model, 1)} g calculados por el uso."
            } else {
                measured?.let { "Hollín: ${n(it, 1)} g." }
            },
            distance?.let { km ->
                "Última regeneración: hace ${n(km, if (km < 10) 1 else 0)} km" + (minutes?.let { " y ${n(it)} min de motor." } ?: ".")
            },
            values[FILTER_FUEL]?.let { "Gasóleo gastado desde entonces: ${n(it, 1)} L." },
            catalyst?.let { hot ->
                "Escape: ${n(hot)} °C antes del catalizador" + (values[BEFORE_FILTER]?.let { " y ${n(it)} °C antes del filtro." } ?: ".")
            },
            values[FILTER_PRESSURE]?.let { pressure ->
                "Al escape le cuesta ${n(pressure)} hPa cruzar el filtro" +
                    (values[FILTER_RESISTANCE]?.let { "; resistencia ${n(it, 1)}." } ?: ".")
            },
        )
        val waiting = values[ENGINE_MODE] == MODE_NORMAL
        return when {
            asked == null -> HealthCard(HealthSystem.FILTER, HealthLevel.UNKNOWN, "Esperando los datos del filtro.", facts)
            asked > 0 && waiting -> HealthCard(
                HealthSystem.FILTER, HealthLevel.WATCH,
                "La centralita quiere regenerar y todavía no ha empezado.",
                facts,
                "Ayuda circular unos minutos seguidos por carretera, sin parar el motor.",
            )
            asked > 0 -> HealthCard(
                HealthSystem.FILTER, HealthLevel.WATCH,
                "Está regenerando.",
                facts,
                "Si puedes, no apagues el motor hasta que termine. Al acabar, la distancia desde la última regeneración vuelve a cero.",
            )
            else -> HealthCard(HealthSystem.FILTER, HealthLevel.OK, "No está regenerando.", facts)
        }
    }

    private fun n(value: Double, decimals: Int = 0) = String.format(Locale.US, "%.${decimals}f", value).replace('.', ',')
}
