package com.rabie.bmwobd.obd

/**
 * Valores que el coche da como si fueran medidas y son marcas de "no hay dato". Se quitan antes de
 * enseñarlos o grabarlos, y en la pantalla y en el fichero queda el hueco. Todos vistos en las
 * grabaciones del 12 y el 13 de octubre.
 */
object Plausible {

    /**
     * La lambda de BMW llega al tope de su escala (32,767) cuando apenas hay gasoleo que quemar,
     * al levantar el pie: el 10 % del tiempo en marcha. El error de la EGR del OBD es una cuenta
     * sobre lo mandado y con la EGR mandada cerrada sale -100 %: 1.898 veces, todas con la EGR
     * mandada a 0. La presion antes del turbo dio una vez 3.000 hPa justos, el tope del sensor.
     */
    fun clean(values: MutableMap<Int, Double>) {
        values[LAMBDA]?.let { if (it >= LAMBDA_TOP) values.remove(LAMBDA) }
        values[EGR_ERROR]?.let { error ->
            if (error <= EGR_ERROR_MARK && (values[EGR_SENT] ?: 0.0) < EGR_OPEN) values.remove(EGR_ERROR)
        }
        values[TURBO_INLET]?.let { if (it >= TURBO_INLET_TOP) values.remove(TURBO_INLET) }
        // El modo del motor dio 0x7FFFFFFF el 15 de octubre, con el contacto recien puesto: el
        // numero mas alto que cabe, que no es ningun modo.
        values[Pids.BMW_ENGINE_MODE]?.let { if (it == ENGINE_MODE_NONE) values.remove(Pids.BMW_ENGINE_MODE) }
    }

    private const val ENGINE_MODE_NONE = 2_147_483_647.0

    private val LAMBDA = Pids.bmw(0x0A29)
    private const val LAMBDA_TOP = 32.76
    private const val EGR_ERROR = 0x2D
    private const val EGR_SENT = 0x2C
    private const val EGR_ERROR_MARK = -99.9
    private const val EGR_OPEN = 5.0
    private val TURBO_INLET = Pids.bmw(0x03E0)
    private const val TURBO_INLET_TOP = 2_999.0
}
