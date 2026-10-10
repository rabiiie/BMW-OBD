package com.rabie.bmwobd.obd

/** Envia comandos AT y OBD al ELM327 y devuelve la respuesta limpia, una linea por renglon. */
class Elm327(
    private val transport: ObdTransport,
    private val log: (String) -> Unit,
) {

    /** Con [quiet] no deja rastro en el registro: para barridos de miles de consultas. */
    suspend fun send(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS, quiet: Boolean = false): String {
        if (!quiet) log(">> $command")
        transport.write("$command\r")
        val raw = transport.readUntilPrompt(timeoutMs)
        val clean = raw.replace('\r', '\n')
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        if (!quiet) log("<< ${clean.replace("\n", " | ")}")
        return clean
    }

    /** Reinicia el adaptador y lo deja sin eco, sin espacios ni cabeceras. Devuelve su version. */
    suspend fun initialize(): String {
        val version = send("ATZ", RESET_TIMEOUT_MS).lines().lastOrNull().orEmpty()
        configure()
        return version
    }

    /** Vuelve a dejar el adaptador como lo espera la app, sin reiniciarlo. */
    suspend fun configure() {
        for (command in SETUP_COMMANDS) send(command)
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 2_000L
        const val RESET_TIMEOUT_MS = 4_000L

        // Eco, saltos de linea y espacios fuera, sin cabeceras, tiempo adaptativo, protocolo automatico.
        val SETUP_COMMANDS = listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0")
    }
}
