package com.rabie.bmwobd.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleTest {

    @Test
    fun `fabricante por el principio del bastidor`() {
        assertEquals("BMW", Vin.make("WBAUD31060P123456"))
        assertEquals("Volkswagen", Vin.make("WVGZZZ1TZKW012345"))
        assertEquals("Toyota", Vin.make("JTDKB20U093012345"))
        assertNull(Vin.make("XXX00000000000000"))
    }

    @Test
    fun `la escala del cuentarrevoluciones depende del combustible`() {
        val diesel = Vehicle.GENERIC
        val petrol = diesel.copy(diesel = false)
        assertEquals(listOf("0", "1", "2", "3", "4", "5", "6"), diesel.rpmLabels)
        assertEquals(9, petrol.rpmLabels.size)
        assertEquals(112.0, petrol.coolantWarn, 0.0)
    }
}
