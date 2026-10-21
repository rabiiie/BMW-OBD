package com.rabie.bmwobd.cases

import com.rabie.bmwobd.advice.Moment
import com.rabie.bmwobd.health.HealthCard
import com.rabie.bmwobd.health.HealthLevel
import com.rabie.bmwobd.health.HealthSystem
import com.rabie.bmwobd.obd.BmwFault
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
        // 03EC, la interrupcion de la regeneracion, va con las vigiladas: dice por que se pausa (2 con
        // la temperatura alcanzada, 4 y 6 al pisar fuerte o soltar, 0 calentando). Las post-inyecciones
        // (04B5, 04B6 y la cantidad de la segunda, 0840) dicen cuanto intenta calentar. Con catorce
        // vigiladas y una por vuelta, cada una se repite cada ~9 s.
        watch = listOf(0x0404, 0x05AA, 0x03EC, 0x03EA, 0x03ED, 0x03F1, 0x0424, 0x0C83, 0x0420, 0x041E, 0x0434, 0x04B5, 0x04B6, 0x0840).map(Pids::bmw),
        also = listOf(0x0405, 0x0408, 0x03EE, 0x03DE, 0x03E0, 0x0426, 0x0432, 0x043B, 0x0C81).map(Pids::bmw),
    ),
    INJECTORS(
        "Inyectores",
        watch = Pids.BMW_CYLINDER_CORRECTIONS + listOf(0x14D3, 0x0500, 0x04B1).map(Pids::bmw),
        also = Pids.BMW_INJECTOR_TIMES.flatten() + listOf(0x145E, 0x145F, 0x1460, 0x1964, 0x04B0).map(Pids::bmw),
    ),
    TURBO(
        "Turbo y aire",
        watch = listOf(0x01F4, 0x01F9, 0x0BEB, 0x0C86, 0x0709, 0x07A2, 0x0BB4, 0x07AD, 0x03E0).map(Pids::bmw),
        also = listOf(0x079E, 0x0793, 0x0B1F, 0x0C87, 0x0777, 0x0C1C, 0x0BEA, 0x01F7, 0x0712, 0x0713, 0x0776, 0x0C89).map(Pids::bmw),
    ),
    COLD_START(
        "Arranque en frío",
        // Un arranque dura un par de segundos y cabe una medida por vuelta: el desfase de la
        // distribucion va una vuelta si y otra no, y la presion de gasoleo de baja una de cada
        // cuatro. Lo que cambia despacio, como los calentadores, va con lo lento.
        watch = listOf(
            Pids.BMW_TIMING_OFFSET_ADDRESS, 0x02DC, Pids.BMW_TIMING_OFFSET_ADDRESS, 0x0674,
            Pids.BMW_TIMING_OFFSET_ADDRESS, 0x02DC, Pids.BMW_TIMING_OFFSET_ADDRESS, 0x069A,
        ).map(Pids::bmw),
        also = Pids.BMW_GLOW_FAULTS.map { it.first } +
            listOf(
                0x1609, 0x160B, 0x012C, 0x15FE, 0x15FF, 0x01B1, 0x01B8, 0x01B9, 0x0327, 0x0324, 0x05DF, 0x05DE, 0x0644, 0x0D8E,
            ).map(Pids::bmw),
    ),
    DRIVING(
        "Conducción",
        watch = listOf(0x0EA6, 0x07D1, 0x0DE3, 0x0DE2, 0x0500).map(Pids::bmw),
        also = listOf(0x0578, 0x0579, 0x1036, 0x0DDF, 0x106B, 0x0F0A, 0x1068, 0x05D3).map(Pids::bmw),
    ),
    POWER(
        "Potencia",
        // Solo el par en la lista de cada vuelta: con mas medidas cada una tardaria varias vueltas
        // en repetirse, y para la curva de potencia hace falta el par al mismo tiempo que las vueltas.
        watch = listOf(0x07D1).map(Pids::bmw),
        also = listOf(0x0500, 0x0578, 0x1036).map(Pids::bmw),
    ),
    ELECTRIC(
        "Eléctrico",
        watch = listOf(0x01B2, 0x01B9, 0x01AF, 0x0186, 0x01BC).map(Pids::bmw),
        also = listOf(0x01B1, 0x01B8, 0x0E43, 0x10D5, 0x10CE, 0x012C).map(Pids::bmw),
    ),
}

