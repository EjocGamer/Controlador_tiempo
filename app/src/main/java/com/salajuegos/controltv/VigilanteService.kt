package com.salajuegos.controltv

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VigilanteService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val numTvs = 6
    private val avisoEnviado = BooleanArray(numTvs + 1) { false }
    private val estadoTextoNotif = Array(numTvs + 1) { "" }

    override fun onCreate() {
        super.onCreate()
        crearCanalesNotificacion()
        actualizarNotificacionEnVivo("Iniciando supervisión de pantallas...")
        iniciarBucleVigilancia()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
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

    private fun registrarSesionValidadaEnReporte(nombreEstacion: String, minutosTotales: Int, etiquetaPlan: String) {
        if (minutosTotales <= 0) return
        val prefs = getSharedPreferences("ejoc_reportes", Context.MODE_PRIVATE)
        val fechaHoy = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val clave = "sesiones_$fechaHoy"

        val actualStr = prefs.getString(clave, "[]") ?: "[]"
        val array = try { JSONArray(actualStr) } catch (_: Exception) { JSONArray() }

        val obj = JSONObject().apply {
            put("estacion", nombreEstacion)
            put("minutos", minutosTotales)
            put("plan", etiquetaPlan)
            put("hora", SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()))
        }
        array.put(obj)
        prefs.edit().putString(clave, array.toString()).apply()
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

                val lineas = estadoTextoNotif.filter { it.isNotEmpty() }
                val textoFinal = if (lineas.isEmpty()) {
                    "Ninguna estación con tiempo activo"
                } else {
                    lineas.joinToString("\n")
                }
                actualizarNotificacionEnVivo(textoFinal)

                delay(5000)
            }
        }
    }

    private suspend fun verificarYControlarTv(id: Int, nombre: String, ip: String) {
        val salida = AdbManager.ejecutar(this, ip, "cat /sdcard/Download/sala_fin.txt 2>/dev/null; echo '|'; cat /sdcard/Download/sala_libre.txt 2>/dev/null; echo '|'; date +%s")
        if (salida == null) {
            estadoTextoNotif[id] = ""
            return
        }
        val partes = salida.split("|")
        if (partes.size < 3) return

        val ahora = partes[2].trim().toLongOrNull() ?: return
        if (ahora < 1700000000L) return

        val textoFin = partes[0].trim()
        val textoLibre = partes[1].trim()

        // 1. Verificar Modo Tiempo Libre
        if (textoLibre.isNotEmpty()) {
            val inicioLibre = textoLibre.toLongOrNull() ?: 0L
            if (inicioLibre > 1700000000L && ahora >= inicioLibre) {
                val transcurrido = (ahora - inicioLibre).toInt()
                val h = transcurrido / 3600
                val m = (transcurrido % 3600) / 60
                val s = transcurrido % 60
                val reloj = if (h > 0) String.format("%dh %02dm %02ds", h, m, s) else String.format("%02dm %02ds", m, s)
                estadoTextoNotif[id] = "⏱️ $nombre (Libre): $reloj"
                return
            }
        }

        // 2. Verificar Modo Temporizador normal
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

        // Al llegar a los últimos 2 minutos: alerta al dependiente y valida la sesión en el reporte diario
        if (restante in 1..120 && !avisoEnviado[id]) {
            avisoEnviado[id] = true
            lanzarAlertaDependiente(id, nombre, restante)

            val prefs = getSharedPreferences("sala_tvs", Context.MODE_PRIVATE)
            val minsAcumulados = prefs.getInt("mins_sesion_$id", 0)
            val yaValidada = prefs.getBoolean("validada_sesion_$id", false)
            if (minsAcumulados > 0 && !yaValidada) {
                prefs.edit().putBoolean("validada_sesion_$id", true).apply()
                val etiqueta = formatearPlan(minsAcumulados)
                registrarSesionValidadaEnReporte(nombre, minsAcumulados, etiqueta)
            }
        }

        // Tiempo agotado: borra archivos, limpia sesión y apaga el TV
        if (restante <= 0) {
            avisoEnviado[id] = false
            estadoTextoNotif[id] = ""
            getSharedPreferences("sala_tvs", Context.MODE_PRIVATE).edit()
                .putInt("mins_sesion_$id", 0)
                .putBoolean("validada_sesion_$id", false)
                .apply()
            AdbManager.ejecutar(this, ip, "rm -f /sdcard/Download/sala_fin.txt /sdcard/Download/sala_pid.txt; input keyevent 26")
        }
    }

    private fun formatearPlan(mins: Int): String {
        return when {
            mins < 60 -> "$mins min"
            mins % 60 == 0 -> "${mins / 60} h"
            else -> "${mins / 60}h ${mins % 60}m"
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
