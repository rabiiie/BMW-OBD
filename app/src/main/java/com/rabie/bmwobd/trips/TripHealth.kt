package com.rabie.bmwobd.trips

import com.rabie.bmwobd.advice.Limits
import com.rabie.bmwobd.advice.Moment
import com.rabie.bmwobd.obd.BmwFault
import com.rabie.bmwobd.obd.Pids
import java.util.Locale

/**
 * Como se calento el motor. [minutesTo80] solo existe si arranco frio; [steady] es la temperatura
 * en la que se quedo, la mediana pasado un cuarto de hora de marcha.
 */
data class WarmUp(val startCoolant: Double, val outside: Double?, val minutesTo80: Double?, val steady: Double?)

/**
 * El primer arranque grabado. [seconds] va de la ultima lectura con el motor parado a la primera
 * ya a ralenti: con dos lecturas por segundo, tiene medio segundo de margen.
 */
data class EngineStart(val seconds: Double, val restVolts: Double?, val coolant: Double?, val parkedMinutes: Double? = null)

/**
 * Lo que da el coche frente a lo que pide la centralita, en los ratos en que las dos cosas estan
 * quietas. [median] negativa es que da menos de lo pedido; [low] es el decil mas bajo.
 */
data class Gap(val median: Double, val low: Double, val samples: Int)

/** El ralenti con el motor caliente y el coche parado: a cuantas vueltas va y cuanto baila (del 5 % al 95 % de las lecturas). */
data class Idle(val rpm: Double, val spread: Double, val samples: Int)

/** La tension con el motor en marcha: la mediana y el decil mas bajo. */
data class Charge(val volts: Double, val low: Double)

/**
 * El filtro de particulas: la presion que le cuesta al escape pasar por el a ralenti en caliente,
 * los kilometros que hubo entre las dos ultimas regeneraciones, la resistencia que calcula la app
 * (presion diferencial por cada 100 m³/h de gas, con el caudal que da la centralita) y cuantas
 * veces la presion antes de la turbina es la del turbo cuando sopla. Las dos ultimas valen para
 * comparar trayectos: un filtro que se carga sube las dos.
 */
data class FilterCheck(
    val idlePressure: Double?,
    val lastInterval: Double?,
    val resistance: Double? = null,
    val turbineRatio: Double? = null,
    /** Minutos con el escape de 280 a 350 °C y a mas de 60 km/h: la regeneracion continua de BMW. */
    val continuousMinutes: Double? = null,
    /** Minutos con el escape por encima de 500 °C, donde BMW da por lograda la regeneracion. */
    val hotMinutes: Double? = null,
    /** El tramo seguido mas largo a mas de 88 km/h; BMW pide unos 45 min para salir de 480A/481A. */
    val longestFastMinutes: Double? = null,
    /** Como estaban 480A y 481A al empezar y al acabar (BmwFault.filterLevel). */
    val faultsStart: Double? = null,
    val faultsEnd: Double? = null,
)

/**
 * Lo mas que dio el motor en el trayecto, segun el par que calcula la centralita, y la mejor
 * recuperacion de 80 a 120 hecha con el pedal a fondo. El par se lee cada varios segundos, asi
 * que el maximo de verdad puede haber sido algo mayor.
 */
data class Performance(
    val torque: Double,
    val torqueRpm: Double,
    val horsepower: Double,
    val horsepowerRpm: Double,
    val from80To120: Double?,
)

/**
 * Lo que solo sirve para las causas de un filtro cargado: si el trayecto dio señal de filtro cargado
 * (480A/481A, regeneracion de proteccion o peticion 7), las averias de calentadores, la correccion
 * del caudalimetro con carga y la desviacion de aire (medianas).
 */
