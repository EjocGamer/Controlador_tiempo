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
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

object AdbManager {
    private const val PREFS_HDMI = "ejoc_hdmi_cache"

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
                socket.connect(InetSocketAddress(ip, 5555), 1200)
                conn = AdbConnection.create(socket, obtenerCrypto(context))
                conn.connect()

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
                pausaReconexionHasta[ip] = System.currentTimeMillis() + 4_000L
                null
            } finally {
                try { conn?.close() } catch (_: Exception) {}
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }

    suspend fun cambiarAHdmiUniversal(
        context: Context,
        ip: String,
        puertoHdmi: Int = 1,
        forzarRedeteccion: Boolean = false
    ): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_HDMI, Context.MODE_PRIVATE)
        val claveCache = "${ip}_hdmi_$puertoHdmi"
        var uriGuardada = if (forzarRedeteccion) null else prefs.getString(claveCache, null)

        if (uriGuardada.isNullOrBlank()) {
            val salida = ejecutar(
                context = context,
                ip = ip,
                comando = "dumpsys tv_input | grep -i -E 'hdmi_port=|hdmi.*HW|Hdmi.*InputService'",
                esAccionManual = true
            )

            if (!salida.isNullOrBlank()) {
                val inputId = extraerInputIdPorPuerto(salida, puertoHdmi)
                if (!inputId.isNullOrBlank()) {
                    val codificado = URLEncoder.encode(inputId, "UTF-8")
                    uriGuardada = "content://android.media.tv/passthrough/$codificado"
                    prefs.edit().putString(claveCache, uriGuardada).apply()
                }
            }
        }

        if (!uriGuardada.isNullOrBlank()) {
            val cmdUri = "am start -a android.intent.action.VIEW -d \"$uriGuardada\" --activity-clear-top --activity-single-top"
            return ejecutar(context, ip, cmdUri, esAccionManual = true) != null
        }

        val keycode = when (puertoHdmi) {
            1 -> "KEYCODE_TV_INPUT_HDMI_1"
            2 -> "KEYCODE_TV_INPUT_HDMI_2"
            3 -> "KEYCODE_TV_INPUT_HDMI_3"
            4 -> "KEYCODE_TV_INPUT_HDMI_4"
            else -> "KEYCODE_TV_INPUT_HDMI_1"
        }
        return ejecutar(context, ip, "input keyevent $keycode", esAccionManual = true) != null
    }

    private fun extraerInputIdPorPuerto(salidaDumpsys: String, puertoHdmi: Int): String? {
        val regexPuertoExacto = Regex("""hdmi_port=${puertoHdmi}\b.*?TvInputInfo\{id=([^\s,}]+)""")
        regexPuertoExacto.find(salidaDumpsys)?.groupValues?.getOrNull(1)?.let { return it }

        val regexNombrePuerto = Regex("""([a-zA-Z0-9._]+/\.?[a-zA-Z0-9._]*[Hh][Dd][Mm][Ii]${puertoHdmi}[a-zA-Z0-9._/]*)""")
        regexNombrePuerto.find(salidaDumpsys)?.groupValues?.getOrNull(1)?.let { return it }

        val regexGenerico = Regex("""([a-zA-Z0-9._]+/\.?[a-zA-Z0-9._]*[Hh][Dd][Mm][Ii][a-zA-Z0-9._]*/HW\d+)""")
        val idsUnicos = regexGenerico.findAll(salidaDumpsys)
            .map { it.groupValues[1] }
            .distinct()
            .sorted()
            .toList()

        if (idsUnicos.isEmpty()) return null
        val indice = (puertoHdmi - 1).coerceIn(0, idsUnicos.size - 1)
        return idsUnicos[indice]
    }

    fun limpiarCacheHdmi(context: Context, ip: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_HDMI, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("${ip}_hdmi_") }.forEach { editor.remove(it) }
        editor.apply()
    }
}
