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
        assertEquals(EngineProfile.BMW_N47, EngineProfile.candidate(bmw))
        assertNull(EngineProfile.candidate(bmw.copy(diesel = false)))
        assertNull(EngineProfile.candidate(bmw.copy(make = "Volkswagen")))
        assertNull(EngineProfile.candidate(Vehicle.GENERIC))
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
        // Otra centralita contesta otra cosa en esas direcciones: basta una que desentone.
        assertFalse(N47Fingerprint.fits(own + (ids[0] to 12.0), obd))
        assertFalse(N47Fingerprint.fits(own + (ids[2] to 5.0), obd))
        assertFalse(N47Fingerprint.fits(emptyMap(), obd))
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
