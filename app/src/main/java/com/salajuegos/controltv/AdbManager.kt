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

    suspend fun ejecutar(context: Context, ip: String, comando: String): String? = withContext(Dispatchers.IO) {
        val mutex = candadosPorIp.getOrPut(ip) { Mutex() }
        // Evita que dos hilos abran conexiones ADB simultáneas al mismo TV
        mutex.withLock {
            var socket: Socket? = null
            var conn: AdbConnection? = null
            try {
                socket = Socket()
                socket.soTimeout = 2500
                socket.connect(InetSocketAddress(ip, 5555), 2500)
                conn = AdbConnection.create(socket, obtenerCrypto(context))
                conn.connect()
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
                null
            } finally {
                try { conn?.close() } catch (_: Exception) {}
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }
}
