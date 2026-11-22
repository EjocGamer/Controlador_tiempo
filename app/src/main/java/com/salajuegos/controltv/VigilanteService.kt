package com.salajuegos.controltv

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.tananaev.adblib.AdbBase64
import com.tananaev.adblib.AdbConnection
import com.tananaev.adblib.AdbCrypto
import kotlinx.coroutines.*
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

class VigilanteService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var crypto: AdbCrypto
    private val numTvs = 6
    private val avisoEnviado = BooleanArray(numTvs + 1) { false }

    override fun onCreate() {
        super.onCreate()
        val base64 = AdbBase64 { data -> Base64.encodeToString(data, Base64.NO_WRAP) }
        val pubFile = File(filesDir, "adb_pub.key")
        val privFile = File(filesDir, "adb_priv.key")
        crypto = if (pubFile.exists() && privFile.exists()) {
            AdbCrypto.loadAdbKeyPair(base64, privFile, pubFile)
        } else {
            AdbCrypto.generateAdbKeyPair(base64).apply { saveAdbKeyPair(privFile, pubFile) }
        }

        iniciarNotificacion()
        iniciarBucleVigilancia()
    }

    private fun iniciarNotificacion() {
        val channelId = "ejoc_vigilante"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Control de Sala Activo", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("EjocGames Time Activo")
            .setContentText("Supervisando apagado automático de pantallas")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .build()
        startForeground(101, notif)
    }

    private fun iniciarBucleVigilancia() {
        scope.launch {
            while (isActive) {
                val prefs = getSharedPreferences("sala_tvs", Context.MODE_PRIVATE)
                val tareas = (1..numTvs).map { id ->
                    async {
                        val ip = prefs.getString("ip_$id", "192.168.1.${100 + id}") ?: return@async
                        verificarYControlarTv(id, ip)
                    }
                }
                tareas.awaitAll()
                delay(3000)
            }
        }
    }

    private suspend fun verificarYControlarTv(id: Int, ip: String) {
        val salida = ejecutarAdb(ip, "cat /sdcard/Download/sala_fin.txt 2>/dev/null; echo '|'; date +%s") ?: return
        val partes = salida.split("|")
        if (partes.size < 2) return

        val ahora = partes[1].trim().toLongOrNull() ?: return
        if (ahora < 1700000000L) return

        val textoFin = partes[0].trim()
        if (textoFin.isEmpty()) {
            avisoEnviado[id] = false
            return
        }

        val fin = textoFin.toLongOrNull() ?: return
        val restante = (fin - ahora).toInt()
        if (restante > 86400) return

        // Faltando 2 minutos: pulso visual en pantalla del TV
        if (restante in 1..120 && !avisoEnviado[id]) {
            avisoEnviado[id] = true
            ejecutarAdb(ip, "input keyevent 25; input keyevent 25; sleep 1; input keyevent 24; input keyevent 24")
        }

        // Tiempo agotado: borra archivo y apaga el TV inmediatamente
        if (restante <= 0) {
            avisoEnviado[id] = false
            ejecutarAdb(ip, "rm -f /sdcard/Download/sala_fin.txt /sdcard/Download/sala_pid.txt; input keyevent 26")
        }
    }

    private fun ejecutarAdb(ip: String, comando: String): String? {
        var socket: Socket? = null
        var conn: AdbConnection? = null
        return try {
            socket = Socket()
            socket.soTimeout = 1500
            socket.connect(InetSocketAddress(ip, 5555), 1500)
            conn = AdbConnection.create(socket, crypto)
            conn.connect()
            val stream = conn.open("shell:$comando")
            val sb = StringBuilder()
            while (!stream.isClosed) {
                try {
                    val b = stream.read()
                    if (b != null) sb.append(String(b))
                } catch (_: Exception) { break }
            }
            sb.toString()
        } catch (_: Exception) {
            null
        } finally {
            try { conn?.close() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
