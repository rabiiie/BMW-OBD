package com.rabie.bmwobd.obd

object ObdParser {

    /**
     * Bytes de datos de una respuesta del modo 01 para el PID dado, o null si ninguna linea
     * es una respuesta valida (NO DATA, SEARCHING..., errores del bus).
     */
    fun dataBytes(response: String, pid: Int): IntArray? =
        payloads(response, "41" + "%02X".format(pid)).firstOrNull { it.isNotEmpty() }

    /** La respuesta trae un mensaje troceado en varias tramas (lineas `0:`, `1:`...). */
    fun isMultiFrame(response: String): Boolean = cleanLines(response).any(::isFrameLine)

    /**
     * La respuesta trae datos del coche, aunque no sean de lo que se pregunto: otra respuesta del
     * modo 01 o un trozo de un mensaje largo. No lo son NO DATA, la interrogacion ni los errores.
     */
    fun isForeignAnswer(response: String): Boolean =
        cleanLines(response).any { isFrameLine(it) || (it.length >= 4 && it.startsWith("41") && it.all(::isHex)) }

    private fun cleanLines(response: String): List<String> =
        response.lines().map { it.replace(" ", "").uppercase() }.filter { it.isNotEmpty() }

    private fun isFrameLine(line: String): Boolean = line.length > 2 && line[1] == ':' && isHex(line[0])

    /**
     * Los bytes que siguen a [prefix] en cada mensaje de la respuesta. Un mensaje corto es una
     * linea. Uno largo llega troceado: primero su longitud en tres cifras hexadecimales y luego
     * los trozos numerados (`0:`, `1:`...), con relleno al final que se descarta.
     */
    fun payloads(response: String, prefix: String): List<IntArray> {
        val result = mutableListOf<IntArray>()
        val pieces = StringBuilder()
        var declaredBytes: Int? = null

        fun add(message: String) {
            if (!message.startsWith(prefix)) return
            val body = message.substring(prefix.length)
            if (body.length % 2 != 0 || !body.all(::isHex)) return
            result += IntArray(body.length / 2) { body.substring(it * 2, it * 2 + 2).toInt(16) }
        }

        fun flush() {
            if (pieces.isNotEmpty()) {
                val limit = declaredBytes?.let { it * 2 } ?: pieces.length
                add(pieces.substring(0, minOf(limit, pieces.length)))
                pieces.setLength(0)
            }
            declaredBytes = null
        }

        for (raw in response.lines()) {
            val line = raw.replace(" ", "").uppercase()
            when {
                line.isEmpty() -> Unit
                line.length == 3 && line.all(::isHex) -> {
                    flush()
                    declaredBytes = line.toInt(16)
                }
                line.length > 2 && line[1] == ':' && isHex(line[0]) -> pieces.append(line, 2, line.length)
                else -> {
                    flush()
                    add(line)
                }
            }
        }
        flush()
        return result
    }

    /**
     * PIDs soportados que anuncia una respuesta a 0100, 0120, 0140...: 32 bits, el primero
     * (el mas significativo) es el PID base + 1.
     */
    fun supportedFrom(base: Int, data: IntArray): Set<Int> {
        if (data.size < 4) return emptySet()
        val supported = mutableSetOf<Int>()
        for (bit in 0 until 32) {
            if ((data[bit / 8] shr (7 - bit % 8)) and 1 == 1) supported += base + bit + 1
        }
        return supported
    }

    private fun isHex(c: Char) = c in '0'..'9' || c in 'A'..'F'
}