data class FilterCauses(
    val filterTrouble: Boolean,
    val glowFaults: Double?,
    val mafCorrection: Double?,
    val airDeviation: Double?,
) {
    companion object {
        private val MAF_CORRECTION = Pids.bmw(0x0713)
        private val AIR_DEVIATION = Pids.bmw(0x07A2)
        private const val PROTECTION_REQUEST = 7.0

        fun of(data: TripData): FilterCauses? {
            val faults = data.series[Pids.BMW_FILTER_FAULTS]
            val mode = data.series[Pids.BMW_ENGINE_MODE]
            val request = data.series[Pids.BMW_REGEN_REQUEST]
            if (faults == null && mode == null && request == null) return null
            val trouble = faults?.any { it >= 1 } == true ||
                mode?.any { !it.isNaN() && it.toLong().toInt() == Pids.MODE_PROTECTION } == true ||
                request?.any { it == PROTECTION_REQUEST } == true
            return FilterCauses(
                filterTrouble = trouble,
                glowFaults = data.series[Pids.BMW_GLOW_FAULT_COUNT]?.filter { !it.isNaN() }?.maxOrNull(),
                mafCorrection = median(data.series[MAF_CORRECTION]),
                airDeviation = median(data.series[AIR_DEVIATION]),
            )
        }

        private fun median(series: DoubleArray?): Double? =
            series?.filter { !it.isNaN() }?.sorted()?.takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }
    }
}

