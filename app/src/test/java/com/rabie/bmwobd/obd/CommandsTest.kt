package com.rabie.bmwobd.obd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandsTest {

    @Test
    fun `las consultas y los comandos del adaptador pasan`() {
        for (command in listOf("010C", "01 0c", "03", "0902", "0A", "222A0A", "atrv", "AT SH 6F1")) {
            assertTrue(command, Commands.isReadOnly(command))
        }
    }

    @Test
    fun `borrar, activar y escribir no pasan`() {
        for (command in listOf("04", "14FFFFFF", "2E100001", "3101FF00", "1003", "2701", "", "0")) {
            assertFalse(command, Commands.isReadOnly(command))
        }
    }
}
