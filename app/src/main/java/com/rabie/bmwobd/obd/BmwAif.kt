package com.rabie.bmwobd.obd

/**
 * El campo AIF de la centralita del motor: lo que deja escrito cada programacion de taller
 * (numero de montaje y de programa, fecha, km, concesionario y bastidor). La consulta es la de
 * AIF_LESEN de d70n47b0, sacada con la traza del .prg: 23 00 00 00 07 40, ReadMemoryByAddress.
 * Como lleva el bastidor, la respuesta no se guarda tal cual: se tapa el bastidor del coche, entero
 * y sus 7 ultimos caracteres, y se apunta si estaba.
 */
object BmwAif {

    const val QUERY = "230000000740"
    const val ANSWER = "63"

    private const val MASK = 0x2A
    private const val SHORT_VIN = 7

    /** La respuesta ya tapada, en hexadecimal, y si el bastidor del coche estaba (null si no se sabe el del coche). */
    class Read(val hex: String, val vinFound: Boolean?) {
        val vinText: String
            get() = when (vinFound) {
                true -> "coincide con el del coche (tapado en la respuesta)"
                false -> "no aparece el de este coche"
                null -> "no se puede comparar: no se leyó el bastidor del coche"
            }
    }

    /**
     * Tapa en [data] el bastidor [vin], entero y sus 7 ultimos caracteres, en ASCII. Null si no hay
     * respuesta. Sin bastidor del coche no se puede tapar nada: no se da la respuesta.
     */
    fun masked(data: IntArray?, vin: String?): Read? {
        if (data == null) return null
        if (vin.isNullOrBlank()) return Read("(sin enseñar: no se leyó el bastidor del coche para taparlo)", null)
        val bytes = data.copyOf()
        var found = false
        for (target in listOf(vin, vin.takeLast(SHORT_VIN))) {
            val pattern = target.map { it.code }
            var i = 0
            while (i + pattern.size <= bytes.size) {
                if ((pattern.indices).all { bytes[i + it] == pattern[it] }) {
                    for (k in pattern.indices) bytes[i + k] = MASK
                    found = true
                    i += pattern.size
                } else {
                    i++
                }
            }
        }
        return Read(bytes.joinToString(" ") { "%02X".format(it) }, found)
    }
}
