package com.rabie.bmwobd.obd

object Commands {

    // Modos OBD y UDS que solo leen: datos en vivo, foto de averia, averias, resultados de
    // pruebas, datos del vehiculo, lectura por identificador y lectura de averias UDS.
    private val READ_ONLY_MODES = setOf("01", "02", "03", "06", "07", "09", "0A", "19", "22")

    /**
     * Si un comando escrito a mano solo consulta. Los AT configuran el adaptador, no el coche. Todo
     * lo demas (borrar averias, activar actuadores, escribir o programar) puede cambiar algo en el
     * coche y solo se deja pasar en modo experto.
     */
    fun isReadOnly(command: String): Boolean {
        val text = command.trim().uppercase().replace(" ", "")
        if (text.startsWith("AT")) return true
        return text.length >= 2 && text.take(2) in READ_ONLY_MODES
    }
}