/** Lo que pasa ahora en lo que mira un caso, dicho sin jerga. */
object CaseCards {

    private val FILTER_DISTANCE = Pids.bmw(0x03EB)
    private val FILTER_MINUTES = Pids.bmw(0x0408)
    private val FILTER_FUEL = Pids.bmw(0x03E8)
    private val FILTER_PRESSURE = Pids.bmw(0x0424)
    private val FILTER_RESISTANCE = Pids.bmw(0x03F1)
    // PFltRgn_stLck, "Status - Regenerationssperre": la que lee STATUS_REGENERATION_CSF de ISTA
    // para decir si la regeneracion esta bloqueada. BMW solo da el significado del 0.
    private val REGEN_LOCK = Pids.bmw(0x03EE)
    private val BEFORE_CATALYST = Pids.bmw(0x041E)
    private val BEFORE_FILTER = Pids.bmw(0x0434)
    private val GENERAL_CORRECTION = Pids.bmw(0x14D3)
    private val BOOST_ASKED = Pids.bmw(0x01F4)
    private val BOOST_DEVIATION = Pids.bmw(0x01F9)
    private val TURBO_ACTUATOR = Pids.bmw(0x0BEB)
    private val TURBO_SPEED = Pids.bmw(0x0C86)
    private val AIR_PER_CYLINDER = Pids.bmw(0x0709)
    private val AIR_DEVIATION = Pids.bmw(0x07A2)
    private val EGR_POSITION = Pids.bmw(0x0BB4)
    private val EGR_RATE = Pids.bmw(0x07AD)
    private val AFTER_COMPRESSOR = Pids.bmw(0x0C87)
    private val AFTER_INTERCOOLER = Pids.bmw(0x076F)
    private val STARTER = Pids.bmw(0x069A)
    private val FUEL_LOW_PRESSURE = Pids.bmw(0x02DC)
    private val PUMP_CURRENT = Pids.bmw(0x0327)
    private val RAIL_REAL = Pids.bmw(0x0674)
    private val RAIL_ASKED = Pids.bmw(0x0641)
    private val GLOW_POWER = Pids.bmw(0x1609)
    private val GLOW_TEMP = Pids.bmw(0x160B)
    private val BATTERY_VOLTS = Pids.bmw(0x012C)
    private val BATTERY_MARGIN = Pids.bmw(0x01B1)
    private val GEAR = Pids.bmw(0x0EA6)
    private val TORQUE = Pids.bmw(0x07D1)
    private val CRUISE_STATE = Pids.bmw(0x0DE3)
    private val CRUISE_SPEED = Pids.bmw(0x0DE2)
    private const val CRUISE_WAITING = 1
    private const val CRUISE_DRIVING = 2
    private const val RPM_NM_PER_KW = 9549.0
    private const val HP_PER_KW = 1.36
    private val BATTERY_CURRENT = Pids.bmw(0x01B2)
    private val BATTERY_TERMINAL = Pids.bmw(0x01B9)
    private val BATTERY_TEMP = Pids.bmw(0x01B8)
    private val ALTERNATOR_ASKED = Pids.bmw(0x01AF)
    private val ALTERNATOR_LOAD = Pids.bmw(0x0186)
    private val ALTERNATOR_TEMP = Pids.bmw(0x0E43)
    private val RECOVERED_AH = Pids.bmw(0x10D5)
    private val PARKED_MINUTES = Pids.bmw(0x10CE)
    private const val RUNNING_RPM = 400.0
    private const val LOW_RUNNING_VOLTS = 12.3
    private const val STEADY_AMPS = 1.0
    private const val SENSOR_OPEN_BELOW = -40.0
    private const val GLOWING_FROM_W = 20.0
    private const val WARM_ENGINE = 60.0
    private const val SEA_LEVEL_KPA = 100.0
    private const val BOOSTING_OVER_AMBIENT_KPA = 20.0
    private const val CORRECTION_WARN = 2.0
    private const val CORRECTION_BAD = 4.0

