package com.salajuegos.controltv

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
    private val numTvs = 6
    private val avisoEnviado = BooleanArray(numTvs + 1) { false }
    private val tiemposActivos = IntArray(numTvs + 1) { -1 } // -1 = sin tiempo/desconectado

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

        crearCanalesNotificacion()
        actualizarNotificacionEnVivo("Iniciando supervisión de pantallas...")
        iniciarBucleVigilancia()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY hace que Android reinicie el servicio automáticamente si se libera memoria
        return START_STICKY
    }

    private fun crearCanalesNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            // Canal 1: Panel en vivo silencioso con los tiempos restantes
            val canalServicio = NotificationChannel(
                "ejoc_vigilante",
                "Panel de Tiempos en Vivo",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            nm.createNotificationChannel(canalServicio)

            // Canal 2: Alerta urgente cuando faltan 2 minutos (sonido + vibración + banner)
            val canalAlertas = NotificationChannel(
                "ejoc_alertas_2min",
                "Avisos de Tiempo por Vencer (2 min)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Avisa cuando faltan 2 minutos para apagar una estación"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
            }
            nm.createNotificationChannel(canalAlertas)
        }
    }

    private fun obtenerPendingIntentPrincipal(): PendingIntent {
        val intentAbrir = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intentAbrir,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun actualizarNotificacionEnVivo(resumenTexto: String) {
        val notif = NotificationCompat.Builder(this, "ejoc_vigilante")
            .setContentTitle("🎮 EjocGames Time • Tiempos Activos")
            .setContentText(resumenTexto.lines().firstOrNull() ?: "Sin estaciones activas")
            .setStyle(NotificationCompat.BigTextStyle().bigText(resumenTexto))
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(obtenerPendingIntentPrincipal())
            .build()

        startForeground(101, notif)
    }

    private fun lanzarAlertaDependiente(id: Int, nombreEstacion: String, segundosRestantes: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        val min = segundosRestantes / 60
        val seg = segundosRestantes % 60
        val tiempoTexto = String.format("%02d:%02d", min, seg)

        val alerta = NotificationCompat.Builder(this, "ejoc_alertas_2min")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⏳ ¡$nombreEstacion por terminar!")
            .setContentText("Quedan $tiempoTexto para el apagado automático. Toca para agregar tiempo.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .setContentIntent(obtenerPendingIntentPrincipal())
            .build()

        nm.notify(200 + id, alerta)
    }

    private fun iniciarBucleVigilancia() {
        scope.launch {
            while (isActive) {
                val prefs = getSharedPreferences("sala_tvs", Context.MODE_PRIVATE)
                val tareas = (1..numTvs).map { id ->
                    async {
                        val ip = prefs.getString("ip_$id", "192.168.1.${100 + id}") ?: return@async
                        val nombre = prefs.getString("nombre_$id", "Estación $id") ?: "Estación $id"
                        verificarYControlarTv(id, nombre, ip)
                    }
                }
                tareas.awaitAll()

                // Construir el resumen en tiempo real para la barra de notificaciones
                val lineas = mutableListOf<String>()
                for (id in 1..numTvs) {
                    val seg = tiemposActivos[id]
                    if (seg > 0) {
                        val nombre = prefs.getString("nombre_$id", "Estación $id") ?: "Estación $id"
                        val h = seg / 3600
                        val m = (seg % 3600) / 60
                        val s = seg % 60
                        val reloj = if (h > 0) String.format("%dh %02dm %02ds", h, m, s) else String.format("%02dm %02ds", m, s)
                        val icono = if (seg <= 120) "⚠️" else "🟢"
                        lineas.add("$icono $nombre: $reloj")
                    }
                }

                val textoFinal = if (lineas.isEmpty()) {
                    "Ninguna estación con tiempo activo"
                } else {
                    lineas.joinToString("\n")
                }
                actualizarNotificacionEnVivo(textoFinal)

                delay(2000)
            }
        }
    }

    private suspend fun verificarYControlarTv(id: Int, nombre: String, ip: String) {
        val salida = AdbManager.ejecutar(this, ip, "cat /sdcard/Download/sala_fin.txt 2>/dev/null; echo '|'; cat /sdcard/Download/sala_libre.txt 2>/dev/null; echo '|'; date +%s")
        if (salida == null) {
            tiemposActivos[id] = -1
            return
        }
        val partes = salida.split("|")
        if (partes.size < 2) return

        val ahora = partes[1].trim().toLongOrNull() ?: return
        if (ahora < 1700000000L) return

        val textoFin = partes[0].trim()
        if (textoFin.isEmpty()) {
            avisoEnviado[id] = false
            tiemposActivos[id] = 0
            return
        }

        val fin = textoFin.toLongOrNull() ?: return
        val restante = (fin - ahora).toInt()
        if (restante > 86400) return

        tiemposActivos[id] = if (restante > 0) restante else 0

        if (restante > 120) {
            avisoEnviado[id] = false
        }

        // Faltando 2 minutos: notificación sonora/vibración en el teléfono
        if (restante in 1..120 && !avisoEnviado[id]) {
            avisoEnviado[id] = true
            lanzarAlertaDependiente(id, nombre, restante)
        }

        // Tiempo agotado: borra archivo y apaga el TV inmediatamente
        if (restante <= 0) {
            avisoEnviado[id] = false
            tiemposActivos[id] = 0
            AdbManager.ejecutar(this, ip, "rm -f /sdcard/Download/sala_fin.txt /sdcard/Download/sala_pid.txt; input keyevent 26")
        }
    }


    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
