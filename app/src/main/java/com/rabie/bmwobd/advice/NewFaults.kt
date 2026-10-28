package com.rabie.bmwobd.advice

import com.rabie.bmwobd.obd.BmwFault

/**
 * Las averias que pasan a presentes durante el trayecto. Las que ya lo estaban al empezar no
 * avisan: se conocen y salen en cada lectura. Cada una avisa una vez por trayecto, hasta que deja
 * de estar presente o pasan [SHOW_MS].
 */
class NewFaults(atStart: List<BmwFault>?) {

    private var known: MutableSet<Int>? = atStart?.filter { it.active }?.map { it.code }?.toMutableSet()
    private val since = LinkedHashMap<Int, Pair<Long, BmwFault>>()

    /** Apunta una lectura de la memoria de averias. Devuelve las que acaban de aparecer. */
    fun read(tMs: Long, faults: List<BmwFault>): List<BmwFault> {
        val present = faults.filter { it.active }
        val codes = present.map { it.code }.toSet()
        // Si no se pudo leer al conectar, la primera lectura hace de punto de partida.
        val seen = known ?: run {
            known = codes.toMutableSet()
            return emptyList()
        }
        since.keys.retainAll(codes)
        val appeared = present.filter { it.code !in seen }
        for (fault in appeared) {
            since[fault.code] = tMs to fault
            seen += fault.code
        }
        return appeared
    }

    fun advice(tMs: Long): List<Advice> = since.values
        .filter { (start, _) -> tMs - start < SHOW_MS }
        .map { (_, fault) ->
            Advice(
                "fault_${fault.label}", Severity.ALERT, "Avería nueva: ${fault.label}",
                (fault.text ?: "La app no tiene su texto.") + ". Está presente ahora y no lo estaba al empezar el trayecto.",
            )
        }

    private companion object {
        const val SHOW_MS = 5 * 60_000L
    }
}
