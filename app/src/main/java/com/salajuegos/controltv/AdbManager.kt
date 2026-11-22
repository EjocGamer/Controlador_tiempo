package com.salajuegos.controltv

import android.content.Context
import android.util.Base64
import com.tananaev.adblib.AdbBase64
import com.tananaev.adblib.AdbConnection
import com.tananaev.adblib.AdbCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

object AdbManager {
    private var crypto: AdbCrypto? = null
    private val candadosPorIp = ConcurrentHashMap<String, Mutex>()
    private val pausaReconexionHasta = ConcurrentHashMap<String, Long>()

    @Synchronized
    private fun obtenerCrypto(context: Context): AdbCrypto {
        crypto?.let { return it }
        val base64 = AdbBase64 { data -> Base64.encodeToString(data, Base64.NO_WRAP) }
        val appCtx = context.applicationContext
        val pubFile = File(appCtx.filesDir, "adb_pub.key")
        val privFile = File(appCtx.filesDir, "adb_priv.key")
        val instancia = if (pubFile.exists() && privFile.exists()) {
            AdbCrypto.loadAdbKeyPair(base64, privFile, pubFile)
        } else {
            AdbCrypto.generateAdbKeyPair(base64).apply { saveAdbKeyPair(privFile, pubFile) }
        }
        crypto = instancia
        return instancia
    }

    suspend fun ejecutar(
        context: Context,
        ip: String,
        comando: String,
        esAccionManual: Boolean = false
    ): String? = withContext(Dispatchers.IO) {
        val mutex = candadosPorIp.getOrPut(ip) { Mutex() }
        mutex.withLock {
            val ahora = System.currentTimeMillis()
            val pausadoHasta = pausaReconexionHasta[ip] ?: 0L
            if (!esAccionManual && ahora < pausadoHasta) {
                return@withContext null
            }

            var socket: Socket? = null
            var conn: AdbConnection? = null
            try {
                socket = Socket()
                socket.soTimeout = 1500
                socket.connect(InetSocketAddress(ip, 5555), 1500)
                conn = AdbConnection.create(socket, obtenerCrypto(context))
                conn.connect()

                // Conectado exitosamente: limpiar cualquier pausa pendiente
                pausaReconexionHasta.remove(ip)

                val stream = conn.open("shell:$comando")
                val salida = StringBuilder()
                while (!stream.isClosed) {
                    try {
                        val bytes = stream.read()
                        if (bytes != null) salida.append(String(bytes))
                    } catch (_: Exception) {
                        break
                    }
                }
                salida.toString()
            } catch (_: Exception) {
                // Si el TV está apagado o esperando autorización RSA en pantalla, pausar 25s
                pausaReconexionHasta[ip] = System.currentTimeMillis() + 25_000L
                null
            } finally {
                try { conn?.close() } catch (_: Exception) {}
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }
}
