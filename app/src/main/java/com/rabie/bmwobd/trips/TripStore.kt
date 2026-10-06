package com.rabie.bmwobd.trips

import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TripEntry(
    val file: File,
    val startMillis: Long,
    val simulated: Boolean,
    val vehicleKey: String?,
    val stats: TripStats,
)

/** Los trayectos grabados: un CSV por trayecto en la carpeta privada de la app. */
class TripStore(private val dir: File) {

    /** El nombre lleva la fecha y, detras, la clave del coche: `trip_20261006_081500_<clave>.csv`. */
    fun newFile(simulated: Boolean, vehicleKey: String, now: Date = Date()): File {
        dir.mkdirs()
        val prefix = if (simulated) SIMULATED_PREFIX else REAL_PREFIX
        return File(dir, prefix + stamp().format(now) + "_" + vehicleKey + EXTENSION)
    }

    /** Del mas reciente al mas antiguo. */
    fun list(): List<TripEntry> {
        val files = dir.listFiles { file -> file.name.endsWith(EXTENSION) } ?: return emptyList()
        return files.map { file ->
            TripEntry(
                file = file,
                startMillis = startOf(file),
                simulated = file.name.startsWith(SIMULATED_PREFIX),
                vehicleKey = file.name.removeSuffix(EXTENSION).split('_').getOrNull(3),
                stats = TripCsv.stats(load(file)),
            )
        }.sortedByDescending { it.startMillis }
    }

    fun load(file: File): TripData = file.useLines { TripCsv.parse(it) }

    fun delete(file: File) {
        file.delete()
    }

    private fun startOf(file: File): Long {
        val parts = file.name.removeSuffix(EXTENSION).split('_')
        val text = parts.drop(1).take(2).joinToString("_")
        return runCatching { stamp().parse(text)?.time }.getOrNull() ?: file.lastModified()
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private companion object {
        const val REAL_PREFIX = "trip_"
        const val SIMULATED_PREFIX = "sim_"
        const val EXTENSION = ".csv"
    }
}

/** Escribe un trayecto fila a fila. Al cerrar, un trayecto demasiado corto se borra. */
class TripRecorder(private val file: File, private val columns: List<Int>) : Closeable {

    private val writer: BufferedWriter = file.bufferedWriter()
    private val startNanos = System.nanoTime()
    private var rows = 0

    init {
        writer.write(TripCsv.header(columns))
        writer.newLine()
    }

    fun append(values: Map<Int, Double>) {
        val tMs = (System.nanoTime() - startNanos) / 1_000_000
        writer.write(TripCsv.row(tMs, columns, values))
        writer.newLine()
        rows++
        if (rows % FLUSH_EVERY == 0) writer.flush()
    }

    override fun close() {
        runCatching { writer.close() }
        if (rows < MIN_ROWS) file.delete()
    }

    private companion object {
        const val FLUSH_EVERY = 10
        const val MIN_ROWS = 20
    }
}
