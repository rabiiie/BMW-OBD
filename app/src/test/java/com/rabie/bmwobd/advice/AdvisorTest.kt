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
}
