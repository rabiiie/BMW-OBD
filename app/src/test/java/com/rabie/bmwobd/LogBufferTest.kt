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
    fun `se guardan los ultimos bloques fijados y una conexion nueva lo borra todo`() {
        val buffer = LogBuffer(headLines = 3, tailLines = 2, pinnedBlocks = 2)
        buffer.add("l1")
        for (block in listOf("primero", "segundo", "tercero")) {
            buffer.pin()
            buffer.add(block)
            buffer.unpin()
        }
        assertEquals(listOf("l1", "segundo", "tercero"), buffer.lines())
        assertEquals(listOf("tercero"), buffer.pinned())
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

    @Test
    fun `lo guardado solo se añade al registro del mismo coche`() {
        val bmw = listOf("## Coche: BMW 440891 · diésel · bastidor oculto", "<< 410C0000")
        val ford = listOf("## Coche: Ford B75665 · diésel · bastidor oculto", "<< 410C0C24")
        val saved = listOf("## Guardado del coche: BMW 440891", "## Saltos sueltos", "## Saltos: fin")
        assertEquals(bmw + listOf("## Saltos sueltos", "## Saltos: fin"), ObdController.withSaved(bmw, saved, "## Saltos: fin"))
        assertEquals(ford, ObdController.withSaved(ford, saved, "## Saltos: fin"))
        // Lo guardado antes de apuntar el coche no se sabe de quien es.
        assertEquals(ford, ObdController.withSaved(ford, saved.drop(1), "## Saltos: fin"))
    }
}
