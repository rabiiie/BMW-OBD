package com.rabie.bmwobd.trips

import com.rabie.bmwobd.obd.Pids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TripStoreTest {

    private fun trip(dir: File, store: TripStore, speed: Double): File {
        val file = store.newFile(simulated = false, vehicleKey = "abc")
        TripRecorder(file, listOf(Pids.RPM, Pids.SPEED)).use { recorder ->
            repeat(40) { recorder.append(mapOf(Pids.RPM to 2000.0, Pids.SPEED to speed)) }
        }
        return file
    }

    @Test
    fun `el resumen se guarda al lado del trayecto y se reutiliza`() {
        val dir = Files.createTempDirectory("trips").toFile()
        val store = TripStore(dir)
        val file = trip(dir, store, 80.0)
        val first = store.list().single()
        val saved = File(dir, file.name.removeSuffix(".csv") + ".sum")
        assertTrue(saved.exists())
        // Con el resumen guardado la lista no necesita el CSV: aunque se vacie, sale lo mismo.
        val stamp = file.lastModified()
        file.writeText("t_ms\n")
        file.setLastModified(stamp)
        saved.setLastModified(stamp + 1000)
        assertEquals(first.stats, store.list().single().stats)
    }

    @Test
    fun `un trayecto que cambia se vuelve a resumir y al borrarlo se va su resumen`() {
        val dir = Files.createTempDirectory("trips").toFile()
        val store = TripStore(dir)
        val file = trip(dir, store, 80.0)
        val before = store.list().single().stats
        val saved = File(dir, file.name.removeSuffix(".csv") + ".sum")
        saved.setLastModified(file.lastModified() - 10_000)
        assertEquals(before, store.list().single().stats)
        store.delete(file)
        assertFalse(saved.exists())
        assertTrue(store.list().isEmpty())
    }
}
