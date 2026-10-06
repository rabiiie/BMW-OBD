package com.rabie.bmwobd.vehicle

/**
 * Valores de referencia de un motor concreto, para juzgar las lecturas con algo que no sea el
 * propio coche. Solo se aplican al coche que encaja con ese motor; los demas se quedan con los
 * umbrales genericos.
 */
data class EngineReference(
    val name: String,
    val source: String,
    val idleRpm: Double,
    val idleLoad: ClosedFloatingPointRange<Double>,
    val idleRailBar: ClosedFloatingPointRange<Double>,
    val idleAirGramsPerSecond: ClosedFloatingPointRange<Double>,
    val fullLoadMapKpa: ClosedFloatingPointRange<Double>,
    val fullLoadAirGramsPerSecond: ClosedFloatingPointRange<Double>,
    val maxRailBar: Double,
) {
    companion object {

        /**
         * BMW N47 de dos litros. Las cifras son las de la tabla que aporto el dueño del coche; no
         * estan contrastadas con documentacion de BMW.
         */
        val N47 = EngineReference(
            name = "BMW N47 2.0d",
            source = "tabla de referencia del usuario",
            idleRpm = 750.0,
            idleLoad = 15.0..25.0,
            idleRailBar = 300.0..350.0,
            idleAirGramsPerSecond = 7.5..9.5,
            fullLoadMapKpa = 230.0..250.0,
            fullLoadAirGramsPerSecond = 100.0..115.0,
            maxRailBar = 1600.0,
        )

        /** La referencia que corresponde a este coche, o null si su motor no tiene ninguna. */
        fun of(vehicle: Vehicle): EngineReference? {
            val liters = vehicle.displacementLiters ?: return null
            val names = listOfNotNull(vehicle.make, vehicle.name).joinToString(" ")
            val isBmw = BMW_HINTS.any { names.contains(it, ignoreCase = true) }
            return if (isBmw && vehicle.diesel && liters in 1.9..2.1) N47 else null
        }

        private val BMW_HINTS = listOf("BMW", "118d", "120d", "N47")
    }
}
