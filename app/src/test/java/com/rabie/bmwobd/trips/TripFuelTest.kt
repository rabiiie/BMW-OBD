package com.rabie.bmwobd.trips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripFuelTest {

    private fun real(name: String): TripData {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream(name))
        return stream.bufferedReader().useLines { TripCsv.parse(it) }
    }

    @Test
    fun `el contador suma las subidas, sigue tras una puesta a cero y descarta un salto imposible`() {
        val counter = FuelCounter()
        assertFalse(counter.seen)
        // Ida del 12 de octubre: de 3,46 a 7,05 L.
        listOf(3.46, 3.46, 3.60, 4.40, 5.10, 5.90, 6.80, 7.05).forEach(counter::add)
        assertEquals(3.59, counter.liters, 0.001)
        // Una lectura cruzada de otra medida no cuenta, ni hacia arriba ni hacia abajo.
        counter.add(94.4)
        counter.add(3.0)
        assertEquals(3.59, counter.liters, 0.001)
        // Una regeneracion completa lo pone a cero: lo que marca despues es lo gastado desde entonces.
        counter.add(0.12)
        counter.add(0.40)
        assertEquals(3.99, counter.liters, 0.001)
        counter.add(null)
        counter.add(Double.NaN)
        assertEquals(3.99, counter.liters, 0.001)
        // Un salto grande que la lectura siguiente confirma era de verdad.
        counter.add(2.90)
        assertEquals(3.99, counter.liters, 0.001)
        counter.add(2.95)
        assertEquals(6.54, counter.liters, 0.001)
    }

    @Test
    fun `la ida del 12 de octubre gasto 3,59 litros en 79 km`() {
        val fuel = TripFuel.of(real("trayecto_real_12oct_consumo.csv"))!!
        assertTrue(fuel.real)
        assertEquals(3.59, fuel.liters, 0.011)
        assertEquals(79.4, fuel.km, 0.3)
        assertEquals(4.5, fuel.per100!!, 0.1)
        // El reparto suma el total, en kilometros y en litros.
        assertEquals(fuel.km, fuel.bands.sumOf { it.km }, 0.001)
        assertEquals(fuel.liters, fuel.bands.sumOf { it.liters }, 0.001)
        assertEquals(3, fuel.bands.size)
        assertEquals(fuel.liters * 1.459, fuel.cost(1.459)!!, 0.0001)
        val facts = fuel.facts(1.459)
        assertTrue(facts.first().second.startsWith("3,59 L en 79,"))
        assertTrue(facts.any { it.first == "Coste" && it.second.startsWith("5,2") })
        assertTrue(TripFuel.of(real("trayecto_real_12oct_consumo.csv"))!!.facts(null).any { it.second.contains("Ajustes") })
    }
}
