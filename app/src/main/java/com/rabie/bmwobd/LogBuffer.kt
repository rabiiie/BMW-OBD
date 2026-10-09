package com.rabie.bmwobd

/**
 * El registro que se enseña y se comparte. Con la lectura en marcha salen decenas de lineas por
 * segundo, asi que solo se guardan el principio, que es la negociacion con el coche, y lo ultimo.
 * Lo que se escribe entre [pin] y [unpin] se guarda aparte y no se pierde: es el resultado de un
 * sondeo, que de otro modo las lecturas siguientes echarian fuera en unos segundos.
 */
class LogBuffer(private val headLines: Int = HEAD_LINES, private val tailLines: Int = TAIL_LINES) {

    private val head = ArrayList<String>()
    private var pinned = ArrayList<String>()
    private val tail = ArrayDeque<String>()
    private var pinning = false
    private var headClosed = false

    @Synchronized
    fun add(line: String): List<String> {
        when {
            pinning -> pinned.add(line)
            !headClosed && head.size < headLines -> head.add(line)
            else -> {
                tail.addLast(line)
                if (tail.size > tailLines) tail.removeFirst()
            }
        }
        return lines()
    }

    /** Empieza un bloque que se conserva entero. Sustituye al bloque anterior. */
    @Synchronized
    fun pin() {
        pinned = ArrayList()
        pinning = true
        headClosed = true
    }

    @Synchronized
    fun unpin() {
        pinning = false
    }

    @Synchronized
    fun clear() {
        head.clear()
        pinned = ArrayList()
        tail.clear()
        pinning = false
        headClosed = false
    }

    @Synchronized
    fun lines(): List<String> = head + pinned + tail

    private companion object {
        const val HEAD_LINES = 120
        const val TAIL_LINES = 680
    }
}