    fun card(case: Case, values: Map<Int, Double>): HealthCard? = when (case) {
        Case.NORMAL -> null
        Case.FILTER -> filter(values)
        Case.INJECTORS -> injectors(values)
        Case.TURBO -> turbo(values)
        Case.COLD_START -> coldStart(values)
        Case.DRIVING -> driving(values)
        Case.ELECTRIC -> electric(values)
        Case.POWER -> power(values)
    }

    /** Par y potencia de ahora, con el par que calcula la centralita; para la curva, una aceleracion a fondo en 3ª. */
    private fun power(values: Map<Int, Double>): HealthCard {
        val torque = values[TORQUE]
        val rpm = values[Pids.RPM]
        if (torque == null || rpm == null) {
            return HealthCard(HealthSystem.DRIVING, HealthLevel.UNKNOWN, "Esperando el par y las vueltas.", emptyList())
        }
        val horsepower = if (torque > 0) torque * rpm / RPM_NM_PER_KW * HP_PER_KW else 0.0
        return HealthCard(
            HealthSystem.DRIVING, HealthLevel.OK,
            "${n(torque)} Nm a ${thousands(rpm)} rpm: unos ${n(horsepower)} CV ahora.",
            listOfNotNull(values[Pids.BMW_INJECTION]?.let { "Inyecta ${n(it, 1)} mg por embolada." }),
            "Para la curva: en 3ª, a fondo desde 1500 hasta 4500 rpm, en un sitio donde se pueda. El par es el que calcula la centralita.",
        )
    }

    /**
     * La bateria y el alternador. Este coche regula la carga: carga mas en las retenciones y
     * deja descargar algo la bateria acelerando, asi que una corriente negativa en marcha no es
     * una averia. Solo avisa una tension baja con el motor girando.
     */
    private fun electric(values: Map<Int, Double>): HealthCard {
        val current = values[BATTERY_CURRENT]
        val volts = values[BATTERY_TERMINAL] ?: values[BATTERY_VOLTS]
        val running = (values[Pids.RPM] ?: 0.0) > RUNNING_RPM
        val facts = listOfNotNull(
            volts?.let { v -> "Tensión: ${n(v, 1)} V" + (values[ALTERNATOR_ASKED]?.let { "; el coche le pide ${n(it, 1)} V al alternador." } ?: ".") },
            values[ALTERNATOR_LOAD]?.let { load ->
                "Alternador al ${n(load)} % de carga" + (values[ALTERNATOR_TEMP]?.let { ", a ${n(it)} °C." } ?: ".")
            },
            values[BATTERY_MARGIN]?.let { margin ->
                "Margen de la batería para arrancar: ${n(margin)} (más alto, mejor)" +
                    (values[BATTERY_TEMP]?.takeIf { it > SENSOR_OPEN_BELOW }?.let { "; batería a ${n(it)} °C." } ?: ".")
            },
            values[PARKED_MINUTES]?.let { "Antes de este arranque estuvo aparcado ${duration(it)}." },
            values[RECOVERED_AH]?.let { "Energía recuperada en retenciones: ${n(it, 1)} Ah." },
        )
        return when {
            current == null -> HealthCard(HealthSystem.ELECTRIC, HealthLevel.UNKNOWN, "Esperando los datos de la batería.", facts)
            running && volts != null && volts < LOW_RUNNING_VOLTS -> HealthCard(
                HealthSystem.ELECTRIC, HealthLevel.WATCH,
                "Solo ${n(volts, 1)} V con el motor en marcha: el alternador no está cargando.",
                facts,
                "Si se mantiene varios minutos, mirar la correa del alternador y sus conexiones antes de que la batería se agote.",
            )
            current >= STEADY_AMPS -> HealthCard(HealthSystem.ELECTRIC, HealthLevel.OK, "La batería está cargando: entran ${n(current, 1)} A.", facts)
            current <= -STEADY_AMPS -> HealthCard(
                HealthSystem.ELECTRIC, HealthLevel.OK,
                "La batería está dando ${n(-current, 1)} A." + if (running) " En marcha es normal a ratos: carga en las retenciones." else "",
                facts,
            )
            else -> HealthCard(HealthSystem.ELECTRIC, HealthLevel.OK, "Batería en equilibrio: ni carga ni descarga.", facts)
        }
    }

