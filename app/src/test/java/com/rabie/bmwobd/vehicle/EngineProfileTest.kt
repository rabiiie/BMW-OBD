package com.rabie.bmwobd.vehicle

import com.rabie.bmwobd.obd.ObdSession
import com.rabie.bmwobd.obd.Pids
import com.rabie.bmwobd.obd.SimulatedTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineProfileTest {

    private val bmw = Vehicle.GENERIC.copy(name = "118d", make = "BMW")
    private val ids = N47Fingerprint.checks.map { it.def.id }

    @Test
    fun `sin perfil guardado el coche es generico y no tiene nada propio`() {
        assertEquals(EngineProfile.GENERIC, EngineProfile.of(bmw))
        assertTrue(EngineProfile.GENERIC.measures.isEmpty())
        assertTrue(EngineProfile.GENERIC.caseMeasures.isEmpty())
        assertFalse(EngineProfile.GENERIC.ownDiagnostics)
        // Ser BMW diesel de dos litros ya no basta para tener la referencia del N47.
        assertNull(EngineReference.of(bmw.copy(displacementLiters = 1.995)))

        val known = bmw.copy(profile = EngineProfile.BMW_N47.key)
        assertEquals(EngineProfile.BMW_N47, EngineProfile.of(known))
        assertEquals(Pids.bmwDiesel, EngineProfile.BMW_N47.measures)
        assertEquals(EngineReference.N47, EngineReference.of(known))
        assertEquals(EngineProfile.GENERIC, EngineProfile.of(bmw.copy(profile = "uno_que_ya_no_existe")))
    }

    @Test
    fun `solo se comprueba la centralita de un BMW diesel`() {
        assertEquals(EngineProfile.BMW_N47, EngineProfile.candidates(bmw).first())
        assertTrue(EngineProfile.candidates(bmw.copy(diesel = false)).isEmpty())
        assertTrue(EngineProfile.candidates(bmw.copy(make = "Volkswagen")).isEmpty())
        assertTrue(EngineProfile.candidates(Vehicle.GENERIC).isEmpty())
    }

    @Test
    fun `la centralita se reconoce con lo que marco el coche y otra no encaja`() {
        // Sondeo del 9 de octubre: 0547 dio 48,46 °C con el refrigerante del OBD en 48 °C.
        val own = mapOf(ids[0] to 48.46, ids[1] to 812.0, ids[2] to 14.02, ids[3] to 938.0)
        val obd = mapOf(Pids.COOLANT to 48.0, Pids.RPM to 806.0, 0x42 to 14.0, Pids.BAROMETRIC to 94.0)
        assertTrue(N47Fingerprint.fits(own, obd))
        // Con una que falte todavia vale; con dos, no hay bastante para decidir.
        assertTrue(N47Fingerprint.fits(own - ids[3], obd))
        assertFalse(N47Fingerprint.fits(own - ids[3] - ids[2], obd))
        // Una que desentone se tolera, porque las lecturas no son del mismo instante.
        assertTrue(N47Fingerprint.fits(own + (ids[1] to 2400.0), obd))
        // Otra centralita contesta otra cosa en esas direcciones: con dos que desentonen ya no vale.
        assertFalse(N47Fingerprint.fits(own + (ids[0] to 12.0) + (ids[2] to 5.0), obd))
        assertFalse(N47Fingerprint.fits(emptyMap(), obd))
    }

    @Test
    fun `con las lecturas del barrido del coche la centralita se reconoce en todas las pasadas`() {
        // Barrido del 11 de octubre: lo que dio cada direccion y lo que marcaba el OBD en ese momento.
        // La presion ambiente del OBD no iba en el barrido; en los trayectos estuvo entre 91 y 95 kPa.
        val passes = listOf(
            listOf(92.66 to 92.0, 1804.0 to 1806.0, 14.04 to 14.0, 919.0 to 92.0),
            listOf(92.36 to 90.0, 1999.0 to 2014.5, 14.04 to 14.0, 919.0 to 92.0),
            listOf(89.56 to 88.0, 1677.0 to 1651.5, 13.92 to 14.0, 923.0 to 92.0),
            listOf(87.86 to 86.0, 1951.0 to 1946.0, 14.62 to 14.6, 953.0 to 95.0),
            listOf(93.16 to 92.0, 1902.0 to 1907.0, 13.96 to 13.9, 947.0 to 95.0),
            listOf(89.76 to 91.0, 1947.0 to 1948.5, 13.94 to 14.0, 925.0 to 92.0),
        )
        val standard = N47Fingerprint.checks.map { it.obd }
        for (pass in passes) {
            val own = ids.zip(pass.map { it.first }).toMap()
            val obd = standard.zip(pass.map { it.second }).toMap()
            assertTrue(pass.toString(), N47Fingerprint.fits(own, obd))
            // Y sin la presion ambiente, que es la que no se pudo emparejar, tambien.
            assertTrue(N47Fingerprint.fits(own - ids[3], obd))
        }
    }

    @Test
    fun `leida de la centralita, la huella coincide con el OBD normal`() = runBlocking<Unit> {
        val session = ObdSession(SimulatedTransport(latencyMs = 0)) {}
        session.connect()
        val own = session.readBmw(N47Fingerprint.checks.map { it.def })
        assertNotNull(own)
        val obd = N47Fingerprint.checks.mapNotNull { check ->
            session.read(check.obd)?.let { Pids.byId.getValue(check.obd).decodeOrNull(it) }?.let { check.obd to it }
        }.toMap()
        assertEquals(4, own!!.size)
        assertTrue(N47Fingerprint.fits(own, obd))
    }
}
