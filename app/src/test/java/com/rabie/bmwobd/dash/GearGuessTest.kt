package com.rabie.bmwobd.dash

import com.rabie.bmwobd.obd.SimulatedEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GearGuessTest {

    @Test
    fun `con lo visto en el coche acierta cuarta, quinta y sexta sin haber aprendido nada`() {
        val gears = GearGuess()
        // Lecturas del barrido del 11 de octubre.
        assertEquals(5, gears.now(88.0, 1854.0))
        assertEquals(4, gears.now(59.0, 1594.0))
        assertEquals(6, gears.now(113.0, 1949.0))
        assertEquals(0, gears.now(0.0, 800.0))
        // Una relacion que no es de ninguna marcha conocida: no se inventa.
        assertNull(gears.now(30.0, 2400.0))
        assertNull(gears.now(null, 800.0))
        assertNull(gears.now(60.0, 300.0))
    }

    @Test
    fun `aprende una marcha con dos lecturas que coinciden y no con una suelta`() {
        val gears = GearGuess()
        gears.learn(2, 30.0, 2000.0)
        assertNull(gears.now(30.0, 2000.0))
        // A medio cambio la relacion no cuadra con la anterior: sigue sin darse por buena.
        gears.learn(2, 30.0, 1200.0)
        assertNull(gears.now(30.0, 2000.0))
        gears.learn(2, 30.0, 1210.0)
        assertEquals(2, gears.now(31.0, 1245.0))
        // Parado, a pocas vueltas o con una marcha imposible no aprende.
        gears.learn(3, 5.0, 900.0)
        gears.learn(3, 5.0, 900.0)
        gears.learn(9, 80.0, 2000.0)
        gears.learn(9, 80.0, 2000.0)
        assertNull(gears.now(20.0, 3600.0))
        assertNull(gears.now(80.0, 2000.0))
    }

    @Test
    fun `lo aprendido manda sobre lo visto en otro coche`() {
        // El motor simulado tiene relaciones mas cortas: su sexta se parece a la quinta del 118d.
        val gears = GearGuess()
        val speed = 110.0
        val rpm = speed * 20.5
        assertEquals(6, SimulatedEngine.gearAt(speed, rpm))
        assertEquals(5, gears.now(speed, rpm))
        repeat(2) { gears.learn(6, speed, rpm) }
        assertEquals(6, gears.now(speed, rpm))
        assertEquals(0, SimulatedEngine.gearAt(0.0, 800.0))
    }
}
