package com.salajuegos.controltv

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

object EstadoSala {
    const val NUM_TVS = 6
    val online = BooleanArray(NUM_TVS + 1) { false }
    val segundos = IntArray(NUM_TVS + 1) { 0 }
}

class VigilanteService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val avisoEnviado = BooleanArray(EstadoSala.NUM_TVS + 1) { false }
    private val estadoTextoNotif = Array(EstadoSala.NUM_TVS + 1) { "" }

    override fun onCreate() {
        super.onCreate()
        crearCanalesNotificacion()
        actualizarNotificacionEnVivo("Conectando con estaciones...")
        iniciarBucleVigilancia()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartIntent = Intent(applicationContext, VigilanteService::class.java)
        val pendingIntent = PendingIntent.getService(
            applicationContext,
            1,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000, pendingIntent)
        super.onTaskRemoved(rootIntent)
    }

    private fun crearCanalesNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            val canalServicio = NotificationChannel(
                "ejoc_vigilante",
                "Panel de Tiempos en Vivo",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            nm.createNotificationChannel(canalServicio)

            // Notificación estándar (tono corto de notificación normal, no invasivo)
            val canalAlertas = NotificationChannel(
                "ejoc_avisos_normal_v3",
                "Avisos de Tiempo por Vencer",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificación breve cuando faltan 2 minutos para apagar un TV"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 150, 300)
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

        val sonidoNotif = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val alerta = NotificationCompat.Builder(this, "ejoc_avisos_normal_v3")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("⏳ $nombreEstacion: quedan $tiempoTexto")
            .setContentText("Faltan menos de 2 minutos para el apagado automático.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(sonidoNotif)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 300, 150, 300))
            .setContentIntent(obtenerPendingIntentPrincipal())
            .build()

        nm.notify(200 + id, alerta)
    }

    private fun iniciarBucleVigilancia() {
        scope.launch {
            while (isActive) {
                val prefs = getSharedPreferences("sala_tvs", Context.MODE_PRIVATE)
                val tareas = (1..EstadoSala.NUM_TVS).map { id ->
                    async {
                        val ip = prefs.getString("ip_$id", "192.168.1.${100 + id}") ?: return@async
                        val nombre = prefs.getString("nombre_$id", "Estación $id") ?: "Estación $id"
                        verificarYControlarTv(id, nombre, ip)
                    }
                }
                tareas.awaitAll()

                val lineas = estadoTextoNotif.filter { it.isNotEmpty() }
                val textoFinal = if (lineas.isEmpty()) {
                    "Ninguna estación con tiempo activo"
                } else {
                    lineas.joinToString("\n")
                }
                actualizarNotificacionEnVivo(textoFinal)

                delay(3000)
            }
        }
    }

    private suspend fun verificarYControlarTv(id: Int, nombre: String, ip: String) {
        val salida = AdbManager.ejecutar(
            this,
            ip,
            "cat /sdcard/Download/sala_fin.txt 2>/dev/null; echo '|'; date +%s"
        )
        if (salida == null) {
            EstadoSala.online[id] = false
            estadoTextoNotif[id] = ""
            return
        }

        val partes = salida.split("|")
        if (partes.size < 2) return

        val ahora = partes[1].trim().toLongOrNull() ?: return
        if (ahora < 1700000000L) return

        EstadoSala.online[id] = true

        val textoFin = partes[0].trim()
        if (textoFin.isEmpty()) {
            avisoEnviado[id] = false
            EstadoSala.segundos[id] = 0
            estadoTextoNotif[id] = ""
            return
        }

        val fin = textoFin.toLongOrNull() ?: return
        val restante = (fin - ahora).toInt()
        if (restante > 86400) return

        if (restante > 0) {
            EstadoSala.segundos[id] = restante
            val h = restante / 3600
            val m = (restante % 3600) / 60
            val s = restante % 60
            val reloj = if (h > 0) String.format("%dh %02dm %02ds", h, m, s) else String.format("%02dm %02ds", m, s)
            val icono = if (restante <= 120) "⚠️" else "🟢"
            estadoTextoNotif[id] = "$icono $nombre: $reloj"
        } else {
            EstadoSala.segundos[id] = 0
            estadoTextoNotif[id] = ""
        }

        if (restante > 120) {
            avisoEnviado[id] = false
        }

        // Faltando 2 minutos: dispara notificación normal breve
        if (restante in 1..120 && !avisoEnviado[id]) {
            avisoEnviado[id] = true
            lanzarAlertaDependiente(id, nombre, restante)
        }

        // Tiempo agotado: borra el archivo y envía la orden de apagado en un solo comando atómico
        if (restante <= 0) {
            avisoEnviado[id] = false
            EstadoSala.segundos[id] = 0
            estadoTextoNotif[id] = ""
            AdbManager.ejecutar(
                this,
                ip,
                "rm -f /sdcard/Download/sala_fin.txt /sdcard/Download/sala_pid.txt; input keyevent 26",
                esAccionManual = true
            )
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
