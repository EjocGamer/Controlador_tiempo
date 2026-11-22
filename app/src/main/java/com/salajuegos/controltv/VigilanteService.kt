package com.salajuegos.controltv

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class VigilanteService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val numTvs = 6
    private val avisoEnviado = BooleanArray(numTvs + 1) { false }
    private val estadoTextoNotif = Array(numTvs + 1) { "" }

    override fun onCreate() {
        super.onCreate()
        crearCanalesNotificacion()
        actualizarNotificacionEnVivo("Conectando con estaciones...")
        iniciarBucleVigilancia()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    // Si el dependiente cierra la app de tareas recientes, el servicio se reactiva automáticamente
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

            // Canal configurado como ALARMA para sonar incluso si el teléfono está en silencio
            val sonidoAlarma = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            val audioAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val canalAlertas = NotificationChannel(
                "ejoc_alarma_critica_v2",
                "Alerta Sonora de 2 Minutos",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Suena incluso en silencio cuando faltan 2 minutos para apagar un TV"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 200, 600)
                setSound(sonidoAlarma, audioAttrs)
                setBypassDnd(true)
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

        val sonidoAlarma = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val alerta = NotificationCompat.Builder(this, "ejoc_alarma_critica_v2")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⏳ ¡$nombreEstacion por terminar!")
            .setContentText("Quedan $tiempoTexto para el apagado automático. Toca para agregar tiempo.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSound(sonidoAlarma)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 600, 200, 600))
            .setContentIntent(obtenerPendingIntentPrincipal())
            .build()

        nm.notify(200 + id, alerta)
    }

    private fun iniciarBucleVigilancia() {
        scope.launch {
            // Espera inicial de 3s para no competir con la primera conexión de MainActivity
            delay(3000)
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
        val salida = AdbManager.ejecutar(this, ip, "cat /sdcard/Download/sala_fin.txt 2>/dev/null; echo '|'; date +%s")
        if (salida == null) {
            estadoTextoNotif[id] = ""
            return
        }
        val partes = salida.split("|")
        if (partes.size < 2) return

        val ahora = partes[1].trim().toLongOrNull() ?: return
        if (ahora < 1700000000L) return

        val textoFin = partes[0].trim()
        if (textoFin.isEmpty()) {
            avisoEnviado[id] = false
            estadoTextoNotif[id] = ""
            return
        }

        val fin = textoFin.toLongOrNull() ?: return
        val restante = (fin - ahora).toInt()
        if (restante > 86400) return

        if (restante > 0) {
            val h = restante / 3600
            val m = (restante % 3600) / 60
            val s = restante % 60
            val reloj = if (h > 0) String.format("%dh %02dm %02ds", h, m, s) else String.format("%02dm %02ds", m, s)
            val icono = if (restante <= 120) "⚠️" else "🟢"
            estadoTextoNotif[id] = "$icono $nombre: $reloj"
        } else {
            estadoTextoNotif[id] = ""
        }

        if (restante > 120) {
            avisoEnviado[id] = false
        }

        // Faltando 2 minutos: dispara alerta sonora tipo alarma
        if (restante in 1..120 && !avisoEnviado[id]) {
            avisoEnviado[id] = true
            lanzarAlertaDependiente(id, nombre, restante)
        }

        // Tiempo agotado: borra el archivo y envía orden de apagado forzada
        if (restante <= 0) {
            avisoEnviado[id] = false
            estadoTextoNotif[id] = ""
            AdbManager.ejecutar(this, ip, "rm -f /sdcard/Download/sala_fin.txt /sdcard/Download/sala_pid.txt", esAccionManual = true)
            AdbManager.ejecutar(this, ip, "input keyevent 26", esAccionManual = true)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
