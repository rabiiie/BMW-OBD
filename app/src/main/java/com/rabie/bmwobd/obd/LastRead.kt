package com.rabie.bmwobd.obd

import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/** La ultima lectura de averias que se hizo: todo lo leido, cuando y de que coche. */
data class SavedRead(
    val report: DiagnosticsReport,
    val manipulation: ManipulationReport?,
    val atMillis: Long,
    val vehicle: String,
) : java.io.Serializable

/**
 * Guarda en el movil la ultima lectura de averias y la devuelve al abrir la app; cada lectura
 * nueva sustituye a la anterior. Si el formato cambia con una version nueva de la app, la guardada
 * no se puede leer y se descarta sin mas.
 */
class LastReadStore(private val file: File) {

    fun save(read: SavedRead) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            ObjectOutputStream(temp.outputStream().buffered()).use { it.writeObject(read) }
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        }
    }

    fun load(): SavedRead? = runCatching {
        if (!file.exists()) return null
        ObjectInputStream(file.inputStream().buffered()).use { it.readObject() as? SavedRead }
    }.getOrNull()
}