    private fun duration(minutes: Double): String =
        if (minutes < 90) "${n(minutes)} min" else "${n(minutes / 60, 1)} h"

    /**
     * La marcha, la fuerza que da el motor y el control de crucero. Los estados del crucero son
     * los que se vieron en el coche: 1 con una velocidad guardada y el pie en el pedal, 2 llevandolo.
     */
    private fun driving(values: Map<Int, Double>): HealthCard {
        val gear = values[GEAR]
        val speed = values[Pids.SPEED]
        val rpm = values[Pids.RPM]
        val torque = values[TORQUE]
        val set = values[CRUISE_SPEED]
        val facts = listOfNotNull(
            torque?.let { nm ->
                val horsepower = rpm?.takeIf { nm > 0 }?.let { nm * it / RPM_NM_PER_KW * HP_PER_KW }
                if (nm <= 0) "El motor no empuja: retiene con ${n(-nm)} Nm." else "El motor da ${n(nm)} Nm" +
                    (horsepower?.let { ", unos ${n(it)} CV ahora." } ?: ".")
            },
            Pids.litersPer100Km(values)?.let { "Consumo: ${n(it, 1)} L/100." },
            when (values[CRUISE_STATE]?.toInt()) {
                null -> null
                CRUISE_DRIVING -> "Control de crucero llevando el coche" + (set?.let { " a ${n(it)} km/h." } ?: ".")
                CRUISE_WAITING -> "Control de crucero en espera" + (set?.takeIf { it > 0 }?.let { "; velocidad guardada ${n(it)} km/h." } ?: ".")
                else -> "Control de crucero apagado."
            },
        )
        val moving = listOfNotNull(speed?.let { "${n(it)} km/h" }, rpm?.let { "${thousands(it)} rpm" }).joinToString(", ")
        val headline = when {
            gear == null -> return HealthCard(HealthSystem.DRIVING, HealthLevel.UNKNOWN, "Esperando los datos de conducción.", facts)
            gear < 1 -> "Punto muerto" + if (moving.isEmpty()) "." else ", $moving."
            else -> "${n(gear)}ª" + if (moving.isEmpty()) "." else " a $moving."
        }
        return HealthCard(HealthSystem.DRIVING, HealthLevel.OK, headline, facts)
    }

