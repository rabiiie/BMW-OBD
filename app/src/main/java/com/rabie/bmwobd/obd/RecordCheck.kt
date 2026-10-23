package com.rabie.bmwobd.obd

/** Una linea de la comprobacion: que se ha leido, si esta completo y el detalle. */
data class CheckLine(val label: String, val ok: Boolean, val detail: String)

/**
 * Lo que la app comprueba de la ultima lectura antes de dar el registro por completo y de dejar
 * borrar: que se leyeron las tres memorias de BMW, que llego el detalle de cada averia y aviso, y
 * que las lecturas de servicio se hicieron todas, con los datos del filtro de ISTA traducidos.
 */
object RecordCheck {

    fun lines(report: DiagnosticsReport?, service: List<ServiceRead>?): List<CheckLine> {
        val out = ArrayList<CheckLine>()
        if (report != null) {
            out += memory("Memoria de averías", report.bmwFaults, "averías")
            out += memory("Memoria de información", report.bmwInfo, "avisos")
            out += memory("Historial", report.bmwHistory, "entradas")
            val codes = codes(report)
            val missing = missingDetails(report)
            out += CheckLine(
                "Detalle de cada avería y aviso",
                missing.isEmpty(),
                if (missing.isEmpty()) "${codes.size} de ${codes.size}"
                else "${codes.size - missing.size} de ${codes.size}; falta " + missing.joinToString(", ") { "%04X".format(it) },
            )
        }
        if (service != null) {
            val total = BmwService.QUERIES.size
            val answered = service.count { it.data != null || it.refusal != null }
            out += CheckLine(
                "Lecturas de servicio",
                service.size == total,
                if (service.size == total) "$total pedidas, $answered contestadas" else "${service.size} de $total pedidas",
            )
            val filter = service.firstOrNull { it.query.query == BmwService.FILTER_QUERY.query }
            val translated = filter?.fieldTexts.orEmpty().isNotEmpty()
            out += CheckLine("Datos del filtro de ISTA traducidos", translated, if (translated) "sí" else "no han contestado")
        }
        return out
    }

    /** Todos los codigos de averias y avisos de la lectura, sin repetir. */
    fun codes(report: DiagnosticsReport): List<Int> =
        (report.bmwFaults.orEmpty() + report.bmwInfo.orEmpty()).map { it.code }.distinct()

    /** Los codigos de la lectura cuyo detalle no llego. */
    fun missingDetails(report: DiagnosticsReport): List<Int> = codes(report).filter { it !in report.bmwDetailed }

    private fun memory(label: String, list: List<BmwFault>?, unit: String) =
        CheckLine(label, list != null, if (list == null) "no ha contestado" else "${list.size} $unit")
}
