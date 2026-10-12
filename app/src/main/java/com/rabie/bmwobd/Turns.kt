package com.rabie.bmwobd

import com.rabie.bmwobd.cases.Case
import com.rabie.bmwobd.obd.PidDef
import com.rabie.bmwobd.obd.Pids
import com.rabie.bmwobd.obd.Tier

/** Una peticion al coche y las medidas que salen de su respuesta. */
internal class Request(val pid: Int, val defs: List<PidDef>) {
    val tier: Tier = defs.minOf { it.tier }

    /** Los PIDs de una sola medida caben en una trama y admiten la lectura rapida. */
    val singleFrame: Boolean = !Pids.isBmw(pid) && defs.all { it.pid == it.id }
}

/**
 * Que se pregunta en cada vuelta de lectura. Las rapidas, siempre; de las medias, una; de las
 * lentas, una cada varias vueltas. Con un caso puesto, las medidas que vigila salen del turno que
 * tuvieran y se lee una en cada vuelta. Las de [extras] solo existen dentro de los casos: fuera
 * de ellos no se piden, salvo las de [background], que el panel enseña siempre y van al turno lento.
 */
internal class Turns(defs: List<PidDef>, extras: List<PidDef>, background: Collection<Int> = emptyList()) {

    /** Todo lo que se lee siempre, para la primera lectura al conectar. */
    val requests: List<Request> = defs.groupBy { it.pid }.map { Request(it.key, it.value) }

    private val fast = requests.filter { it.tier == Tier.FAST }
    private val medium = requests.filter { it.tier == Tier.MEDIUM }
    private val slow = requests.filter { it.tier == Tier.SLOW }
    private val baseIds = defs.map { it.id }.toSet()
    private val extraIds = extras.map { it.id }.toSet() - baseIds
    private val requestOf: Map<Int, Request> =
        requests.flatMap { request -> request.defs.map { it.id to request } }.toMap() +
            extras.filter { it.id in extraIds }.associate { it.id to Request(it.pid, listOf(it)) }

    var case: Case = Case.NORMAL
        private set

    private var watch = emptyList<Request>()
    private var mediumNow = medium
    private var slowNow = slow
    private var mediumIndex = 0
    private var slowIndex = 0
    private var watchIndex = 0
    private var cycle = 0
    private val always = background.filter { it in extraIds }

    /** Las medidas a la vista ahora: las de siempre, las del panel y las propias del caso puesto. */
    var shown: Set<Int> = emptySet()
        private set

    init {
        use(Case.NORMAL)
    }

    /** Pone un caso y devuelve las medidas que quedan a la vista. */
    fun use(wanted: Case): Set<Int> {
        case = wanted
        watch = wanted.watch.mapNotNull { requestOf[it] }.distinct()
        val also = (always + wanted.also).mapNotNull { requestOf[it] }.distinct()
        mediumNow = medium - watch.toSet()
        slowNow = (slow - watch.toSet()).let { kept -> kept + (also - kept.toSet() - watch.toSet()) }
        shown = baseIds + (always + wanted.watch + wanted.also).filter { it in extraIds }
        return shown
    }

    /** Las peticiones de la vuelta siguiente. Durante un barrido el caso espera: el barrido ya ocupa su sitio. */
    fun next(scanning: Boolean = false): List<Request> {
        val turn = fast.toMutableList()
        if (mediumNow.isNotEmpty()) turn += mediumNow[mediumIndex++ % mediumNow.size]
        if (watch.isNotEmpty() && !scanning) turn += watch[watchIndex++ % watch.size]
        // Con muchas medidas lentas se lee una cada dos vueltas, para que no tarden en repetirse.
        val slowEvery = if (slowNow.size > MANY_SLOW) SLOW_EVERY / 2 else SLOW_EVERY
        if (slowNow.isNotEmpty() && (cycle++ % slowEvery == 0 || mediumNow.isEmpty())) turn += slowNow[slowIndex++ % slowNow.size]
        return turn
    }

    private companion object {
        const val SLOW_EVERY = 4
        const val MANY_SLOW = 16
    }
}
