package com.rabie.bmwobd.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaultEnvironmentTest {

    private fun payload(text: String) = ObdParser.payloads(text, BmwFault.DETAIL_ANSWER).last()

    private fun FaultSnapshot.value(name: String) = values.first { it.name == name }

    @Test
    fun `el detalle de 4D00 trae la primera y la ultima vez con las condiciones del filtro`() {
        // El detalle de 4D00 que dio el coche el 14 de octubre.
        val back = "058\n0: 57014D002100\n1: 00000428B92311\n2: A784A585B68F8A\n3: 9B103800100001\n4: DDDDDB07390F83\n" +
            "5: 0304FF000480FF\n6: 007D487F057362\n7: 7AB93818CE81C6\n8: 88A2868B98103B\n9: 00210002E7E7E5\nA: 07510F83071FF2\n" +
            "B: 00071598005F48\nC: EE0575AF51AAAA"
        val shots = FaultEnvironment.snapshots(payload(back), 0x4D00)
        assertEquals(2, shots.size)

        val first = shots[0]
        assertEquals(379_160, first.km)
        assertEquals(2259.5, first.value("Revoluciones").value, 0.01)
        assertEquals(92.0, first.value("Refrigerante").value, 0.01)
        assertEquals(52.2, first.value("Gasóleo pedido").value, 0.1)
        assertEquals("0x00100001", first.value("Modo del motor (regeneración)").text)
        assertEquals(39.71, first.value("Hollín medido, corregido").value, 0.01)
        assertEquals("295,2 km", first.value("Distancia desde la última regeneración").text)
        assertEquals(21.3, first.value("Ceniza calculada").value, 0.05)

        val last = shots[1]
        assertEquals(379_328, last.km)
        assertEquals("0x00210002", last.value("Modo del motor (regeneración)").text)
        assertEquals("464,3 km", last.value("Distancia desde la última regeneración").text)
        assertTrue(last.values.all { it.translated })
    }

    @Test
    fun `el del sensor de aceite usa el grupo general y una respuesta de otra averia no vale`() {
        val oil = "04A\n0: 5701498E6100\n1: 0000FF01AE0500\n2: 005F0000003C7D\n3: 91040000004719\n4: 000000285C2100\n" +
            "5: 00801000015300\n6: FFB91206CF5A2B\n7: 1C293C89901000\n8: 00524AE664000C\n9: 28461C00000010\nA: 00014E00CAAAAA"
        val shots = FaultEnvironment.snapshots(payload(oil), 0x498E)
        assertEquals(356_392, shots.first().km)
        assertTrue(shots.first().values.any { it.name == "Velocidad" })
        assertEquals(emptyList<FaultSnapshot>(), FaultEnvironment.snapshots(payload(oil), 0x4D00))
    }
}
