package com.rabie.bmwobd.obd

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastReadStoreTest {

    @Test
    fun `la ultima lectura se guarda entera, se lee igual y la nueva sustituye a la anterior`() = runBlocking<Unit> {
        val session = ObdSession(SimulatedTransport(latencyMs = 0)) {}
        session.connect()
        val report = session.diagnostics(bmw = true)
        val manipulation = ManipulationReport(listOf(ManipulationReport.Entry(0, 150_000.0, 0.0)), 0.0, 0.0, 3, 1.0, 380_000.0)
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val store = LastReadStore(File(dir, "ultima.bin"))
        assertNull(store.load())

        store.save(SavedRead(report, manipulation, 1_000L, "118d"))
        assertEquals(SavedRead(report, manipulation, 1_000L, "118d"), store.load())

        store.save(SavedRead(report.copy(stored = listOf("P0401")), null, 2_000L, "118d"))
        val again = store.load()!!
        assertEquals(2_000L, again.atMillis)
        assertEquals(listOf("P0401"), again.report.stored)
        assertNull(again.manipulation)
        dir.deleteRecursively()
    }

    @Test
    fun `un fichero que no se puede leer se descarta`() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val file = File(dir, "ultima.bin").apply { writeText("no es una lectura") }
        assertNull(LastReadStore(file).load())
        dir.deleteRecursively()
    }
}
