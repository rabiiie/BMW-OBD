package com.rabie.bmwobd.vehicle

import com.rabie.bmwobd.obd.PidDef
import com.rabie.bmwobd.obd.Pids
import kotlin.math.abs

/**
 * Lo que la app sabe de un motor concreto: sus medidas propias, las de los casos, su memoria de
 * averias y sus valores de referencia. Un coche lo lleva guardado desde la primera vez que su
 * centralita se reconoce; los demas son genericos y solo se les lee el OBD normal.
 */
enum class EngineProfile(val key: String, val title: String) {
    GENERIC("generico", "Genérico"),
    BMW_N47("bmw_n47", "BMW N47 (DDE 7)"),
    ;

    /** Las medidas propias que se leen siempre. */
    val measures: List<PidDef> get() = if (this == BMW_N47) Pids.bmwDiesel else emptyList()

    /** Las que solo se piden con un caso puesto o para el panel. */
    val caseMeasures: List<PidDef> get() = if (this == BMW_N47) Pids.bmwCases else emptyList()

    /** Si la centralita tiene memoria de averias propia y se le puede hacer el barrido de direcciones. */
    val ownDiagnostics: Boolean get() = this == BMW_N47

    val reference: EngineReference? get() = if (this == BMW_N47) EngineReference.N47 else null

    companion object {

        fun of(vehicle: Vehicle): EngineProfile = entries.firstOrNull { it.key == vehicle.profile } ?: GENERIC

        /**
         * El perfil que merece la pena comprobar en un coche que todavia no tiene ninguno. Solo
         * decide a quien se le pregunta: que lo sea lo dice [N47Fingerprint].
         */
        fun candidate(vehicle: Vehicle): EngineProfile? = BMW_N47.takeIf { Pids.hasBmwMeasures(vehicle.make, vehicle.diesel) }
    }
}

/** Una medida propia que tambien sale por el OBD normal; [scale] pasa la propia a la unidad del OBD. */
class ProfileCheck(val def: PidDef, val obd: Int, val tolerance: Double, val scale: Double = 1.0)

/**
 * Como se reconoce una centralita del N47: se le piden por sus direcciones cuatro medidas que el
 * coche da tambien por el OBD normal. Si con las formulas de su tabla salen los mismos valores,
 * la tabla es la suya. Otra centralita de BMW contestaria otra cosa en esas direcciones, o nada.
 */
object N47Fingerprint {

    val checks: List<ProfileCheck> = listOf(
        ProfileCheck(Pids.bmwChecks[0], Pids.COOLANT, tolerance = 4.0),
        ProfileCheck(Pids.bmwChecks[1], Pids.RPM, tolerance = 300.0),
        ProfileCheck(Pids.bmwChecks[2], MODULE_VOLTAGE, tolerance = 0.4),
        ProfileCheck(Pids.bmwChecks[3], Pids.BAROMETRIC, tolerance = 3.0, scale = 0.1),
    )

    /**
     * Hacen falta tres coincidencias de las cuatro. Una sola que desentone no lo tumba: las dos
     * lecturas no son del mismo instante y una puede caer en un aceleron. Tres valores iguales
     * por casualidad en otra centralita no son creibles.
     */
    fun fits(own: Map<Int, Double>, obd: Map<Int, Double>): Boolean =
        checks.count { check ->
            val mine = own[check.def.id]
            val standard = obd[check.obd]
            mine != null && standard != null && abs(mine * check.scale - standard) <= check.tolerance
        } >= MIN_MATCHES

    private const val MODULE_VOLTAGE = 0x42
    private const val MIN_MATCHES = 3
}