    /**
     * Los calentadores ayudan a encender el gasoleo con el motor frio y durante las
     * regeneraciones. Su mando dice cuanto gastan y si ve mal alguno.
     */
    private fun coldStart(values: Map<Int, Double>): HealthCard {
        val power = values[GLOW_POWER]
        val coolant = values[Pids.COOLANT]
        val faults = Pids.BMW_GLOW_FAULTS.mapNotNull { (id, text) -> values[id]?.takeIf { it > 0 }?.let { "$text (código ${n(it)})" } }
        val glowing = power != null && power >= GLOWING_FROM_W
        val facts = listOfNotNull(
            if (glowing) {
                "Gastan ${n(power!!)} W" + (values[GLOW_TEMP]?.takeIf { it > 100 }?.let { " y están a unos ${n(it)} °C." } ?: ".")
            } else {
                null
            },
            coolant?.let { "Motor a ${n(it)} °C." },
            values[STARTER]?.takeIf { it > 0 }?.let { "El motor de arranque está actuando." },
            // La presion de baja es la que da la bomba del deposito; en marcha, en el coche, estuvo en 3,8 bar.
            values[FUEL_LOW_PRESSURE]?.let { low ->
                "Gasóleo: ${n(low, 2)} bar de baja" + (values[PUMP_CURRENT]?.let { ", con la bomba del depósito gastando ${n(it, 1)} A." } ?: ".")
            },
            values[RAIL_REAL]?.let { rail ->
                "Raíl: ${n(rail)} bar" + (values[RAIL_ASKED]?.let { " y pide ${n(it)}." } ?: ".")
            },
            // La del borne y la del OBD se leen seguido; la de la centralita puede ser todavia la de antes de arrancar.
            (values[BATTERY_TERMINAL] ?: values[Moment.MODULE_VOLTAGE] ?: values[BATTERY_VOLTS])?.let { volts ->
                "Batería: ${n(volts, 1)} V" + (values[BATTERY_MARGIN]?.let { "; margen para arrancar ${n(it)} (más alto, mejor)." } ?: ".")
            },
            if (faults.isEmpty() && Pids.BMW_GLOW_FAULTS.all { values[it.first] != null }) "Su mando no marca ningún fallo ahora." else null,
        )
        return when {
            power == null -> HealthCard(HealthSystem.COLD_START, HealthLevel.UNKNOWN, "Esperando los datos de los calentadores.", facts)
            faults.isNotEmpty() -> HealthCard(
                HealthSystem.COLD_START, HealthLevel.WATCH,
                "El mando de los calentadores ve un fallo: " + faults.joinToString(", ") + ".",
                facts,
                "El código dice en qué calentador. En verano casi no se nota; en invierno ese cilindro arranca peor. " +
                    "En el taller: medir la resistencia de ese calentador y su cable.",
            )
            glowing -> HealthCard(HealthSystem.COLD_START, HealthLevel.OK, "Calentadores encendidos.", facts)
            coolant != null && coolant >= WARM_ENGINE -> HealthCard(
                HealthSystem.COLD_START, HealthLevel.OK, "Calentadores apagados: con el motor caliente no hacen falta.", facts,
            )
            else -> HealthCard(HealthSystem.COLD_START, HealthLevel.OK, "Calentadores apagados.", facts)
        }
    }

    /**
     * Lo que se le pide al turbo y lo que da, el aire que entra y lo que recircula la EGR. Es
     * informativa: un instante suelto no dice si falta presion, porque el turbo tarda en llegar.
     * Los avisos de falta de presion salen del consejero, que espera a que se mantenga.
     */
    private fun turbo(values: Map<Int, Double>): HealthCard {
        val asked = values[BOOST_ASKED]
        val real = values[Pids.MAP]
        val air = values[AIR_PER_CYLINDER]
        val facts = listOfNotNull(
            values[BOOST_DEVIATION]?.let { "Diferencia que ve la centralita entre lo pedido y lo real: ${n(it)} hPa." },
            values[TURBO_ACTUATOR]?.let { actuator ->
                "Actuador del turbo al ${n(actuator)} %" + (values[TURBO_SPEED]?.let { "; el turbo gira a unas ${thousands(it)} rpm." } ?: ".")
            },
            air?.let { mg ->
                "Aire por cilindro: ${n(mg)} mg" + (values[AIR_DEVIATION]?.let { ", a ${n(it, 1)} mg de lo pedido." } ?: ".")
            },
            values[EGR_POSITION]?.let { position ->
                "EGR: válvula al ${n(position)} %" + (values[EGR_RATE]?.let { "; recircula el ${n(it)} % del gas." } ?: ".")
            },
            values[AFTER_COMPRESSOR]?.let { hot ->
                "El aire sale del compresor a ${n(hot)} °C" + (values[AFTER_INTERCOOLER]?.let { " y del intercooler a ${n(it)} °C." } ?: ".")
            },
        )
        if (asked == null) return HealthCard(HealthSystem.TURBO, HealthLevel.UNKNOWN, "Esperando los datos del turbo.", facts)
        val ambient = values[Pids.BAROMETRIC] ?: SEA_LEVEL_KPA
        val now = "pide ${n(asked)} kPa" + (real?.let { " y tiene ${n(it)}" } ?: "")
        val headline = if (asked - ambient >= BOOSTING_OVER_AMBIENT_KPA) "Soplando: $now." else "Sin exigirle al turbo: $now."
        return HealthCard(HealthSystem.TURBO, HealthLevel.OK, headline, facts)
    }

