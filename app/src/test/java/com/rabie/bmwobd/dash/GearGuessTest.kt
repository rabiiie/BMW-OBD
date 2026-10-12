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

    private fun held(hint: ShiftHint, speed: Double, rpm: Double, gear: Int?, nextRpm: Double?, demanding: Boolean = false, coolant: Double = 90.0): Int {
        var last = ShiftHint.NONE
        for (t in 0..3000 step 500) last = hint.update(t.toLong(), speed, rpm, coolant, gear, nextRpm, demanding)
        return last
    }

    @Test
    fun `recomienda subir cuando el motor va holgado y la siguiente aguanta`() {
        val gears = GearGuess()
        // Cuarta a 2.100 rpm son 78 km/h; en quinta caeria a 1.640.
        val next = 78.0 / gears.ratio(5)!! * 1000.0
        assertEquals(1642.0, next, 1.0)
        assertEquals(ShiftHint.UP, held(ShiftHint(), 78.0, 2100.0, 4, next))
        // Pidiendole fuerza no: en la marcha larga se quedaria sin ella.
        assertEquals(ShiftHint.NONE, held(ShiftHint(), 78.0, 2100.0, 4, next, demanding = true))
        // A pocas vueltas tampoco, ni en la ultima marcha, ni si la siguiente caeria demasiado.
        assertEquals(ShiftHint.NONE, held(ShiftHint(), 60.0, 1620.0, 4, 60.0 / 47.5 * 1000))
        assertEquals(ShiftHint.NONE, held(ShiftHint(), 130.0, 2245.0, 6, null))
        assertEquals(ShiftHint.NONE, held(ShiftHint(), 78.0, 2100.0, 4, 1200.0))
        // En frio se deja subir mas de vueltas antes de recomendarlo.
        assertEquals(ShiftHint.NONE, held(ShiftHint(), 78.0, 2100.0, 4, next, coolant = 35.0))
        assertEquals(ShiftHint.UP, held(ShiftHint(), 95.0, 2570.0, 4, 2000.0, coolant = 35.0))
        // Sin conocer la relacion de la siguiente, basta con que vaya holgado de vueltas.
        assertEquals(ShiftHint.UP, held(ShiftHint(), 30.0, 2300.0, 2, null))
    }

    @Test
    fun `recomienda bajar cuando va ahogado y no dice nada parado o sin marcha`() {
        assertEquals(ShiftHint.DOWN, held(ShiftHint(), 45.0, 1050.0, 4, null))
        assertEquals(ShiftHint.DOWN, held(ShiftHint(), 55.0, 1300.0, 5, null, demanding = true))
        assertEquals(ShiftHint.NONE, held(ShiftHint(), 55.0, 1300.0, 5, null))
        // En primera no hay a donde bajar.
        assertEquals(ShiftHint.NONE, held(ShiftHint(), 12.0, 1000.0, 1, null))
        assertEquals(ShiftHint.NONE, held(ShiftHint(), 0.0, 800.0, 0, null))
        assertEquals(ShiftHint.NONE, held(ShiftHint(), 60.0, 2400.0, null, null))
    }

    @Test
    fun `la recomendacion tarda un momento en salir y se quita en cuanto deja de valer`() {
        val hint = ShiftHint()
        assertEquals(ShiftHint.NONE, hint.update(0, 78.0, 2100.0, 90.0, 4, 1640.0, false))
        assertEquals(ShiftHint.NONE, hint.update(1000, 78.0, 2100.0, 90.0, 4, 1640.0, false))
        assertEquals(ShiftHint.UP, hint.update(1600, 78.0, 2100.0, 90.0, 4, 1640.0, false))
        // Al cambiar a quinta desaparece al momento.
        assertEquals(ShiftHint.NONE, hint.update(1900, 78.0, 1640.0, 90.0, 5, 1347.0, false))
        // Un golpe de pedal la reinicia: no vuelve hasta que se mantiene otra vez.
        hint.update(2000, 78.0, 2100.0, 90.0, 4, 1640.0, false)
        hint.update(2500, 78.0, 2100.0, 90.0, 4, 1640.0, true)
        assertEquals(ShiftHint.NONE, hint.update(3000, 78.0, 2100.0, 90.0, 4, 1640.0, false))
        assertEquals(ShiftHint.UP, hint.update(4600, 78.0, 2100.0, 90.0, 4, 1640.0, false))
    }
}
