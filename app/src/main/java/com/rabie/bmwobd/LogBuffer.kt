package com.rabie.bmwobd

/**
 * El registro que se enseña y se comparte. Con la lectura en marcha salen decenas de lineas por
 * segundo, asi que solo se guardan el principio, que es la negociacion con el coche, y lo ultimo.
 * Lo que se escribe entre [pin] y [unpin] se guarda aparte y no se pierde: el resultado de un
 * sondeo o de la lectura de averias, que de otro modo las lecturas siguientes echarian fuera en
 * unos segundos. Se guardan los ultimos [pinnedBlocks] bloques.
 */
class LogBuffer(
    private val headLines: Int = HEAD_LINES,
    private val tailLines: Int = TAIL_LINES,
    private val pinnedBlocks: Int = PINNED_BLOCKS,
) {

    private val head = ArrayList<String>()
    private val blocks = ArrayDeque<ArrayList<String>>()
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

    /** Empieza un bloque que se conserva entero. Si ya hay [pinnedBlocks], se va el mas antiguo. */
    @Synchronized
    fun pin() {
        pinned = ArrayList()
        blocks.addLast(pinned)
        if (blocks.size > pinnedBlocks) blocks.removeFirst()
        pinning = true
        headClosed = true
    }

    @Synchronized
    fun unpin() {
        pinning = false
    }

    /** El ultimo bloque fijado, o el que se esta escribiendo. */
    @Synchronized
    fun pinned(): List<String> = pinned.toList()

    @Synchronized
    fun clear() {
        head.clear()
        blocks.clear()
        pinned = ArrayList()
        tail.clear()
        pinning = false
        headClosed = false
    }

    @Synchronized
    fun lines(): List<String> = head + blocks.flatten() + tail

    private companion object {
        // Caben la negociacion, la busqueda de las medidas propias de la marca y la primera lectura.
        const val HEAD_LINES = 320
        const val TAIL_LINES = 680
        // Las lecturas al conectar, la de averias, un borrado y la relectura, con margen.
        const val PINNED_BLOCKS = 8
    }
}
