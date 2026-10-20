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
            "Petición 7: protección: filtro muy cargado, sin regeneración normal · 481A presente: filtro por encima del máximo",
            filter.status,
        )
        assertNull(filter.alert)
        val advice = Advice("filter_faults", Severity.WARN, "481A: filtro por encima del máximo", "BMW manda unos 45 min")
        val warned = DashPages.pages(values, trip, listOf(advice), bmw = true)
        assertEquals(Severity.WARN, warned[1].alert)
        assertEquals("BMW manda unos 45 min", warned[1].note)
        assertNull(warned[2].alert)
        // Con el aviso tapando la linea, la peticion sigue en su celda.
        assertEquals(DashItem("Regeneración", "7", "protección"), warned[1].cells[0])
        assertEquals(DashItem("Avería filtro", "481A", ""), warned[1].cells[1])
        // Diez celdas: cinco filas completas.
        assertEquals(11, warned[1].cells.size)
        val preparing = DashPages.pages(mapOf(Pids.BMW_REGEN_REQUEST to 6.0, Pids.BMW_FILTER_FAULTS to 0.0), trip, emptyList(), bmw = true)[1]
        assertEquals(DashItem("Regeneración", "6", "preparando"), preparing.cells[0])
        assertEquals(DashItem("Avería filtro", "ninguna", ""), preparing.cells[1])
        // Con el 5 solo dice calentando si el motor esta en modo de regeneracion; si no, en pausa y por que.
        fun regen(mode: Int, pause: Double?) = DashPages.pages(
            mapOf(Pids.BMW_REGEN_REQUEST to 5.0, Pids.BMW_ENGINE_MODE to mode.toDouble()) +
                listOfNotNull(pause?.let { Pids.BMW_REGEN_INTERRUPTION to it }),
            trip, emptyList(), bmw = true,
        )[1].cells[0].unit
        assertEquals("calentando", regen(Pids.MODE_REGENERATING, 0.0))
        assertEquals("pausa · temp.", regen(Pids.MODE_NORMAL, 2.0))
        assertEquals("en pausa", regen(Pids.MODE_NORMAL, 4.0))
        assertEquals("en pausa", regen(Pids.MODE_NORMAL, 6.0))
        assertEquals("en pausa", regen(Pids.MODE_NORMAL, null))
    }

    @Test
    fun `un aviso del aire lleva a la tarjeta del turbo`() {
        val pages = DashPages.pages(emptyMap(), trip, emptyList(), bmw = true)
        assertEquals(2, DashPages.indexOf(pages, DashGroup.AIR))
        assertEquals(1, DashPages.indexOf(pages, DashGroup.FILTER))
    }
}
