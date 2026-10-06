package com.rabie.bmwobd.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.UUID

/** Bluetooth clasico con el perfil serie (SPP), el que usan los ELM327 baratos. */
@SuppressLint("MissingPermission")
class BluetoothSppTransport(private val device: BluetoothDevice) : ObdTransport {

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override suspend fun open() = withContext(Dispatchers.IO) {
        val connected = try {
            connect(device.createRfcommSocketToServiceRecord(SPP_UUID))
        } catch (e: IOException) {
            connect(createSocketOnChannel1())
        }
        socket = connected
        input = connected.inputStream
        output = connected.outputStream
    }

    override suspend fun write(text: String) = withContext(Dispatchers.IO) {
        val inp = input ?: throw IOException("Sin conexion")
        val out = output ?: throw IOException("Sin conexion")
        while (inp.available() > 0) inp.read()
        out.write(text.toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    override suspend fun readUntilPrompt(timeoutMs: Long): String = withContext(Dispatchers.IO) {
        val inp = input ?: throw IOException("Sin conexion")
        val received = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (inp.available() > 0) {
                val byte = inp.read()
                if (byte < 0) throw IOException("Conexion cerrada por el adaptador")
                val char = byte.toChar()
                if (char == PROMPT) return@withContext received.toString()
                received.append(char)
            } else {
                delay(POLL_MS)
            }
        }
        throw SocketTimeoutException("El adaptador no respondio en ${timeoutMs} ms")
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    private fun connect(candidate: BluetoothSocket): BluetoothSocket {
        try {
            candidate.connect()
            return candidate
        } catch (e: IOException) {
            runCatching { candidate.close() }
            throw e
        }
    }

    /** Muchos clones solo aceptan el socket en el canal 1 y rechazan el UUID. */
    private fun createSocketOnChannel1(): BluetoothSocket {
        val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
        return method.invoke(device, 1) as BluetoothSocket
    }

    private companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val PROMPT = '>'
        const val POLL_MS = 5L
    }
}
