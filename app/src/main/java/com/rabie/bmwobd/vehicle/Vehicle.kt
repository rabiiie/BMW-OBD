package com.rabie.bmwobd.vehicle

import android.content.SharedPreferences

/**
 * Perfil de un coche. Se crea solo la primera vez que se conecta, con lo que el coche dice de si
 * mismo, y el usuario completa lo que no se puede leer: el nombre y la cilindrada.
 */
data class Vehicle(
    val key: String,
    val vin: String?,
    val name: String,
    val make: String?,
    val diesel: Boolean,
    val displacementLiters: Double?,
) {
    /** Escala del cuentarrevoluciones: un diesel corta mucho antes que un gasolina. */
    val rpmMax: Double get() = if (diesel) 6000.0 else 8000.0
    val rpmLabels: List<String> get() = (0..(rpmMax / 1000).toInt()).map { it.toString() }
    val rpmRedFrom: Float get() = if (diesel) 5f / 6f else 6.5f / 8f

    // Los gasolina con termostato pilotado trabajan a proposito por encima de 100 °C.
    val coolantWarn: Double get() = if (diesel) 108.0 else 112.0
    val coolantAlert: Double get() = if (diesel) 115.0 else 118.0

    /** Como describir el coche en una busqueda: marca, nombre, cilindrada y combustible. Sin bastidor. */
    val searchTerms: String
        get() = listOfNotNull(
            make?.takeUnless { name.contains(it, ignoreCase = true) },
            name.takeUnless { key == UNKNOWN_KEY },
            displacementLiters?.let { "%.1f".format(java.util.Locale.US, it) },
            if (diesel) "diésel" else "gasolina",
        ).joinToString(" ")

    companion object {
        const val UNKNOWN_KEY = "desconocido"
        val GENERIC = Vehicle(UNKNOWN_KEY, null, "Coche sin identificar", null, diesel = true, displacementLiters = null)
    }
}

object Vin {

    /** Fabricante segun los tres primeros caracteres del bastidor. Solo los mas comunes en Europa. */
    fun make(vin: String): String? = makes[vin.take(3).uppercase()] ?: makes[vin.take(2).uppercase()]

    private val makes = mapOf(
        "WBA" to "BMW", "WBS" to "BMW M", "WBY" to "BMW", "WMW" to "MINI",
        "WVW" to "Volkswagen", "WVG" to "Volkswagen", "WV1" to "Volkswagen", "WV2" to "Volkswagen",
        "WAU" to "Audi", "TRU" to "Audi", "VSS" to "SEAT", "TMB" to "Škoda", "WP0" to "Porsche", "WP1" to "Porsche",
        "WDB" to "Mercedes-Benz", "WDD" to "Mercedes-Benz", "WDC" to "Mercedes-Benz", "W1K" to "Mercedes-Benz",
        "W0L" to "Opel", "W0V" to "Opel", "WF0" to "Ford",
        "VF1" to "Renault", "VF3" to "Peugeot", "VF7" to "Citroën", "UU1" to "Dacia",
        "ZFA" to "Fiat", "ZAR" to "Alfa Romeo", "YV1" to "Volvo", "SAL" to "Land Rover", "SAJ" to "Jaguar",
        "JT" to "Toyota", "JN" to "Nissan", "JM" to "Mazda", "JH" to "Honda", "KM" to "Hyundai", "KN" to "Kia",
    )
}

/** Los perfiles guardados, uno por bastidor. */
class VehicleStore(private val prefs: SharedPreferences) {

    /** El perfil de este bastidor; si es la primera vez que se ve, lo crea con lo detectado. */
    fun resolve(vin: String?, dieselDetected: Boolean): Vehicle {
        val key = vin ?: Vehicle.UNKNOWN_KEY
        val known = byKey(key)
        val vehicle = known ?: run {
            val make = vin?.let(Vin::make)
            Vehicle(
                key = key,
                vin = vin,
                name = if (vin == null) Vehicle.GENERIC.name else listOfNotNull(make, vin.takeLast(6)).joinToString(" "),
                make = make,
                diesel = dieselDetected,
                displacementLiters = null,
            )
        }
        save(vehicle)
        return vehicle
    }

    fun byKey(key: String): Vehicle? {
        val name = prefs.getString("$key.name", null) ?: return null
        val vin = prefs.getString("$key.vin", null)
        return Vehicle(
            key = key,
            vin = vin,
            name = name,
            make = vin?.let(Vin::make),
            diesel = prefs.getBoolean("$key.diesel", true),
            displacementLiters = prefs.getFloat("$key.displacement", 0f).takeIf { it > 0f }?.toDouble(),
        )
    }

    /** El ultimo coche al que se conecto, para rotular la app antes de conectar. */
    fun last(): Vehicle? = prefs.getString(KEY_LAST, null)?.let(::byKey)

    fun save(vehicle: Vehicle) {
        prefs.edit()
            .putString("${vehicle.key}.name", vehicle.name)
            .putString("${vehicle.key}.vin", vehicle.vin)
            .putBoolean("${vehicle.key}.diesel", vehicle.diesel)
            .putFloat("${vehicle.key}.displacement", vehicle.displacementLiters?.toFloat() ?: 0f)
            .putString(KEY_LAST, vehicle.key)
            .apply()
    }

    private companion object {
        const val KEY_LAST = "last"
    }
}
