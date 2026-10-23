package com.rabie.bmwobd.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MsaTest {

    @Test
    fun `desactivar el arranque y parada y devolverlo con las ordenes de BMW`() = runBlocking<Unit> {
        val sent = mutableListOf<String>()
        val session = ObdSession(SimulatedTransport(latencyMs = 0)) { line -> if (line.startsWith(">> ")) sent += line.removePrefix(">> ") }
        session.connect()
        assertEquals(MsaResult(accepted = true, refusal = null, state = 1), session.msa(disable = true))
        assertEquals(MsaResult(accepted = true, refusal = null, state = 0), session.msa(disable = false))
        // Las de STEUERN_MSA_DEAK, STEUERN_ENDE_MSA_DEAK y STATUS_MSA_DEAK del d70n47b0, sacadas con la traza.
        assertEquals(listOf("2E5F8E07", "225F8E", "2E5F8E00", "225F8E"), sent.filter { it.startsWith("2E5F8E") || it == "225F8E" })
    }
}
