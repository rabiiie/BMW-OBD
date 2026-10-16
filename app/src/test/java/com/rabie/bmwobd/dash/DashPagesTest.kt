package com.rabie.bmwobd.dash

import com.rabie.bmwobd.advice.Advice
import com.rabie.bmwobd.advice.Severity
import com.rabie.bmwobd.obd.Pids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashPagesTest {

    private val trip = TripNow(12.3, 600_000, 6.1, diesel = true, liters = 0.8, real = true, price = 1.5)

    @Test
    fun `una tarjeta por tema, y sin medidas de BMW solo las que tienen datos`() {
        val bmw = DashPages.pages(emptyMap(), trip, emptyList(), bmw = true)
        assertEquals(listOf("Trayecto", "Filtro de partículas", "Turbo y aire", "Motor", "Inyectores", "Eléctrico"), bmw.map { it.title })
        assertEquals(listOf("Trayecto", "Turbo y aire", "Motor", "Eléctrico"), DashPages.pages(emptyMap(), trip, emptyList(), bmw = false).map { it.title })
    }

    @Test
    fun `la del filtro dice la peticion y las averias, y un aviso manda en su linea`() {
        val values = mapOf(Pids.BMW_REGEN_REQUEST to 7.0, Pids.BMW_FILTER_FAULTS to 2.0)
        val filter = DashPages.pages(values, trip, emptyList(), bmw = true)[1]
        assertEquals(
            "Petición 7: protección del motor por filtro muy cargado; sin regeneración normal · 481A presente: filtro por encima del máximo",
            filter.status,
        )
        assertNull(filter.alert)
        val advice = Advice("filter_faults", Severity.WARN, "481A: filtro por encima del máximo", "BMW manda unos 45 min")
        val warned = DashPages.pages(values, trip, listOf(advice), bmw = true)
        assertEquals(Severity.WARN, warned[1].alert)
        assertEquals("BMW manda unos 45 min", warned[1].note)
        assertNull(warned[2].alert)
    }

    @Test
    fun `un aviso del aire lleva a la tarjeta del turbo`() {
        val pages = DashPages.pages(emptyMap(), trip, emptyList(), bmw = true)
        assertEquals(2, DashPages.indexOf(pages, DashGroup.AIR))
        assertEquals(1, DashPages.indexOf(pages, DashGroup.FILTER))
    }
}
