package com.rabie.bmwobd

import com.rabie.bmwobd.ui.LogExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogBufferTest {

    @Test
    fun `se conservan el principio y lo ultimo`() {
        val buffer = LogBuffer(headLines = 3, tailLines = 2)
        for (i in 1..10) buffer.add("l$i")
        assertEquals(listOf("l1", "l2", "l3", "l9", "l10"), buffer.lines())
    }

    @Test
    fun `el sondeo no lo echan fuera las lecturas que vienen despues`() {
        val buffer = LogBuffer(headLines = 3, tailLines = 2)
        for (i in 1..10) buffer.add("l$i")
        buffer.pin()
        buffer.add("## Sondeo: inicio")
        buffer.add("## Sondeo: fin")
        buffer.unpin()
        for (i in 11..500) buffer.add("l$i")
        assertEquals(
            listOf("l1", "l2", "l3", "## Sondeo: inicio", "## Sondeo: fin", "l499", "l500"),
            buffer.lines(),
        )
    }

    @Test
    fun `un sondeo nuevo sustituye al anterior y una conexion nueva lo borra todo`() {
        val buffer = LogBuffer(headLines = 3, tailLines = 2)
        buffer.add("l1")
        buffer.pin()
        buffer.add("primero")
        buffer.unpin()
        buffer.pin()
        buffer.add("segundo")
        buffer.unpin()
        assertEquals(listOf("l1", "segundo"), buffer.lines())
        buffer.clear()
        assertTrue(buffer.lines().isEmpty())
    }

    @Test
    fun `el registro que se comparte no lleva el bastidor`() {
        val shared = LogExport.withoutVin(
            listOf(
                ">> 0902",
                "<< 014 | 0: 490201574241 | 1: 55443731305830 | 2: 50343430383931",
                "## Coche: BMW 440891 · diésel · bastidor WBAXXXXXXXXXXXXXX",
                ">> 010C",
                "<< 410C0000",
            ),
        )
        assertEquals("<< (bastidor oculto)", shared[1])
        assertEquals("## Coche: BMW 440891 · diésel · bastidor oculto", shared[2])
        assertEquals("<< 410C0000", shared[4])
        assertFalse(shared.any { it.contains("490201") || it.contains("WBA") })
    }
}
