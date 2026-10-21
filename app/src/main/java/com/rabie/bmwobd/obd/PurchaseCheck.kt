package com.rabie.bmwobd.obd

import java.util.Locale

/** Una frase de la revision de compra; [warn] si es algo que mirar antes de comprar. */
data class PurchaseLine(val text: String, val warn: Boolean = false)

/**
 * Lo que una lectura de averias le dice a quien mira un coche para comprarlo. Todo sale del OBD
 * estandar (SAE J1979), asi que vale para cualquier marca. Al borrar las averias (modo 04) la
 * centralita pone a cero los contadores desde el borrado y vuelve a empezar sus autocomprobaciones:
 * un borrado reciente se ve en los dos sitios. Una reprogramacion, en cambio, no deja rastro aqui.
 */
object PurchaseCheck {

    const val KM_SINCE_CLEAR = 0x31
    const val WARMUPS_SINCE_CLEAR = 0x30
    const val MINUTES_SINCE_CLEAR = 0x4E
    const val KM_WITH_MIL = 0x21
    const val FILTER_PRESSURE_PID = 0x7A

    /** Los contadores que se leen con las averias: los que se ponen a cero al borrar y el cuentakilometros. */
    val COUNTERS = listOf(KM_SINCE_CLEAR, WARMUPS_SINCE_CLEAR, MINUTES_SINCE_CLEAR, KM_WITH_MIL, Pids.ODOMETER)

    // Los topes de los contadores: dos bytes los km y los minutos, uno los calentamientos.
    private const val WORD_TOP = 65535.0
    private const val BYTE_TOP = 255.0
    private const val MINUTES_PER_HOUR = 60.0

    fun lines(report: DiagnosticsReport): List<PurchaseLine> = listOfNotNull(
        cleared(report),
        report.counters[KM_WITH_MIL]?.takeIf { it > 0 }?.let {
            PurchaseLine("Se han hecho ${thousands(it)} km con el testigo de avería encendido.", warn = true)
        },
        faults(report),
        report.permanent?.takeIf { it.isNotEmpty() }?.let {
            PurchaseLine(
                "${it.size} avería(s) permanente(s): no se borran con un lector. La centralita las quita sola " +
                    "cuando comprueba que el fallo ya no está.",
                warn = true,
            )
        },
        PurchaseLine(
            report.vin?.let { "Bastidor que da la centralita: $it. Tiene que coincidir con los papeles y con el grabado en el chasis." }
                ?: "La centralita no da el bastidor.",
        ),
        PurchaseLine(
            report.counters[Pids.ODOMETER]?.let { "Cuentakilómetros según la centralita: ${thousands(it)} km. Tiene que coincidir con el del cuadro." }
                ?: "Este coche no da el cuentakilómetros por OBD, lo normal en coches antiguos: los km se comprueban con facturas e inspecciones.",
        ),
        filter(report),
    )

    private fun cleared(report: DiagnosticsReport): PurchaseLine? {
        val pending = report.readiness?.pending.orEmpty()
        val unfinished = if (pending.isEmpty()) null else "Faltan ${pending.size} autocomprobaciones por terminar (${pending.joinToString { it.name.lowercase() }})"
        val km = report.counters[KM_SINCE_CLEAR]
            ?: return unfinished?.let { PurchaseLine("$it. Pasa tras borrar averías o desconectar la batería.", warn = true) }
        if (km >= WORD_TOP) return PurchaseLine("Las averías no se han borrado en los últimos 65.535 km: el contador está en su tope.")
        val since = listOfNotNull(
            "${thousands(km)} km",
            report.counters[WARMUPS_SINCE_CLEAR]?.let { if (it >= BYTE_TOP) "255 o más arranques en caliente" else "${thousands(it)} arranques en caliente" },
            report.counters[MINUTES_SINCE_CLEAR]?.takeIf { it < WORD_TOP }?.let { "${thousands(it / MINUTES_PER_HOUR)} h de motor" },
        ).joinToString(", ")
        return PurchaseLine(
            "Averías borradas hace $since. " +
                (unfinished?.let { "$it: encaja con un borrado reciente." } ?: "Las autocomprobaciones ya han terminado."),
            warn = unfinished != null,
        )
    }

    private fun faults(report: DiagnosticsReport): PurchaseLine? {
        val stored = report.stored ?: return null
        val pending = report.pending.orEmpty()
        if (stored.isEmpty() && pending.isEmpty()) return PurchaseLine("Sin averías guardadas ni pendientes.")
        return PurchaseLine("${stored.size} avería(s) guardada(s) y ${pending.size} pendiente(s): están más abajo.", warn = true)
    }

    private fun filter(report: DiagnosticsReport): PurchaseLine? {
        if (report.readiness?.diesel != true) return null
        return PurchaseLine(
            if (report.filterPressure) {
                "Da la presión del filtro de partículas: en marcha, en el panel, tiene que subir al acelerar. Si se queda " +
                    "casi plana con mucho caudal, puede faltar el filtro."
            } else {
                "No da la presión del filtro de partículas por OBD: el filtro solo se comprueba con el lector de la marca o mirándolo por debajo."
            },
        )
    }

    private fun thousands(value: Double) = String.format(Locale.US, "%,d", Math.round(value)).replace(',', '.')
}
