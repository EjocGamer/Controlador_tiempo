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
    private val conexionesActivas = ConcurrentHashMap<String, Pair<Socket, AdbConnection>>()
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

    private fun cerrarConexion(ip: String) {
        val par = conexionesActivas.remove(ip) ?: return
        try { par.second.close() } catch (_: Exception) {}
        try { par.first.close() } catch (_: Exception) {}
    }

    private fun obtenerOConectar(context: Context, ip: String, forzarAhora: Boolean): AdbConnection? {
        val existente = conexionesActivas[ip]
        if (existente != null && !existente.first.isClosed && existente.first.isConnected) {
            return existente.second
        }
        cerrarConexion(ip)

        val ahora = System.currentTimeMillis()
        val pausadoHasta = pausaReconexionHasta[ip] ?: 0L
        if (!forzarAhora && ahora < pausadoHasta) {
            return null
        }

        // Bloquea cualquier otro intento durante 30s mientras sale el único cuadro RSA en pantalla
        pausaReconexionHasta[ip] = ahora + 30_000L

        return try {
            val socket = Socket()
            socket.soTimeout = 3000
            socket.keepAlive = true
            socket.connect(InetSocketAddress(ip, 5555), 2500)
            val conn = AdbConnection.create(socket, obtenerCrypto(context))
            conn.connect()
            conexionesActivas[ip] = Pair(socket, conn)
            pausaReconexionHasta.remove(ip)
            conn
        } catch (_: Exception) {
            null
        }
    }

    suspend fun ejecutar(context: Context, ip: String, comando: String, esAccionManual: Boolean = false): String? = withContext(Dispatchers.IO) {
        val mutex = candadosPorIp.getOrPut(ip) { Mutex() }
        mutex.withLock {
            try {
                val conn = obtenerOConectar(context, ip, esAccionManual) ?: return@withContext null
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
                cerrarConexion(ip)
                null
            }
        }
    }
}
