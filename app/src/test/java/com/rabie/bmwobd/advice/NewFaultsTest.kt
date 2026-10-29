package com.rabie.bmwobd.advice

import com.rabie.bmwobd.obd.BmwFault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewFaultsTest {

    // 0x61 presente, 0x21 guardada.
    private val oilLevel = BmwFault(0x498E, 0x61)
    private val boostSensor = BmwFault(0x41AB, 0x61)

    @Test
    fun `avisa la averia que aparece en el trayecto y no las que ya estaban`() {
        val faults = NewFaults(listOf(oilLevel))
        assertTrue(faults.read(0, listOf(oilLevel)).isEmpty())
        assertEquals(listOf(boostSensor), faults.read(180_000, listOf(oilLevel, boostSensor)))
        assertEquals(listOf("41AB"), faults.advice(200_000).map { NewFaults.codeOf(it) })
        assertEquals(Severity.ALERT, faults.advice(200_000).single().severity)
        // Se quita a los cinco minutos y no vuelve a avisar en el mismo trayecto.
        assertTrue(faults.advice(500_000).isEmpty())
        assertTrue(faults.read(540_000, listOf(oilLevel, boostSensor)).isEmpty())
    }

    @Test
    fun `se quita cuando deja de estar presente y una guardada no avisa`() {
        val faults = NewFaults(emptyList())
        faults.read(0, listOf(boostSensor))
        faults.read(180_000, listOf(BmwFault(0x41AB, 0x21)))
        assertTrue(faults.advice(200_000).isEmpty())
        assertTrue(faults.read(360_000, listOf(BmwFault(0x3FF1, 0x21))).isEmpty())
    }

    @Test
    fun `sin la lectura al conectar la primera del trayecto hace de punto de partida`() {
        val faults = NewFaults(null)
        assertTrue(faults.read(0, listOf(oilLevel)).isEmpty())
        assertEquals(listOf(boostSensor), faults.read(180_000, listOf(oilLevel, boostSensor)))
    }
}
