package com.rabie.bmwobd.obd

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

data class EngineState(
    val rpm: Double,
    val speed: Double,
    val load: Double,
    val mapKpa: Double,
    val baroKpa: Double,
    val coolant: Double,
    val oil: Double,
    val intakeTemp: Double,
    val maf: Double,
    val railBar: Double,
    val egr: Double,
    val egrError: Double,
    val voltage: Double,
    val ambient: Double,
    val fuelRate: Double,
    val fuelLevel: Double,
)

/**
 * Un 118d inventado que arranca en frio y repite un recorrido de ciudad y autovia. Los numeros
 * son verosimiles, no medidos: sirve para trabajar la app sin estar en el coche.
 */
object SimulatedEngine {

    private const val LOOP_SECONDS = 150.0
    private const val AMBIENT = 18.0
    private const val BARO = 99.0
    private const val IDLE_RPM = 800.0
    private const val COLD_IDLE_RPM = 950.0

    // Segundo del recorrido y velocidad en km/h en ese instante.
    private val route = listOf(
        0.0 to 0.0, 12.0 to 0.0, 20.0 to 30.0, 30.0 to 50.0, 50.0 to 50.0, 62.0 to 90.0,
        75.0 to 120.0, 105.0 to 120.0, 112.0 to 80.0, 125.0 to 80.0, 140.0 to 0.0, 150.0 to 0.0,
    )

    // Revoluciones por cada km/h en cada marcha, de primera a sexta.
    private val gears = listOf(115.0, 62.0, 40.0, 30.0, 24.5, 20.5)

    fun at(seconds: Double): EngineState {
        val t = seconds % LOOP_SECONDS
        val index = route.indexOfLast { it.first <= t }.coerceIn(0, route.size - 2)
        val (t0, v0) = route[index]
        val (t1, v1) = route[index + 1]
        val accel = (v1 - v0) / (t1 - t0)
        val speed = v0 + accel * (t - t0)

        val coolant = AMBIENT + (90.0 - AMBIENT) * (1 - exp(-seconds / 90.0)) + 1.5 * sin(seconds / 11.0)
        val oil = AMBIENT + (98.0 - AMBIENT) * (1 - exp(-seconds / 180.0))

        val rpm = if (speed < 3.0) {
            if (coolant < 50.0) COLD_IDLE_RPM else IDLE_RPM
        } else {
            val limit = if (accel > 1.0) 2900.0 else 1900.0
            val ratio = gears.firstOrNull { speed * it <= limit } ?: gears.last()
            max(IDLE_RPM, speed * ratio)
        }

        val overrun = accel < -1.0
        val load = if (overrun) 0.0 else (18.0 + speed * 0.22 + max(accel, 0.0) * 22.0).coerceIn(0.0, 100.0)
        val spool = ((rpm - 900.0) / 900.0).coerceIn(0.0, 1.0)
        val mapKpa = BARO + max(load - 20.0, 0.0) / 80.0 * 135.0 * spool
        val egr = if (load < 50.0 && coolant > 50.0 && !overrun) 45.0 * (1 - load / 50.0) else 0.0

        return EngineState(
            rpm = rpm,
            speed = speed,
            load = load,
            mapKpa = mapKpa,
            baroKpa = BARO,
            coolant = coolant,
            oil = oil,
            intakeTemp = AMBIENT + 8.0 + (mapKpa - BARO) / 100.0 * 18.0,
            maf = rpm * mapKpa / 6_200.0 * (1 - egr * 0.004),
            railBar = if (overrun) 250.0 else 280.0 + load * 13.0,
            egr = egr,
            egrError = 3.0 * sin(seconds / 5.0),
            voltage = 14.1 + 0.15 * sin(seconds / 7.0),
            ambient = AMBIENT,
            fuelRate = if (overrun) 0.0 else 0.55 + load / 100.0 * rpm / 1000.0 * 4.4,
            fuelLevel = 62.0,
        )
    }
}