    /**
     * La centralita iguala los cilindros corrigiendo lo que inyecta cada uno, y aprende cuanto
     * tiempo de mas necesita cada inyector. Uno que se aparta de los otros es el sospechoso. Los
     * limites son orientativos, de lo que se suele dar por bueno en estos motores, no de BMW.
     */
    private fun injectors(values: Map<Int, Double>): HealthCard {
        val corrections = Pids.BMW_CYLINDER_CORRECTIONS.map { values[it] }
        val general = values[GENERAL_CORRECTION]
        val times = Pids.BMW_INJECTOR_TIMES.map { point -> point.map { values[it] } }
        val facts = listOfNotNull(
            perCylinder(corrections, 1)?.let { "Lo que corrige a cada inyector para igualar los cilindros: $it mg." },
            general?.let { "Corrección general de la cantidad: ${signed(it, 2)} mg." },
        ) + times.mapIndexedNotNull { point, row ->
            perCylinder(row, 0, plus = false)?.let { "Tiempo de más que ha aprendido a darles a ${Pids.BMW_CALIBRATION_BAR[point]} bar: $it µs." }
        }
        val known = corrections.withIndex().mapNotNull { (i, value) -> value?.let { i + 1 to it } }
        val worst = known.maxByOrNull { kotlin.math.abs(it.second) }
        val far = worst?.takeIf { kotlin.math.abs(it.second) >= CORRECTION_WARN }
        return when {
            worst == null -> HealthCard(HealthSystem.INJECTION, HealthLevel.UNKNOWN, "Esperando los datos de los inyectores.", facts)
            far != null -> HealthCard(
                HealthSystem.INJECTION,
                if (kotlin.math.abs(far.second) >= CORRECTION_BAD) HealthLevel.BAD else HealthLevel.WATCH,
                "El inyector ${far.first} se aparta de los demás: le corrige ${signed(far.second, 1)} mg.",
                facts,
                "Con signo más, ese cilindro da menos fuerza y le añade gasóleo; con menos, le sobra. Si se mantiene en " +
                    "caliente y al ralentí, en el taller: prueba de retorno de inyectores y compresión de ese cilindro.",
            )
            else -> HealthCard(
                HealthSystem.INJECTION, HealthLevel.OK,
                "Los cuatro inyectores van parejos.",
                facts + "Cerca de cero es lo bueno. Se ajusta sobre todo al ralentí y en caliente.",
            )
        }
    }

    /** Los valores de los cuatro cilindros en una frase, o null si falta alguno. */
    private fun perCylinder(row: List<Double?>, decimals: Int, plus: Boolean = true): String? {
        if (row.any { it == null }) return null
        return row.withIndex().joinToString(" · ") { (i, value) ->
            "${i + 1}: " + if (plus) signed(value!!, decimals) else n(value!!, decimals)
        }
    }

