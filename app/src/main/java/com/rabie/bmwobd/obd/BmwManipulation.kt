package com.rabie.bmwobd.obd

import java.util.Locale

/**
 * Lo que guarda la centralita del N47 para detectar manipulaciones (MESSWERTETAB de d70n47b0):
 * un historial de 20 anotaciones con la "Manipulationswahrscheinlichkeit" (ManDet_HistTw, en %) y
 * el cuentakilometros de cada una (ManDet_HistKm, de 10 en 10 km), la "Tuningwahrscheinlichkeit"
 * actual y la maxima (Tw_noagr, Tw_noagrmax) con su contador, y la desviacion media del oxigeno en
 * la que se apoya (ManDet_rO2AdapFaultMean). BMW no dice como calcula esas probabilidades ni desde
 * que valor las da por manipulacion: se ensenan tal cual.
 */
data class ManipulationReport(
    /** Las anotaciones con dato, en el orden del anillo de la centralita (no sabemos cual es la ultima). */
    val entries: List<Entry>,
    val tuningNow: Double?,
    val tuningMax: Double?,
    val tuningCount: Long?,
    val oxygenDeviation: Double?,
    /** El cuentakilometros de la propia centralita, para comparar con el historial. */
    val odometer: Double?,
) {
    data class Entry(val slot: Int, val km: Double, val probability: Double?)

    /** Las anotaciones que dicen un km mayor que el cuentakilometros actual. */
    val aheadOfOdometer: List<Entry>
        get() = odometer?.let { now -> entries.filter { it.km > now + KM_STEP } }.orEmpty()

    val suspicious: List<Entry> get() = entries.filter { (it.probability ?: 0.0) > 0.0 }

    fun lines(): List<PurchaseLine> {
        val out = ArrayList<PurchaseLine>()
        if (entries.isEmpty()) {
            out += PurchaseLine("El historial de manipulación de la centralita está vacío.")
        } else {
            val sorted = entries.sortedBy { it.km }
            out += PurchaseLine(
                "Historial de la centralita: ${entries.size} anotaciones, de ${km(sorted.first().km)} a ${km(sorted.last().km)}.",
            )
            val ahead = aheadOfOdometer
            if (ahead.isNotEmpty()) {
                out += PurchaseLine(
                    "Hay anotaciones con más km que el cuentakilómetros actual (" + odometer?.let(::km) + "): " +
                        ahead.joinToString(", ") { km(it.km) } + ". El cuentakilómetros puede haberse bajado, o se cambió la centralita.",
                    warn = true,
                )
            } else if (odometer != null) {
                out += PurchaseLine("Ninguna anotación pasa del cuentakilómetros actual (${km(odometer)}).")
            }
            val marked = suspicious
            out += if (marked.isEmpty()) {
                PurchaseLine("En ninguna anotación la centralita da probabilidad de manipulación.")
            } else {
                PurchaseLine(
                    "Probabilidad de manipulación distinta de 0 en: " +
                        marked.sortedBy { it.km }.joinToString(", ") { "${km(it.km)} (${pct(it.probability)})" } + ".",
                    warn = true,
                )
            }
        }
        val tuning = listOfNotNull(
            tuningNow?.let { "ahora ${number(it)}" },
            tuningMax?.let { "máxima ${number(it)}" },
            tuningCount?.let { "contador $it" },
        )
        if (tuning.isNotEmpty()) {
            out += PurchaseLine(
                "Probabilidad de tuning: " + tuning.joinToString(", ") + ".",
                warn = (tuningMax ?: 0.0) > 0.0 || (tuningNow ?: 0.0) > 0.0,
            )
        }
        oxygenDeviation?.let { out += PurchaseLine("Desviación media del oxígeno en la que se apoya: ${number(it)}.") }
        return out
    }

    companion object {
        const val SLOTS = 20
        const val HIST_TW = 0x164C
        const val HIST_KM = 0x1660
        const val TUNING_NOW = 0x1647
        const val TUNING_MAX = 0x1649
        const val TUNING_COUNT = 0x1645
        const val OXYGEN_DEVIATION = 0x164B

        const val PERCENT_PER_BIT = 0.392157
        const val KM_STEP = 10.0
        const val TUNING_FACTOR = 0.001526

        private fun km(value: Double) = String.format(Locale("es", "ES"), "%,.0f km", value)
        private fun pct(value: Double?) = value?.let { String.format(Locale.US, "%.1f %%", it) } ?: "sin dato"
        private fun number(value: Double) = String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')

        private fun raw(data: IntArray?, size: Int): Long? =
            data?.takeIf { it.size >= size }?.take(size)?.fold(0L) { acc, byte -> acc * 256 + byte }

        /**
         * Arma el informe con lo que contesto cada direccion ([read] da los bytes de una o null). Una
         * anotacion con 0 km se da por vacia. Null si no contesta ninguna.
         */
        suspend fun read(odometer: Double?, read: suspend (Int) -> IntArray?): ManipulationReport? {
            val entries = ArrayList<Entry>()
            var answered = false
            for (slot in 0 until SLOTS) {
                val kmRaw = raw(read(HIST_KM + slot), 2)
                val twRaw = raw(read(HIST_TW + slot), 1)
                if (kmRaw != null || twRaw != null) answered = true
                if (kmRaw != null && kmRaw > 0) entries += Entry(slot, kmRaw * KM_STEP, twRaw?.let { it * PERCENT_PER_BIT })
            }
            val now = raw(read(TUNING_NOW), 2)?.let { it * TUNING_FACTOR }
            val max = raw(read(TUNING_MAX), 2)?.toDouble()
            val count = raw(read(TUNING_COUNT), 4)
            val oxygen = raw(read(OXYGEN_DEVIATION), 2)?.toDouble()
            if (!answered && now == null && max == null && count == null && oxygen == null) return null
            return ManipulationReport(entries, now, max, count, oxygen, odometer)
        }
    }
}
