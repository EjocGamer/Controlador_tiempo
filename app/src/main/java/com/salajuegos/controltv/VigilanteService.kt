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
import java.util.concurrent.ConcurrentHashMap

object EstadoSala {
    val online = ConcurrentHashMap<Int, Boolean>()
    val segundos = ConcurrentHashMap<Int, Int>()
    val esLibre = ConcurrentHashMap<Int, Boolean>()

    fun obtenerIdsEstaciones(context: Context): List<Int> {
        val prefs = context.getSharedPreferences("sala_tvs", Context.MODE_PRIVATE)
        val raw = prefs.getString("lista_ids", null)
        if (raw == null) {
            // Si ya existían configuraciones previas en la app, las conserva; si es nueva, arranca con [1]
            val existentes = (1..6).filter { id ->
                prefs.contains("nombre_$id") \vert{}\vert{} prefs.contains("ip_$id")
            }
            val iniciales = if (existentes.isNotEmpty()) existentes else listOf(1)
            guardarIdsEstaciones(context, iniciales)
            return iniciales
        }
        return raw.split(",").mapNotNull { it.trim().toIntOrNull() }
    }

    fun guardarIdsEstaciones(context: Context, ids: List<Int>) {
        context.getSharedPreferences("sala_tvs", Context.MODE_PRIVATE).edit()
            .putString("lista_ids", ids.joinToString(","))
            .apply()
    }
}

class VigilanteService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val avisoEnviado = ConcurrentHashMap<Int, Boolean>()
    private val estadoTextoNotif = ConcurrentHashMap<Int, String>()

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
            .setContentTitle("⏳ $nombreEstacion: quedan$tiempoTexto")
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
                val ids = EstadoSala.obtenerIdsEstaciones(this@VigilanteService)

                // Limpia estados de estaciones eliminadas
                estadoTextoNotif.keys.retainAll(ids.toSet())
                avisoEnviado.keys.retainAll(ids.toSet())

                val tareas = ids.map { id ->
                    async {
                        val ip = prefs.getString("ip_$id", "192.168.1.${100 + id}") ?: return@async
                        val nombre = prefs.getString("nombre_$id", "Estación $id") ?: "Estación $id"
                        verificarYControlarTv(id, nombre, ip)
                    }
                }
                tareas.awaitAll()

                val lineas = ids.mapNotNull { id -> estadoTextoNotif[id]?.takeIf { it.isNotEmpty() } }
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
            "cat /sdcard/Download/sala_fin.txt 2>/dev/null; echo '|'; cat /sdcard/Download/sala_libre.txt 2>/dev/null; echo '|'; date +%s"
        )
        if (salida == null) {
            EstadoSala.online[id] = false
            estadoTextoNotif[id] = ""
            return
        }

        val partes = salida.split("|")
        if (partes.size < 3) return

        val ahora = partes[2].trim().toLongOrNull() ?: return
        if (ahora < 1700000000L) return

        EstadoSala.online[id] = true

        val textoFin = partes[0].trim()
        val textoLibre = partes[1].trim()

        // 1. Modo Tiempo Libre
        if (textoLibre.isNotEmpty()) {
            val inicioLibre = textoLibre.toLongOrNull() ?: 0L
            if (inicioLibre > 1700000000L && ahora >= inicioLibre) {
                val transcurrido = (ahora - inicioLibre).toInt()
                if (transcurrido <= 86400) {
                    EstadoSala.esLibre[id] = true
                    EstadoSala.segundos[id] = transcurrido
                    avisoEnviado[id] = false
                    val h = transcurrido / 3600
                    val m = (transcurrido % 3600) / 60
                    val s = transcurrido % 60
                    val reloj = if (h > 0) String.format("%dh %02dm %02ds", h, m, s) else String.format("%02dm %02ds", m, s)
                    estadoTextoNotif[id] = "⏱️ $nombre (Libre):$reloj"
                    return
                }
            }
        }

        EstadoSala.esLibre[id] = false

        // 2. Modo Temporizador normal
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
            estadoTextoNotif[id] = "$icono $nombre:$reloj"
        } else {
            EstadoSala.segundos[id] = 0
            estadoTextoNotif[id] = ""
        }

        if (restante > 120) {
            avisoEnviado[id] = false
        }

        if (restante in 1..120 && avisoEnviado[id] != true) {
            avisoEnviado[id] = true
            lanzarAlertaDependiente(id, nombre, restante)
        }

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
