package com.rabie.bmwobd.obd

/** Canal de bytes hacia el adaptador. El protocolo no sabe si va por Bluetooth o por WiFi. */
interface ObdTransport {
    suspend fun open()
    suspend fun write(text: String)

    /** Lee hasta el prompt '>' del ELM327 y devuelve lo recibido sin el prompt. */
    suspend fun readUntilPrompt(timeoutMs: Long): String
    fun close()
}
