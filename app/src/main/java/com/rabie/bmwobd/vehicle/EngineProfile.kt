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
    BMW_B47("bmw_b47", "BMW B47 (DDE 7.01 / 7.41)"),
    ;

    /** Las medidas propias que se leen siempre. */
    val measures: List<PidDef>
        get() = when (this) {
            BMW_N47 -> Pids.bmwDiesel
            BMW_B47 -> Pids.b47
            GENERIC -> emptyList()
        }

    /** Si sus medidas propias van por UDS (servicio 22, la pasarela de BMW) y no por el 2C del N47. */
    val uds: Boolean get() = this == BMW_B47

    /** Como se reconoce su centralita; null en el generico. */
    val fingerprint: Fingerprint?
        get() = when (this) {
            BMW_N47 -> N47Fingerprint
            BMW_B47 -> B47Fingerprint
            GENERIC -> null
        }

    /** Las que solo se piden con un caso puesto o para el panel. */
    val caseMeasures: List<PidDef> get() = if (this == BMW_N47) Pids.bmwCases else emptyList()

    /** Si la centralita tiene memoria de averias propia y se le puede hacer el barrido de direcciones. */
    val ownDiagnostics: Boolean get() = this == BMW_N47

    val reference: EngineReference? get() = if (this == BMW_N47) EngineReference.N47 else null

    companion object {

        fun of(vehicle: Vehicle): EngineProfile = entries.firstOrNull { it.key == vehicle.profile } ?: GENERIC

        /**
         * Los perfiles que merece la pena comprobar en un coche que todavia no tiene ninguno, en
         * orden. Solo decide a quien se le pregunta: que lo sea lo dice su [fingerprint].
         */
        fun candidates(vehicle: Vehicle): List<EngineProfile> =
            if (Pids.hasBmwMeasures(vehicle.make, vehicle.diesel)) listOf(BMW_N47, BMW_B47) else emptyList()
    }
}

/** Una medida propia que tambien sale por el OBD normal; [scale] pasa la propia a la unidad del OBD. */
class ProfileCheck(val def: PidDef, val obd: Int, val tolerance: Double, val scale: Double = 1.0)

/**
 * Como se reconoce una centralita: se le piden por sus direcciones medidas que el coche da tambien
 * por el OBD normal. Si con las formulas de su tabla salen los mismos valores, la tabla es la suya.
 * Otra centralita contestaria otra cosa en esas direcciones, o nada. Hacen falta [minMatches]
 * coincidencias: una sola que desentone no lo tumba, porque las dos lecturas no son del mismo
 * instante y una puede caer en un aceleron.
 */
open class Fingerprint(val checks: List<ProfileCheck>, private val minMatches: Int) {

    fun fits(own: Map<Int, Double>, obd: Map<Int, Double>): Boolean =
        checks.count { check ->
            val mine = own[check.def.id]
            val standard = obd[check.obd]
            mine != null && standard != null && abs(mine * check.scale - standard) <= check.tolerance
        } >= minMatches
}

private const val MODULE_VOLTAGE = 0x42

/** El N47: refrigerante, revoluciones, tension y presion ambiente; tres de cuatro. */
object N47Fingerprint : Fingerprint(
    listOf(
        ProfileCheck(Pids.bmwChecks[0], Pids.COOLANT, tolerance = 4.0),
        ProfileCheck(Pids.bmwChecks[1], Pids.RPM, tolerance = 300.0),
        ProfileCheck(Pids.bmwChecks[2], MODULE_VOLTAGE, tolerance = 0.4),
        ProfileCheck(Pids.bmwChecks[3], Pids.BAROMETRIC, tolerance = 3.0, scale = 0.1),
    ),
    minMatches = 3,
)

/**
 * El B47: refrigerante, revoluciones y tension; dos de tres. Sus identificadores son de UDS: una
 * centralita que no sea de esta familia no contesta a ninguno, asi que dos valores iguales a los
 * del OBD ya bastan.
 */
object B47Fingerprint : Fingerprint(
    listOf(
        ProfileCheck(Pids.b47Checks[0], Pids.COOLANT, tolerance = 4.0),
        ProfileCheck(Pids.b47Checks[1], Pids.RPM, tolerance = 300.0),
        ProfileCheck(Pids.b47Checks[2], MODULE_VOLTAGE, tolerance = 0.4),
    ),
    minMatches = 2,
)
