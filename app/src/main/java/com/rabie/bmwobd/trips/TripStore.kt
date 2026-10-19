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
    val timing: TimingOffset? = null,
    val wear: Wear? = null,
    val margin: Margin? = null,
    val health: TripHealth? = null,
    val fuel: TripFuel? = null,
)

/** Los trayectos grabados: un CSV por trayecto en la carpeta privada de la app. */
class TripStore(private val dir: File, private val isDiesel: (vehicleKey: String?) -> Boolean = { true }) {

    /** El nombre lleva la fecha y, detras, la clave del coche: `trip_20261006_081500_<clave>.csv`. */
    fun newFile(simulated: Boolean, vehicleKey: String, now: Date = Date()): File {
        dir.mkdirs()
        val prefix = if (simulated) SIMULATED_PREFIX else REAL_PREFIX
        return File(dir, prefix + stamp().format(now) + "_" + vehicleKey + EXTENSION)
    }

    /**
     * Del mas reciente al mas antiguo. Lo que se calcula de cada trayecto se guarda al lado de su
     * CSV la primera vez: la lista ya no relee los CSV enteros, solo los nuevos o los que cambiaron.
     */
    fun list(): List<TripEntry> {
        val files = dir.listFiles { file -> file.name.endsWith(EXTENSION) } ?: return emptyList()
        return files.map { file ->
            val vehicleKey = file.name.removeSuffix(EXTENSION).split('_').getOrNull(3)
            val diesel = isDiesel(vehicleKey)
            val summary = readSummary(file, diesel) ?: summarize(load(file), diesel).also { writeSummary(file, it) }
            TripEntry(
                file = file,
                startMillis = startOf(file),
                simulated = file.name.startsWith(SIMULATED_PREFIX),
                vehicleKey = vehicleKey,
                stats = summary.stats,
                timing = summary.timing,
                wear = summary.wear,
                margin = summary.margin,
                health = summary.health,
                fuel = summary.fuel,
            )
        }.sortedByDescending { it.startMillis }
    }

    fun load(file: File): TripData = file.useLines { TripCsv.parse(it) }

    fun delete(file: File) {
        file.delete()
        summaryFile(file).delete()
    }

    /** Lo que la lista y los resumenes necesitan de un trayecto. */
    private class Summary(
        val diesel: Boolean,
        val stats: TripStats,
        val timing: TimingOffset?,
        val wear: Wear?,
        val margin: Margin?,
        val health: TripHealth?,
        val fuel: TripFuel?,
    ) : java.io.Serializable {
        private companion object {
            private const val serialVersionUID = 1L
        }
    }

    private fun summarize(data: TripData, diesel: Boolean) = Summary(
        diesel = diesel,
        stats = TripCsv.stats(data, diesel),
        timing = TripCsv.timing(data),
        wear = TripCsv.wear(data),
        margin = TripCsv.margin(data),
        health = TripHealth.of(data),
        fuel = TripFuel.of(data, diesel),
    )

    private fun summaryFile(file: File) = File(file.parentFile, file.name.removeSuffix(EXTENSION) + SUMMARY_EXTENSION)

    /**
     * El resumen guardado, si es del mismo CSV y de la misma version del calculo. Si no se puede
     * leer (otra version de la app, fichero a medias), se vuelve a calcular.
     */
    private fun readSummary(file: File, diesel: Boolean): Summary? {
        val saved = summaryFile(file)
        if (!saved.exists() || saved.lastModified() < file.lastModified()) return null
        return runCatching {
            java.io.ObjectInputStream(saved.inputStream().buffered()).use { input ->
                if (input.readInt() != SUMMARY_VERSION) return null
                input.readObject() as Summary
            }
        }.getOrNull()?.takeIf { it.diesel == diesel }
    }

    private fun writeSummary(file: File, summary: Summary) {
        runCatching {
            java.io.ObjectOutputStream(summaryFile(file).outputStream().buffered()).use { output ->
                output.writeInt(SUMMARY_VERSION)
                output.writeObject(summary)
            }
        }
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
        const val SUMMARY_EXTENSION = ".sum"

        // Subirla cuando cambie lo que se calcula de un trayecto: los resumenes guardados se rehacen.
        const val SUMMARY_VERSION = 2
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
