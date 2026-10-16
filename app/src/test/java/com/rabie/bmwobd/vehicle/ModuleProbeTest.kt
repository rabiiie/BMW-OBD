package com.rabie.bmwobd.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModuleProbeTest {

    private fun car(vin: String?, profile: String? = null) =
        Vehicle(vin ?: Vehicle.UNKNOWN_KEY, vin, "coche", vin?.let(Vin::make), diesel = true, displacementLiters = null, profile = profile)

    @Test
    fun `cada marca pregunta a las suyas y ninguna se mezcla`() {
        // El BMW solo con la centralita del motor reconocida, como hasta ahora.
        assertEquals(ModuleProbe.BMW, ModuleProbe.of(car("WBAUD71000P000000", EngineProfile.BMW_N47.key)))
        // Un BMW diesel sin la centralita del N47 se prueba como uno de la serie F, que habla UDS.
        assertEquals(ModuleProbe.BMW_UDS, ModuleProbe.of(car("WBA1R510000000000")))
        // Un Focus fabricado en Alemania: bastidor WF0.
        assertEquals(ModuleProbe.FORD, ModuleProbe.of(car("WF0XXXGCDXEA00000")))
        // Y uno de otra fabrica de Ford: el codigo del principio cambia, la marca no.
        assertEquals(ModuleProbe.FORD, ModuleProbe.of(car("VS6XXXGCDXEA00000")))
        // El Touran y un coche sin bastidor no tienen otras centralitas que preguntar.
        assertNull(ModuleProbe.of(car("WVGZZZ1TZBW000000")))
        assertNull(ModuleProbe.of(car(null)))
    }
}
