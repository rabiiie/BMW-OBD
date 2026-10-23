package com.rabie.bmwobd.tests

import com.rabie.bmwobd.obd.FanTest
import com.rabie.bmwobd.obd.ServiceStep
import com.rabie.bmwobd.obd.TestOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TestRecordTest {

    @Test
    fun `un resultado guardado se lee igual, con los pasos y si se oyo`() {
        val test = FanTest(
            "Actuador del turbo",
            listOf(ServiceStep("Al poner 20 %", "30C6070666", "70C607", "aceptado")),
            accepted = true,
            released = true,
            confirmed = true,
        )
        val record = TestRecord.of(test, "hecho", atMillis = 1_761_200_000_000).copy(heard = true)
        val back = TestRecord.decode(record.encode())
        assertEquals(record, back)
        assertEquals(TestOutcome.OK, back?.outcome)
        assertEquals(listOf("Al poner 20 %: aceptado (30C6070666 → 70C607)"), back?.steps)
    }

    @Test
    fun `un texto que no es un resultado no se lee`() {
        assertNull(TestRecord.decode("cualquier cosa"))
    }
}
