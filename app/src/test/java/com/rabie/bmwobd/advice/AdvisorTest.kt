package com.rabie.bmwobd.advice

import com.rabie.bmwobd.obd.Pids
import com.rabie.bmwobd.obd.SimulatedEngine
import com.rabie.bmwobd.trips.TripCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvisorTest {

    private val warmIdle = mapOf(Pids.RPM to 800.0, Pids.SPEED to 0.0, Pids.COOLANT to 90.0, Pids.LOAD to 20.0)

    /** Mantiene las mismas lecturas [seconds] segundos y devuelve lo que queda activo al final. */
    private fun hold(values: Map<Int, Double>, seconds: Int, advisor: Advisor = Advisor()): List<Advice> {
        var last = emptyList<Advice>()
        for (s in 0..seconds) last = advisor.update(s * 1000L, values)
        return last
    }

    private fun List<Advice>.find(id: String) = firstOrNull { it.id == id }

    @Test
    fun `parado regenerando avisa de no apagar y un ralenti largo se dice`() {
        val regenerating = warmIdle + (Pids.BMW_REGEN_REQUEST to 5.0)
        assertEquals(Severity.WARN, hold(regenerating, 30).find("regen_stop")?.severity)
        assertNull(hold(regenerating, 10).find("regen_stop"))
        // Circulando no: ese es el aviso normal de regeneracion.
        assertNull(hold(regenerating + (Pids.SPEED to 80.0) + (Pids.RPM to 1900.0), 120).find("regen_stop"))

        assertNull(hold(warmIdle, 240).find("long_idle"))
        assertEquals(Severity.INFO, hold(warmIdle, 320).find("long_idle")?.severity)
        // Regenerando no se suma el aviso de ralenti largo.
        assertNull(hold(regenerating, 400).find("long_idle"))
    }

    @Test
    fun `un ralenti normal en caliente no dice nada`() {
        assertTrue(hold(warmIdle, 120).isEmpty())
    }

    @Test
    fun `carga alta al ralenti avisa y muy alta va en rojo`() {
        assertEquals(Severity.WARN, hold(warmIdle + (Pids.LOAD to 50.0), 30).find("idle_load")?.severity)
        assertEquals(Severity.ALERT, hold(warmIdle + (Pids.LOAD to 70.0), 30).find("idle_load")?.severity)
    }

    @Test
    fun `un pico suelto no salta y en frio la carga al ralenti no cuenta`() {
        assertNull(hold(warmIdle + (Pids.LOAD to 70.0), 5).find("idle_load"))
        assertNull(hold(warmIdle + (Pids.LOAD to 70.0) + (Pids.COOLANT to 30.0), 60).find("idle_load"))
    }

    @Test
    fun `escape muy caliente sin pedirle fuerza es una regeneracion`() {
        // Lo grabado en el coche: 95 km/h, escape a 590 °C y lambda 1,3 durante doce minutos.
        val cruising = mapOf(Pids.RPM to 1700.0, Pids.SPEED to 95.0, Pids.COOLANT to 91.0, Pids.LOAD to 45.0)
        val regenerating = cruising + (Moment.CATALYST_TEMP to 590.0) + (Pids.LAMBDA to 1.3)
        assertEquals(Severity.INFO, hold(regenerating, 60).find("regeneration")?.severity)
        assertNull(hold(regenerating, 20).find("regeneration"))
        // A fondo el escape tambien pasa de 500 °C y no es una regeneracion.
        assertNull(hold(regenerating + (Pids.LOAD to 100.0) + (Pids.LAMBDA to 1.15), 60).find("regeneration"))
        assertNull(hold(cruising + (Moment.CATALYST_TEMP to 300.0) + (Pids.LAMBDA to 2.0), 60).find("regeneration"))
    }

    @Test
    fun `hollin medido muy por encima del calculado avisa si se mantiene`() {
        val driving = mapOf(Pids.RPM to 1700.0, Pids.SPEED to 90.0, Pids.COOLANT to 91.0, Pids.LOAD to 40.0)
        val apart = driving + (Pids.BMW_SOOT_MEASURED to 25.0) + (Pids.BMW_SOOT_MODEL to 5.6)
        assertEquals(Severity.WARN, hold(apart, 320).find("soot_gap")?.severity)
        assertNull(hold(apart, 120).find("soot_gap"))
        assertNull(hold(driving + (Pids.BMW_SOOT_MEASURED to 9.0) + (Pids.BMW_SOOT_MODEL to 5.6), 320).find("soot_gap"))
    }

    @Test
    fun `refrigerante muy alto va en rojo`() {
        assertEquals(Severity.WARN, hold(warmIdle + (Pids.COOLANT to 110.0), 10).find("coolant_hot")?.severity)
        assertEquals(Severity.ALERT, hold(warmIdle + (Pids.COOLANT to 118.0), 10).find("coolant_hot")?.severity)
    }

    @Test
    fun `si llega menos turbo del pedido acelerando a fondo`() {
        val pull = mapOf(
            Pids.RPM to 2500.0, Pids.SPEED to 90.0, Pids.COOLANT to 90.0, Pids.LOAD to 95.0,
            Moment.BOOST_COMMANDED to 230.0,
        )
        assertNull(hold(pull + (Moment.BOOST_ACTUAL to 225.0), 10).find("boost_low"))
        assertEquals(Severity.WARN, hold(pull + (Moment.BOOST_ACTUAL to 205.0), 10).find("boost_low")?.severity)
        assertEquals(Severity.ALERT, hold(pull + (Moment.BOOST_ACTUAL to 180.0), 10).find("boost_low")?.severity)
    }

    @Test
    fun `una regeneracion que se corta lo dice y una que termina tambien`() {
        val cruising = mapOf(Pids.RPM to 1900.0, Pids.SPEED to 100.0, Pids.COOLANT to 90.0, Pids.LOAD to 40.0)
        val sinceRegen = Pids.bmw(Pids.BMW_SINCE_REGEN_ADDRESS)
        val regenerating = cruising + (Pids.BMW_ENGINE_MODE to Pids.MODE_REGENERATING.toDouble()) + (sinceRegen to 270.0)
        val normal = cruising + (Pids.BMW_ENGINE_MODE to Pids.MODE_NORMAL.toDouble())

        fun drive(after: Map<Int, Double>, seconds: Int): List<Advice> {
            val advisor = Advisor()
            for (s in 0..300) advisor.update(s * 1000L, regenerating)
            var last = emptyList<Advice>()
            for (s in 301..300 + seconds) last = advisor.update(s * 1000L, after)
            return last
        }

        val cut = drive(normal + (sinceRegen to 274.0), 90)
        assertEquals(Severity.WARN, cut.find("regen_cut")?.severity)
        assertNull(cut.find("regen_done"))
        // Un baile corto del modo no es un corte.
        assertNull(drive(normal + (sinceRegen to 274.0), 30).find("regen_cut"))
        val done = drive(normal + (sinceRegen to 0.4), 90)
        assertEquals(Severity.INFO, done.find("regen_done")?.severity)
        assertNull(done.find("regen_cut"))
        // Pasados diez minutos ya no se dice.
        assertNull(drive(normal + (sinceRegen to 274.0), 800).find("regen_cut"))
    }

    @Test
    fun `con la peticion en 5 la regeneracion sigue aunque el modo entre y salga`() {
        // Como el 16 de octubre: la peticion en 5 veintidos minutos y el modo a ratos normal.
        val cruising = mapOf(Pids.RPM to 2000.0, Pids.SPEED to 100.0, Pids.COOLANT to 90.0, Pids.LOAD to 40.0)
        val sinceRegen = Pids.bmw(Pids.BMW_SINCE_REGEN_ADDRESS)
        val asked = cruising + (Pids.BMW_REGEN_REQUEST to 5.0) + (sinceRegen to 640.0)
        val advisor = Advisor()
        var last = emptyList<Advice>()
        for (s in 0..1320) {
            val mode = if ((s / 120) % 2 == 0) Pids.MODE_REGENERATING else Pids.MODE_NORMAL
            last = advisor.update(s * 1000L, asked + (Pids.BMW_ENGINE_MODE to mode.toDouble()))
            assertNull("corte falso en el segundo $s", last.find("regen_cut"))
        }
        // La peticion vuelve a 7 sin que el contador se ponga a cero: eso si es un corte.
        val protecting = cruising + (Pids.BMW_REGEN_REQUEST to 7.0) + (sinceRegen to 664.0) +
            (Pids.BMW_ENGINE_MODE to Pids.MODE_PROTECTION.toDouble())
        for (s in 1321..1420) last = advisor.update(s * 1000L, protecting)
        assertEquals(Severity.WARN, last.find("regen_cut")?.severity)

        // Un intento de diez segundos que la centralita cancela no es un corte.
        val tried = Advisor()
        for (s in 0..10) tried.update(s * 1000L, asked)
        for (s in 11..200) last = tried.update(s * 1000L, protecting)
        assertNull(last.find("regen_cut"))
    }

    @Test
    fun `mezcla muy rica un rato seguido es humo negro`() {
        // Lo grabado el dia de la abrazadera suelta: 34 g/s de aire con 56 mg a 2000 rpm, unos 9 a 1.
        val rich = mapOf(
            Pids.RPM to 2000.0, Pids.SPEED to 80.0, Pids.COOLANT to 85.0, Pids.LOAD to 90.0,
            Moment.MAF to 34.0, Pids.BMW_INJECTION to 56.0,
        )
        assertEquals(Severity.WARN, hold(rich, 20).find("rich_mix")?.severity)
        assertNull(hold(rich, 10).find("rich_mix"))
        // Con el turbo soplando entra el doble de aire: unos 18 a 1, normal a fondo.
        assertNull(hold(rich + (Moment.MAF to 68.0), 20).find("rich_mix"))
        // Inyectando poco no cuenta: los valores leidos a destiempo dan cuentas raras.
        assertNull(hold(rich + (Pids.BMW_INJECTION to 10.0) + (Moment.MAF to 5.0), 20).find("rich_mix"))
    }

    @Test
    fun `turbo que no sopla mientras se le pide va en rojo`() {
        // Lo grabado el dia de la abrazadera suelta: pide 213 kPa, hay 81 y la calle esta a 95.
        val asking = mapOf(
            Pids.RPM to 2000.0, Pids.SPEED to 75.0, Pids.COOLANT to 85.0, Pids.LOAD to 90.0,
            Moment.BMW_BOOST_COMMANDED to 213.0, Moment.BAROMETRIC to 95.0,
        )
        assertEquals(Severity.ALERT, hold(asking + (Pids.MAP to 81.0), 15).find("boost_uncontrolled")?.severity)
        // Unos segundos mientras el turbo coge vueltas no cuentan.
        assertNull(hold(asking + (Pids.MAP to 81.0), 6).find("boost_uncontrolled"))
        // Con el turbo soplando, aunque llegue algo menos de lo pedido, no es este aviso.
        assertNull(hold(asking + (Pids.MAP to 190.0), 10).find("boost_uncontrolled"))
        // Si no pide turbo, no hay nada que regular.
        assertNull(hold(asking + (Moment.BMW_BOOST_COMMANDED to 120.0) + (Pids.MAP to 98.0), 10).find("boost_uncontrolled"))
    }

    @Test
    fun `el motor que no coge temperatura tras un cuarto de hora rodando`() {
        val cruising = mapOf(Pids.RPM to 2000.0, Pids.SPEED to 100.0, Pids.COOLANT to 65.0, Pids.LOAD to 40.0)
        assertNull(hold(cruising, 10 * 60).find("thermostat"))
        assertEquals(Severity.WARN, hold(cruising, 17 * 60).find("thermostat")?.severity)
    }

    @Test
    fun `la tension baja de la recuperacion de energia no es averia`() {
        val running = warmIdle + (Pids.RPM to 2000.0) + (Pids.SPEED to 110.0)
        assertNull(hold(running + (Moment.MODULE_VOLTAGE to 12.3), 120).find("voltage"))
        assertNull(hold(running + (Moment.MODULE_VOLTAGE to 14.9), 120).find("voltage"))
        assertEquals(Severity.WARN, hold(running + (Moment.MODULE_VOLTAGE to 11.7), 60).find("voltage")?.severity)
        assertEquals(Severity.ALERT, hold(running + (Moment.MODULE_VOLTAGE to 11.2), 60).find("voltage")?.severity)
    }

    @Test
    fun `filtro de particulas cargado al ralenti`() {
        assertNull(hold(warmIdle + (Moment.FILTER_PRESSURE to 1.2), 60).find("filter_idle"))
        assertEquals(Severity.WARN, hold(warmIdle + (Moment.FILTER_PRESSURE to 3.0), 60).find("filter_idle")?.severity)
        assertEquals(Severity.ALERT, hold(warmIdle + (Moment.FILTER_PRESSURE to 5.0), 60).find("filter_idle")?.severity)
    }

    @Test
    fun `enfriarse en carretera despues de haber llegado a temperatura`() {
        val road = mapOf(Pids.RPM to 2000.0, Pids.SPEED to 110.0, Pids.LOAD to 40.0)
        val advisor = Advisor()
        // Subiendo de 70 a 90 no hay nada que decir, aunque pase por 78.
        assertNull(hold(road + (Pids.COOLANT to 78.0), 120, advisor).find("thermostat_drop"))
        var last = emptyList<Advice>()
        for (s in 121..200) last = advisor.update(s * 1000L, road + (Pids.COOLANT to 90.0))
        assertNull(last.find("thermostat_drop"))
        for (s in 201..300) last = advisor.update(s * 1000L, road + (Pids.COOLANT to 78.0))
        assertEquals(Severity.WARN, last.find("thermostat_drop")?.severity)
    }

    @Test
    fun `turbo y aire a plena carga contra la referencia del motor`() {
        val bmw = com.rabie.bmwobd.vehicle.Vehicle.GENERIC.copy(name = "118d", make = "BMW", profile = "bmw_n47", displacementLiters = 2.0)
        fun pull(rpm: Double, map: Double, air: Double): List<Advice> {
            val advisor = Advisor()
            val values = mapOf(
                Pids.RPM to rpm, Pids.SPEED to 100.0, Pids.COOLANT to 90.0, Pids.LOAD to 100.0,
                Pids.MAP to map, Pids.BAROMETRIC to 95.0, Moment.MAF to air,
            )
            var last = emptyList<Advice>()
            for (s in 0..10) last = advisor.update(s * 1000L, values, bmw)
            return last
        }
        assertNull(pull(3000.0, 240.0, 105.0).find("boost_low"))
        assertEquals(Severity.WARN, pull(3000.0, 200.0, 90.0).find("boost_low")?.severity)
        assertEquals(Severity.ALERT, pull(3000.0, 180.0, 80.0).find("boost_low")?.severity)
        // A 3000 rpm el aire no se compara; a 3800 si.
        assertNull(pull(3000.0, 240.0, 70.0).find("air_full"))
        assertNull(pull(3800.0, 240.0, 110.0).find("air_full"))
        assertEquals(Severity.WARN, pull(3800.0, 240.0, 80.0).find("air_full")?.severity)
    }

    @Test
    fun `ralenti subido con tension alta es el coche cargando la bateria`() {
        val charging = warmIdle + (Pids.RPM to 980.0) + (Moment.MODULE_VOLTAGE to 14.6)
        assertEquals(Severity.INFO, hold(charging, 60).find("idle_rpm")?.severity)
        val odd = warmIdle + (Pids.RPM to 980.0) + (Moment.MODULE_VOLTAGE to 13.8)
        assertEquals(Severity.WARN, hold(odd, 60).find("idle_rpm")?.severity)
    }

    @Test
    fun `la carga corregida no cambia al abrir o cerrar la EGR`() {
        val bmw = com.rabie.bmwobd.vehicle.Vehicle.GENERIC.copy(name = "118d", make = "BMW", profile = "bmw_n47", displacementLiters = 1.995)
        // Dos momentos reales del mismo ralenti: EGR abierta y, un minuto despues, cerrada.
        val base = mapOf(Pids.RPM to 842.0, Pids.SPEED to 0.0, Pids.COOLANT to 90.0, Pids.MAP to 96.0, Moment.INTAKE_TEMP to 21.0)
        val open = Moment(base + (Pids.LOAD to 53.9) + (Moment.MAF to 7.5), 0, null, bmw).egrFreeLoad!!
        val closed = Moment(base + (Pids.LOAD to 27.7) + (Moment.MAF to 13.9), 0, null, bmw).egrFreeLoad!!
        assertEquals(open, closed, 2.0)
        assertEquals(25.0, open, 2.0)
        // Con la EGR abierta el OBD marca 54 %, pero corregida queda en la referencia: sin aviso.
        val idle = base + (Pids.LOAD to 53.9) + (Moment.MAF to 7.5)
        var last = emptyList<Advice>()
        val advisor = Advisor()
        for (s in 0..60) last = advisor.update(s * 1000L, idle, bmw)
        assertNull(last.find("idle_load"))
    }

    @Test
    fun `motor frio exigido es solo un consejo`() {
        val cold = mapOf(Pids.RPM to 3400.0, Pids.SPEED to 60.0, Pids.COOLANT to 35.0, Pids.OIL to 30.0, Pids.LOAD to 60.0)
        assertEquals(Severity.INFO, hold(cold, 5).find("cold_push")?.severity)
    }

    @Test
    fun `el coche simulado sano no da avisos ni rojos`() {
        val advisor = Advisor()
        val ids = listOf(Pids.RPM, Pids.SPEED, Pids.LOAD, Pids.COOLANT, Pids.OIL, Pids.MAP, Pids.BAROMETRIC, 0x0F, 0x10, 0x2C, 0x2D, 0x42)
        for (s in 0..600) {
            val e = SimulatedEngine.at(s.toDouble())
            val values = ids.zip(
                listOf(e.rpm, e.speed, e.load, e.coolant, e.oil, e.mapKpa, e.baroKpa, e.intakeTemp, e.maf, e.egr, e.egrError, e.voltage),
            ).toMap()
            val serious = advisor.update(s * 1000L, values).filter { it.severity != Severity.INFO }
            assertTrue("segundo $s: $serious", serious.isEmpty())
        }
    }

    @Test
    fun `el repaso de un trayecto dice que salio y cuanto duro`() {
        val columns = listOf(Pids.RPM, Pids.SPEED, Pids.COOLANT, Pids.LOAD)
        val lines = sequence {
            yield(TripCsv.header(columns))
            for (s in 0..60) yield(TripCsv.row(s * 1000L, columns, warmIdle))
            for (s in 61..120) yield(TripCsv.row(s * 1000L, columns, warmIdle + (Pids.COOLANT to 118.0)))
        }
        val findings = Advisor.review(TripCsv.parse(lines))
        assertEquals(listOf("coolant_hot"), findings.map { it.advice.id })
        assertEquals(Severity.ALERT, findings.single().advice.severity)
        assertEquals(55L, findings.single().seconds)
    }
    @Test
    fun `la EGR solo avisa en modo normal, no regenerando ni en proteccion`() {
        // EGR mandada al 20 % y el coche dandola cerrada: error -100 %.
        val cruising = mapOf(
            Pids.RPM to 2000.0, Pids.SPEED to 100.0, Pids.COOLANT to 90.0, Pids.LOAD to 40.0,
            Moment.EGR_COMMANDED to 20.0, Moment.EGR_ERROR to -100.0,
        )
        val normal = cruising + (Pids.BMW_ENGINE_MODE to Pids.MODE_NORMAL.toDouble()) + (Pids.BMW_REGEN_REQUEST to 0.0)
        assertEquals(Severity.WARN, hold(normal, 20).find("egr")?.severity)
        assertNull(hold(cruising + (Pids.BMW_ENGINE_MODE to Pids.MODE_PROTECTION.toDouble()) + (Pids.BMW_REGEN_REQUEST to 7.0), 20).find("egr"))
        assertNull(hold(cruising + (Pids.BMW_ENGINE_MODE to Pids.MODE_REGENERATING.toDouble()) + (Pids.BMW_REGEN_REQUEST to 5.0), 20).find("egr"))
        // En pausa dentro de la regeneracion tampoco: la peticion sigue en 5.
        assertNull(hold(normal + (Pids.BMW_REGEN_REQUEST to 5.0), 20).find("egr"))
    }
}