/** Lo que un trayecto dice de la salud del coche. Lo que no se pudo medir queda en null. */
data class TripHealth(
    val warmUp: WarmUp?,
    val start: EngineStart?,
    val boost: Gap?,
    val rail: Gap? = null,
    val egr: Gap? = null,
    val idle: Idle? = null,
    val charge: Charge? = null,
    val filter: FilterCheck? = null,
    /** Segundos pidiendole fuerza con el motor frio; null si arranco ya caliente. */
    val coldPushSeconds: Long? = null,
    val performance: Performance? = null,
    /** Segundos con el motor ahogado: mucha carga a pocas vueltas, lo mismo que avisa el panel. */
    val luggingSeconds: Long? = null,
    /** Lo que hace falta para las causas que BMW manda descartar cuando el filtro se carga. */
    val causes: FilterCauses? = null,
) {

    private val empty: Boolean
        get() = listOf(warmUp, start, boost, rail, egr, idle, charge, filter, coldPushSeconds, performance, luggingSeconds).all { it == null } &&
            causes?.filterTrouble != true

    /** Los datos, ya en texto, para la ficha del trayecto. */
    fun facts(): List<Pair<String, String>> = listOfNotNull(
        warmUp?.minutesTo80?.let {
            "Calentamiento: de ${n(warmUp.startCoolant)} °C a 80 °C" to
                n(it, 1) + " min" + (warmUp.outside?.let { out -> ", con ${n(out)} °C fuera" } ?: "")
        },
        warmUp?.steady?.let { "Temperatura del motor ya caliente (mediana)" to n(it) + " °C" },
        start?.let { "Arranque: de parado a ralentí" to "≈ " + n(it.seconds, 1) + " s" + (it.coolant?.let { c -> ", motor a ${n(c)} °C" } ?: "") },
        start?.parkedMinutes?.let { "Tiempo parado antes de este arranque" to parked(it) },
        start?.restVolts?.let { "Batería antes de arrancar, con el contacto puesto" to n(it, 1) + " V" },
        boost?.let {
            "Turbo: presión real menos la pedida" to
                "${signed(it.median)} kPa de mediana, ${signed(it.low)} en el peor décimo (${it.samples} lecturas)"
        },
        rail?.let {
            "Raíl: presión real menos la pedida" to
                "${signed(it.median)} bar de mediana, ${signed(it.low)} en el peor décimo (${it.samples} lecturas)"
        },
        egr?.let { "EGR: lo que abre de más o de menos sobre lo mandado" to "${signed(it.median)} % de mediana (${it.samples} lecturas)" },
        idle?.let { "Ralentí en caliente" to "${n(it.rpm)} rpm, baila ${n(it.spread)} rpm (${it.samples} lecturas)" },
        charge?.let { "Tensión con el motor en marcha" to "${n(it.volts, 1)} V de mediana, ${n(it.low, 1)} V en el décimo más bajo" },
        filter?.idlePressure?.let { "Filtro de partículas: presión a ralentí en caliente" to n(it) + " hPa" },
        filter?.lastInterval?.let { "Filtro de partículas: entre las dos últimas regeneraciones" to n(it) + " km" },
        filter?.resistance?.let { "Filtro de partículas: resistencia al paso" to "≈ " + n(it, 1) + " hPa por cada 100 m³/h de gas (mediana)" },
        filter?.continuousMinutes?.let { "Escape de 280 a 350 °C a más de 60 km/h (regeneración continua)" to n(it, 1) + " min" },
        filter?.hotMinutes?.let { "Escape por encima de 500 °C" to n(it, 1) + " min" },
        filter?.longestFastMinutes?.let { "Tramo seguido más largo a más de 90 km/h" to n(it, 1) + " min (BMW pide unos 45 para salir de 480A/481A)" },
        filter?.faultsEnd?.let { end ->
            "Averías del filtro" to (filter.faultsStart?.takeIf { it != end }?.let { start ->
                BmwFault.filterLevelText(start) + " al empezar; " + BmwFault.filterLevelText(end) + " al acabar"
            } ?: (BmwFault.filterLevelText(end) + " todo el trayecto"))
        },
        filter?.turbineRatio?.let { "Escape antes de la turbina frente al turbo" to "≈ " + n(it, 2) + " veces, cuando el turbo sopla (mediana)" },
        coldPushSeconds?.let {
            "Motor frío exigido" to if (it == 0L) "nada: no se le pidió fuerza por debajo de ${n(Limits.COLD_OIL)} °C" else
                "$it s pidiéndole fuerza o muchas vueltas por debajo de ${n(Limits.COLD_OIL)} °C"
        },
        luggingSeconds?.let {
            "Motor ahogado" to if (it == 0L) "nada" else
                "$it s con mucha carga por debajo de ${n(Limits.LUGGING_MAX_RPM)} rpm. Castiga la cadena y el volante bimasa: una marcha menos"
        },
        performance?.let {
            "Lo más que dio el motor" to "${n(it.torque)} Nm a ${n(it.torqueRpm)} rpm y ≈ ${n(it.horsepower)} CV a ${n(it.horsepowerRpm)} rpm, " +
                "según la centralita. De fábrica son 300 Nm y 143 CV; solo se acerca si se le pisa a fondo."
        },
        performance?.from80To120?.let { "De 80 a 120 con el pedal a fondo" to "≈ " + n(it, 1) + " s, la mejor del trayecto" },
        "Cómo leerlo".takeIf { !empty }?.let {
            it to "no hay límites de fábrica: vale por comparación con los trayectos anteriores de este mismo coche"
        },
    )

    /**
     * Lo que BMW manda descartar antes de limpiar un filtro cargado (SI B18 01 17), con lo que dice
     * este trayecto. Solo si en el trayecto hubo señal de filtro cargado.
     */
    fun causes(): List<Pair<String, String>> {
        val causes = causes ?: return emptyList()
        if (!causes.filterTrouble) return emptyList()
        return listOf(
            "Por qué" to "BMW: un filtro cargado es un efecto, no una causa. Antes de limpiarlo manda descartar esto. " +
                "Las cifras son de este trayecto; BMW no da límites en el boletín.",
            "Precalentamiento" to when (val glow = causes.glowFaults) {
                null -> "sin dato"
                0.0 -> "sin averías de calentadores presentes"
                else -> "${n(glow)} averías de calentadores presentes: míralas en Averías"
            },
            "Termostato" to (warmUp?.steady?.let { "el motor se estabiliza en ${n(it)} °C" + if (it < WARM_ENOUGH) " (bajo: puede que no cierre)" else "" }
                ?: "sin dato: no llegó a calentarse del todo"),
            "EGR" to (egr?.let { "abre ${signed(it.median)} % sobre lo mandado (mediana)" } ?: "sin dato"),
            "Presión del turbo" to (boost?.let { "${signed(it.median)} kPa real menos pedida (mediana), ${signed(it.low)} en el peor décimo" } ?: "sin dato"),
            "Caudalímetro" to if (causes.mafCorrection == null && causes.airDeviation == null) {
                "se ve con el caso Turbo y aire puesto"
            } else {
                listOfNotNull(
                    causes.mafCorrection?.let { "corrección con carga ${n(it, 3)}" },
                    causes.airDeviation?.let { "desviación de aire ${signed(it)} mg" },
                ).joinToString("; ")
            },
            "Mariposas y vacío" to "la app no lo mide",
        )
    }

    private fun parked(minutes: Double) = when {
        minutes < 120 -> n(minutes) + " min"
        minutes < 48 * 60 -> n(minutes / 60, 1) + " h"
        else -> n(minutes / 1440, 1) + " días"
    }

    private fun n(value: Double, decimals: Int = 0) = String.format(Locale.US, "%.${decimals}f", value).replace('.', ',')

    private fun signed(value: Double) = n(value, 1).let { if (value > 0 && it.any { c -> c in '1'..'9' }) "+$it" else it }

    companion object {

        /** Lo que se puede sacar de [data], o null si no da para nada de esto. */
        fun of(data: TripData): TripHealth? {
            val health = TripHealth(
                warmUp = warmUp(data),
                start = start(data),
                boost = gap(data, BOOST_ASKED, MAP, BOOST_STILL_KPA) { BOOSTING_KPA },
                rail = gap(data, RAIL_ASKED, RAIL, RAIL_STILL_BAR) { 0.0 },
                egr = egr(data),
                idle = idle(data),
                charge = charge(data),
                filter = filter(data),
                coldPushSeconds = coldPush(data),
                performance = performance(data),
                luggingSeconds = lugging(data),
                causes = FilterCauses.of(data),
            )
            return health.takeUnless { it.empty }
        }

        /**
         * Lo mismo que avisa el panel en marcha: por debajo de 60 grados, muchas vueltas o carga
         * alta con la EGR cerrada, que es cuando de verdad se le pide fuerza.
         */
        private fun coldPush(data: TripData): Long? {
            val coolant = data.series[Pids.COOLANT] ?: return null
            val rpm = data.series[Pids.RPM] ?: return null
            val load = data.series[Pids.LOAD]
            val egr = data.series[Moment.EGR_COMMANDED]
            var cold = false
            var pushedMs = 0L
            for (i in 1 until data.size) {
                if (!(rpm[i - 1] >= RUNNING_RPM) || !(coolant[i - 1] < Limits.COLD_OIL)) continue
                cold = true
                val demanding = load != null && load[i - 1] >= Limits.PULL_MIN_LOAD &&
                    (egr == null || egr[i - 1].isNaN() || egr[i - 1] < Limits.EGR_MIN_COMMANDED)
                if (rpm[i - 1] >= Limits.COLD_MAX_RPM || demanding) pushedMs += (data.tMs[i] - data.tMs[i - 1]).coerceIn(0L, MAX_GAP_MS)
            }
            return if (cold) pushedMs / 1000 else null
        }

        /** Los ratos de mas de tres segundos con carga alta a pocas vueltas y el coche andando. */
        private fun lugging(data: TripData): Long? {
            val rpm = data.series[Pids.RPM] ?: return null
            val load = data.series[Pids.LOAD] ?: return null
            val speed = data.series[Pids.SPEED]
            val egr = data.series[Moment.EGR_COMMANDED]
            var totalMs = 0L
            var runMs = 0L
            fun close() {
                if (runMs >= Limits.LUGGING_HOLD_MS) totalMs += runMs
                runMs = 0L
            }
            for (i in 1 until data.size) {
                val turning = rpm[i - 1]
                val idle = turning < Limits.IDLE_MAX_RPM && (speed == null || speed[i - 1].isNaN() || speed[i - 1] < Limits.IDLE_MAX_SPEED)
                val demanding = load[i - 1] >= Limits.PULL_MIN_LOAD &&
                    (egr == null || egr[i - 1].isNaN() || egr[i - 1] < Limits.EGR_MIN_COMMANDED)
                if (turning >= RUNNING_RPM && turning <= Limits.LUGGING_MAX_RPM && demanding && !idle) {
                    runMs += (data.tMs[i] - data.tMs[i - 1]).coerceIn(0L, MAX_GAP_MS)
                } else {
                    close()
                }
            }
            close()
            return totalMs / 1000
        }

        private fun performance(data: TripData): Performance? {
            val torque = data.series[TORQUE] ?: return null
            val rpm = data.series[Pids.RPM] ?: return null
            var bestTorque = -1
            var bestPower = -1
            fun power(i: Int) = torque[i] * rpm[i] / RPM_NM_PER_KW * HP_PER_KW
            for (i in 0 until data.size) {
                if (torque[i].isNaN() || rpm[i].isNaN() || torque[i] <= 0) continue
                if (bestTorque < 0 || torque[i] > torque[bestTorque]) bestTorque = i
                if (bestPower < 0 || power(i) > power(bestPower)) bestPower = i
            }
            if (bestTorque < 0) return null
            return Performance(torque[bestTorque], rpm[bestTorque], power(bestPower), rpm[bestPower], sprint(data))
        }

        /** La mejor subida de 80 a 120 km/h sin soltar el pedal: de media, por encima de [FLOORED]. */
        private fun sprint(data: TripData): Double? {
            val speed = data.series[Pids.SPEED] ?: return null
            val pedal = data.series[Pids.PEDAL] ?: return null
            var best: Double? = null
            var startMs = -1.0
            var pedalSum = 0.0
            var rows = 0
            for (i in 1 until data.size) {
                val before = speed[i - 1]
                val now = speed[i]
                if (before.isNaN() || now.isNaN()) continue
                fun crossing(at: Double) = data.tMs[i - 1] + (at - before) / (now - before) * (data.tMs[i] - data.tMs[i - 1])
                if (before < SPRINT_FROM && now >= SPRINT_FROM) {
                    startMs = crossing(SPRINT_FROM)
                    pedalSum = 0.0
                    rows = 0
                }
                if (startMs < 0) continue
                if (now < SPRINT_FROM) {
                    startMs = -1.0
                    continue
                }
                if (!pedal[i].isNaN()) {
                    pedalSum += pedal[i]
                    rows++
                }
                if (before < SPRINT_TO && now >= SPRINT_TO) {
                    val seconds = (crossing(SPRINT_TO) - startMs) / 1000.0
                    if (rows > 0 && pedalSum / rows >= FLOORED && (best == null || seconds < best)) best = seconds
                    startMs = -1.0
                }
            }
            return best
        }

        /** Las filas con el coche parado desde hace unos segundos, el motor caliente a ralenti y sin regenerar. */
        private fun warmIdleRows(data: TripData): List<Int> {
            val rpm = data.series[Pids.RPM] ?: return emptyList()
            val speed = data.series[Pids.SPEED] ?: return emptyList()
            val coolant = data.series[Pids.COOLANT] ?: return emptyList()
            val regen = data.series[Pids.BMW_REGEN_REQUEST]
            val mode = TripCsv.engineMode(data)
            val rows = ArrayList<Int>()
            var stoppedSince = -1L
            for (i in 0 until data.size) {
                if (speed[i] != 0.0) {
                    stoppedSince = -1L
                    continue
                }
                if (stoppedSince < 0) stoppedSince = data.tMs[i]
                if (data.tMs[i] - stoppedSince < IDLE_SETTLE_MS) continue
                val regenerating = Pids.regenerating(regen?.get(i)?.takeIf { !it.isNaN() }, mode?.get(i))
                if (coolant[i] >= HOT && rpm[i] >= IDLE_FROM && rpm[i] <= IDLE_TO && !regenerating) rows += i
            }
            return rows
        }

        private fun idle(data: TripData): Idle? {
            val rpm = data.series[Pids.RPM] ?: return null
            val read = warmIdleRows(data).map { rpm[it] }.sorted()
            if (read.size < MIN_LONG_SAMPLES) return null
            return Idle(read[read.size / 2], at(read, 0.95) - at(read, 0.05), read.size)
        }

        private fun filter(data: TripData): FilterCheck? {
            // La de BMW en hPa; en los demas coches la del OBD estandar, que va en kPa.
            val pressure = data.series[FILTER_PRESSURE]
                ?: data.series[Pids.OBD_FILTER_PRESSURE]?.let { kpa -> DoubleArray(kpa.size) { kpa[it] * HPA_PER_KPA } }
            val atIdle = if (pressure == null) emptyList() else warmIdleRows(data).map { pressure[it] }.filter { !it.isNaN() }.sorted()
            val marks = Pids.BMW_REGENERATION_ADDRESSES.take(2).map { address ->
                data.series[Pids.bmw(address)]?.lastOrNull { !it.isNaN() }
            }
            val interval = marks[0]?.let { newer -> marks[1]?.let { older -> newer - older } }?.takeIf { it > 0 }
            val check = FilterCheck(
                atIdle.takeIf { it.size >= MIN_SAMPLES }?.let { it[it.size / 2] },
                interval,
                resistance(data),
                turbineRatio(data),
                continuousMinutes = exhaustMinutes(data) { temp, speed -> temp in CONTINUOUS_FROM..CONTINUOUS_TO && speed >= CONTINUOUS_SPEED },
                hotMinutes = exhaustMinutes(data) { temp, _ -> temp > REGEN_DONE },
                longestFastMinutes = longestFast(data),
                faultsStart = data.series[Pids.BMW_FILTER_FAULTS]?.firstOrNull { !it.isNaN() },
                faultsEnd = data.series[Pids.BMW_FILTER_FAULTS]?.lastOrNull { !it.isNaN() },
            )
            return check.takeIf {
                listOf(it.idlePressure, it.lastInterval, it.resistance, it.turbineRatio, it.continuousMinutes, it.longestFastMinutes, it.faultsEnd)
                    .any { value -> value != null }
            }
        }

        /** Minutos en que el escape antes del catalizador y la velocidad cumplen [match]. */
        private fun exhaustMinutes(data: TripData, match: (Double, Double) -> Boolean): Double? {
            val exhaust = data.series[Pids.bmw(BEFORE_CATALYST)] ?: data.series[OBD_CATALYST] ?: return null
            val speed = data.series[Pids.SPEED] ?: return null
            var ms = 0L
            for (i in 1 until data.size) {
                val temp = exhaust[i - 1]
                if (temp.isNaN() || speed[i - 1].isNaN() || !match(temp, speed[i - 1])) continue
                ms += (data.tMs[i] - data.tMs[i - 1]).coerceIn(0L, MAX_GAP_MS)
            }
            return ms / MS_PER_MINUTE
        }

        /** El tramo mas largo a mas de 88 km/h; una bajada de menos de medio minuto no lo corta. */
        private fun longestFast(data: TripData): Double? {
            val speed = data.series[Pids.SPEED] ?: return null
            var best = 0L
            var start = -1L
            var lastFast = -1L
            for (i in 0 until data.size) {
                val t = data.tMs[i]
                if (speed[i] >= FAST_SPEED) {
                    if (start < 0 || t - lastFast > FAST_GAP_MS) start = t
                    lastFast = t
                    best = maxOf(best, t - start)
                }
            }
            return best / MS_PER_MINUTE
        }

        /**
         * La presion diferencial entre el caudal de gas, en las filas con caudal de carretera. Las
         * dos se leen en el turno lento y no a la vez: por eso solo con caudal alto y la mediana.
         */
        private fun resistance(data: TripData): Double? {
            val pressure = data.series[FILTER_PRESSURE] ?: return null
            val flow = data.series[FILTER_FLOW] ?: return null
            val read = (0 until data.size)
                .filter { flow[it] >= MIN_FLOW && !pressure[it].isNaN() }
                .map { pressure[it] / flow[it] * PER_100_FLOW }
                .sorted()
            return read.takeIf { it.size >= MIN_SAMPLES }?.let { it[it.size / 2] }
        }

        /** La presion antes de la turbina entre la del turbo (las dos absolutas), cuando el turbo sopla. */
        private fun turbineRatio(data: TripData): Double? {
            val turbine = data.series[TURBINE_PRESSURE] ?: return null
            val boost = data.series[MAP] ?: return null
            val read = (0 until data.size)
                .filter { boost[it] >= BOOSTING_KPA && !turbine[it].isNaN() }
                .map { turbine[it] / (boost[it] * HPA_PER_KPA) }
                .sorted()
            return read.takeIf { it.size >= MIN_SAMPLES }?.let { it[it.size / 2] }
        }

        /** Pasado minuto y medio desde que el motor gira: antes, la tension leida puede ser todavia la de parado. */
        private fun charge(data: TripData): Charge? {
            val rpm = data.series[Pids.RPM] ?: return null
            val volts = data.series[MODULE_VOLTS] ?: return null
            val read = ArrayList<Double>()
            var since = -1L
            for (i in 0 until data.size) {
                if (!(rpm[i] >= RUNNING_RPM)) {
                    since = -1L
                    continue
                }
                if (since < 0) since = data.tMs[i]
                if (data.tMs[i] - since >= CHARGE_AFTER_MS && !volts[i].isNaN()) read += volts[i]
            }
            if (read.size < MIN_LONG_SAMPLES) return null
            read.sort()
            return Charge(read[read.size / 2], at(read, 0.1))
        }

        /** El error de la EGR que da el coche, solo mientras se le manda abrir y el mando lleva un rato quieto. */
        private fun egr(data: TripData): Gap? {
            val rpm = data.series[Pids.RPM] ?: return null
            val sent = data.series[EGR_SENT] ?: return null
            val error = data.series[EGR_ERROR] ?: return null
            val read = ArrayList<Double>()
            for (last in WINDOW - 1 until data.size) {
                if (!(rpm[last] >= RUNNING_RPM) || error[last].isNaN()) continue
                var low = Double.MAX_VALUE
                var high = -Double.MAX_VALUE
                var whole = true
                for (i in last - WINDOW + 1..last) {
                    if (sent[i].isNaN()) {
                        whole = false
                        break
                    }
                    low = minOf(low, sent[i])
                    high = maxOf(high, sent[i])
                }
                if (whole && low >= EGR_OPEN && high - low < EGR_STILL) read += error[last]
            }
            if (read.size < MIN_SAMPLES) return null
            read.sort()
            return Gap(read[read.size / 2], at(read, 0.1), read.size)
        }

        private fun at(sorted: List<Double>, share: Double) = sorted[(sorted.size * share).toInt().coerceAtMost(sorted.size - 1)]

        private fun warmUp(data: TripData): WarmUp? {
            val coolant = data.series[Pids.COOLANT] ?: return null
            val rpm = data.series[Pids.RPM] ?: return null
            val running = (0 until data.size).firstOrNull { rpm[it] >= RUNNING_RPM } ?: return null
            val start = (running until data.size).firstOrNull { !coolant[it].isNaN() }?.let { coolant[it] } ?: return null
            val hot = if (start >= COLD_BELOW) null else (running until data.size).firstOrNull { coolant[it] >= HOT }
            val late = (running until data.size)
                .filter { data.tMs[it] - data.tMs[running] >= STEADY_AFTER_MS && !coolant[it].isNaN() }
                .map { coolant[it] }
                .sorted()
            val enough = late.isNotEmpty() && data.tMs.last() - data.tMs[running] >= STEADY_AFTER_MS + STEADY_MIN_MS
            val outside = data.series[OUTSIDE]?.firstOrNull { !it.isNaN() }
            if (hot == null && !enough) return null
            return WarmUp(
                startCoolant = start,
                outside = outside,
                minutesTo80 = hot?.let { (data.tMs[it] - data.tMs[running]) / 60_000.0 },
                steady = if (enough) late[late.size / 2] else null,
            )
        }

        private fun start(data: TripData): EngineStart? {
            val rpm = data.series[Pids.RPM] ?: return null
            val turning = (0 until data.size).firstOrNull { rpm[it] > 0.0 } ?: return null
            // Sin una lectura con el motor parado justo antes, el arranque no quedo grabado.
            val stopped = (turning - 1).takeIf { it >= 0 && rpm[it] == 0.0 } ?: return null
            val idling = (turning until data.size).firstOrNull { rpm[it] >= IDLE_RPM } ?: return null
            val seconds = (data.tMs[idling] - data.tMs[stopped]) / 1000.0
            if (seconds > MAX_START_SECONDS) return null
            val volts = (data.series[MODULE_VOLTS] ?: data.series[Pids.ADAPTER_VOLTAGE])?.get(stopped)?.takeIf { !it.isNaN() }
            val coolant = data.series[Pids.COOLANT]?.get(stopped)?.takeIf { !it.isNaN() }
            // Lo que diga la centralita en los minutos siguientes al arranque, por si con el motor
            // parado todavia daba el del arranque anterior.
            val parked = data.series[Pids.BMW_PARKED_MINUTES]?.let { series ->
                (0 until data.size).lastOrNull { !series[it].isNaN() && data.tMs[it] - data.tMs[idling] <= PARKED_WITHIN_MS }?.let { series[it] }
            }
            return EngineStart(seconds, volts, coolant, parked)
        }

        /**
         * La diferencia entre lo real y lo pedido, solo cuando las dos medidas llevan un rato
         * quietas (menos de [still] de vaiven) y lo pedido pasa de [from]: en un aceleron la
         * pedida, que se lee menos seguido, va por detras y la diferencia no dice nada.
         */
        private fun gap(data: TripData, askedId: Int, realId: Int, still: Double, from: () -> Double): Gap? {
            val asked = data.series[askedId] ?: return null
            val real = data.series[realId] ?: return null
            val rpm = data.series[Pids.RPM]
            val gaps = ArrayList<Double>()
            for (last in WINDOW - 1 until data.size) {
                if (rpm != null && !(rpm[last] >= RUNNING_RPM)) continue
                var askedMin = Double.MAX_VALUE
                var askedMax = -Double.MAX_VALUE
                var realMin = Double.MAX_VALUE
                var realMax = -Double.MAX_VALUE
                var whole = true
                for (i in last - WINDOW + 1..last) {
                    if (asked[i].isNaN() || real[i].isNaN()) {
                        whole = false
                        break
                    }
                    askedMin = minOf(askedMin, asked[i])
                    askedMax = maxOf(askedMax, asked[i])
                    realMin = minOf(realMin, real[i])
                    realMax = maxOf(realMax, real[i])
                }
                if (whole && askedMin > from() && askedMax - askedMin < still && realMax - realMin < still) {
                    gaps += real[last] - asked[last]
                }
            }
            if (gaps.size < MIN_SAMPLES) return null
            gaps.sort()
            return Gap(gaps[gaps.size / 2], gaps[gaps.size / 10], gaps.size)
        }

        private const val OUTSIDE = 0x46
        private const val HPA_PER_KPA = 10.0
        private val TORQUE = Pids.bmw(0x07D1)
        private const val RPM_NM_PER_KW = 9549.0
        private const val HP_PER_KW = 1.36
        private const val SPRINT_FROM = 80.0
        private const val SPRINT_TO = 120.0
        private const val FLOORED = 80.0
        private const val MAX_GAP_MS = 5_000L
        private const val MODULE_VOLTS = 0x42
        private const val MAP = 0x0B
        private val BOOST_ASKED = Pids.bmw(0x01F4)
        private const val RUNNING_RPM = 400.0
        private const val IDLE_RPM = 800.0
        private const val MAX_START_SECONDS = 15.0
        private const val PARKED_WITHIN_MS = 180_000L
        private const val COLD_BELOW = 60.0
        private const val HOT = 80.0
        private const val STEADY_AFTER_MS = 15 * 60_000L
        private const val STEADY_MIN_MS = 5 * 60_000L
        private const val WINDOW = 5
        private const val BOOSTING_KPA = 130.0
        private const val BOOST_STILL_KPA = 5.0
        private const val RAIL_STILL_BAR = 20.0
        private const val MIN_SAMPLES = 30
        private const val MIN_LONG_SAMPLES = 60
        private const val RAIL = 0x23
        private val RAIL_ASKED = Pids.bmw(0x0641)
        private const val EGR_SENT = 0x2C
        private const val EGR_ERROR = 0x2D
        private const val EGR_OPEN = 5.0
        private const val EGR_STILL = 5.0
        private val FILTER_PRESSURE = Pids.bmw(0x0424)
        private val FILTER_FLOW = Pids.bmw(0x0C83)
        private const val WARM_ENOUGH = 80.0
        private const val BEFORE_CATALYST = 0x041E
        private const val OBD_CATALYST = 0x3C
        private const val CONTINUOUS_FROM = 280.0
        private const val CONTINUOUS_TO = 350.0
        private const val CONTINUOUS_SPEED = 60.0
        private const val REGEN_DONE = 500.0
        private const val FAST_SPEED = 88.0
        private const val FAST_GAP_MS = 30_000L
        private const val MS_PER_MINUTE = 60_000.0
        private val TURBINE_PRESSURE = Pids.bmw(0x03E0)
        private const val MIN_FLOW = 150.0
        private const val PER_100_FLOW = 100.0
        private const val IDLE_SETTLE_MS = 5_000L
        private const val IDLE_FROM = 600.0
        private const val IDLE_TO = 1100.0
        private const val CHARGE_AFTER_MS = 90_000L
    }
}