    private fun filter(values: Map<Int, Double>): HealthCard {
        val asked = values[Pids.BMW_REGEN_REQUEST]
        val measured = values[Pids.BMW_SOOT_USED]
        val model = values[Pids.BMW_SOOT_MODEL]
        val distance = values[FILTER_DISTANCE]
        val minutes = values[FILTER_MINUTES]
        val catalyst = values[BEFORE_CATALYST] ?: Moment.EXHAUST_TEMPS.mapNotNull { values[it] }.maxOrNull()
        val coolant = values[Pids.COOLANT]
        val beforeFilter = values[BEFORE_FILTER] ?: catalyst
        val facts = listOfNotNull(
            asked?.let { "Petición de regeneración: ${n(it)}, ${Pids.regenRequestText(it)}." },
            values[Pids.BMW_REGEN_INTERRUPTION]?.takeIf { asked?.toInt() == Pids.BMW_REGEN_RUNNING.toInt() }?.let(Pids::interruptionText),
            values[Pids.BMW_ISTA_PROTECTION_SECONDS]?.let {
                "Petición por protección del motor (TEPR): quedan ${thousands(it)} s; baja con la regeneración normal en marcha."
            },
            values[REGEN_LOCK]?.let {
                if (it == 0.0) "Regeneración bloqueada: no." else "Regeneración bloqueada: valor ${n(it)}; ISTA lo da por bloqueada."
            },
            values[Pids.BMW_FILTER_FAULTS]?.let { BmwFault.filterLevelText(it) + "." },
            values[Pids.BMW_FILTER_FAULTS]?.takeIf { it >= 1 }?.let {
                "Regeneración de servicio de BMW: exige 480A y 481A no presentes, motor a más de 75 °C" +
                    (coolant?.let { c -> " (ahora ${n(c)})" } ?: "") + ", escape antes del filtro a más de 240 °C" +
                    (beforeFilter?.let { t -> " (ahora ${n(t)})" } ?: "") + " y no ir en reserva."
            },
            if (measured != null && model != null) {
                "Hollín: ${n(measured, 1)} g el que usa la centralita y ${n(model, 1)} g el calculado" +
                    (values[Pids.BMW_SOOT_PRESSURE]?.let { " (medido por la presión: ${n(it, 1)} g)." } ?: ".")
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
        return when {
            asked == null -> HealthCard(HealthSystem.FILTER, HealthLevel.UNKNOWN, "Esperando los datos del filtro.", facts)
            Pids.protecting(values) -> HealthCard(
                HealthSystem.FILTER, HealthLevel.WATCH,
                "Regeneración de protección: el filtro está muy cargado.",
                facts,
                "La centralita lo quema despacio, sin pasar de unos 500 °C, para no dañarlo. Necesita trayectos seguidos por carretera.",
            )
            Pids.regenerating(values) -> HealthCard(
                HealthSystem.FILTER, HealthLevel.WATCH,
                "Está regenerando.",
                facts,
                "Si puedes, no apagues el motor hasta que termine. Al acabar, la distancia desde la última regeneración vuelve a cero.",
            )
            // Con el 5 el motor calienta a ratos: entre medias esta en pausa, no bloqueado.
            asked.toInt() == Pids.BMW_REGEN_RUNNING.toInt() && values[Pids.BMW_ENGINE_MODE] != null -> HealthCard(
                HealthSystem.FILTER, HealthLevel.WATCH,
                "Regeneración en marcha, ahora en pausa.",
                facts,
                if (Pids.regenNow(values) == "pausa · temp.") {
                    "Ya llegó a su temperatura y deja de calentar; vuelve en unos segundos, cuando baje."
                } else {
                    "Deja de calentar un momento; pasa sobre todo al pisar fuerte o al soltar el acelerador. Mejor velocidad constante."
                },
            )
            asked >= Pids.BMW_REGEN_ACTIVE -> HealthCard(
                HealthSystem.FILTER, HealthLevel.WATCH,
                "La centralita pide regenerar, pero no lo está haciendo.",
                facts,
                "Lo dice el modo del motor. Pasa cuando algo se lo impide, por ejemplo una avería del escape o del sensor del filtro.",
            )
            asked > 0 -> HealthCard(
                HealthSystem.FILTER, HealthLevel.WATCH,
                "La centralita tiene una regeneración pendiente.",
                facts,
                "La hará cuando lleves unos minutos seguidos por carretera, sin parar el motor.",
            )
            else -> HealthCard(HealthSystem.FILTER, HealthLevel.OK, "No está regenerando.", facts)
        }
    }

    private fun n(value: Double, decimals: Int = 0) = String.format(Locale.US, "%.${decimals}f", value).replace('.', ',')

    private fun thousands(value: Double) = String.format(Locale.US, "%,.0f", value).replace(',', '.')

    private fun signed(value: Double, decimals: Int) = n(value, decimals).let { if (value > 0 && it.any { c -> c in '1'..'9' }) "+$it" else it }
}
