package com.rabie.bmwobd.vehicle

/**
 * A que otras centralitas se sabe preguntar en cada marca, ademas de la del motor, y con que
 * boton. Va por marca y no por motor: la carroceria, las luces o las ruedas no dependen del motor
 * que lleve el coche. Todo son consultas de lectura. Una marca nueva es una entrada aqui y su
 * lista de direcciones en la sesion.
 */
enum class ModuleProbe(val button: String) {
    /** Luces, pasarela, cuadro y llaves, con la direccion de BMW. Solo con la centralita del motor reconocida. */
    BMW("Preguntar a las otras centralitas (coche parado, solo lee)"),

    /** Carroceria, ABS y cuadro con el protocolo estandar: los sensores de las ruedas los vigila la carroceria. */
    FORD("Ford: carrocería, ABS y cuadro (coche parado, solo lee)"),
    ;

    companion object {
        fun of(vehicle: Vehicle): ModuleProbe? = when {
            EngineProfile.of(vehicle) == EngineProfile.BMW_N47 -> BMW
            vehicle.make == FORD_MAKE -> FORD
            else -> null
        }

        private const val FORD_MAKE = "Ford"
    }
}
