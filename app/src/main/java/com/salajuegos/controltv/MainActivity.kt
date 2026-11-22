package com.salajuegos.controltv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.util.Base64
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tananaev.adblib.AdbBase64
import com.tananaev.adblib.AdbConnection
import com.tananaev.adblib.AdbCrypto
import kotlinx.coroutines.*
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

class MainActivity : AppCompatActivity() {

    private lateinit var crypto: AdbCrypto
    private lateinit var contenedorTvs: LinearLayout
    private val numTvs = 6

    private val segundosLocales = IntArray(numTvs + 1) { 0 }
    private val estadoOnline = BooleanArray(numTvs + 1) { false }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        crypto = cargarOCrearLlavesAdb()

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#090D16")) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 32)
        }

        val titulo = TextView(this).apply {
            text = "🎮 EjocGames Time"
            textSize = 25f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 4)
        }
        val subtitulo = TextView(this).apply {
            text = "Control de Pantallas, Tiempos y Audio"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20)
        }
        root.addView(titulo)
        root.addView(subtitulo)

        contenedorTvs = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(contenedorTvs)
        scroll.addView(root)
        setContentView(scroll)

        construirTarjetas()
        iniciarRelojVisual()
        iniciarSincronizacionTv()
    }

    // Crea fondos con bordes redondeados y contorno sutil
    private fun crearFondoRedondeado(colorHex: String, radioDp: Float, bordeHex: String? = null): GradientDrawable {
        val density = resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radioDp * density
            setColor(Color.parseColor(colorHex))
            if (bordeHex != null) {
                setStroke((1.5f * density).toInt(), Color.parseColor(bordeHex))
            }
        }
    }

    private fun crearBotonEstilizado(texto: String, colorHex: String, textoSize: Float = 12f): Button {
        return Button(this).apply {
            text = texto
            textSize = textoSize
            isAllCaps = false
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = crearFondoRedondeado(colorHex, 10f)
            setPadding(8, 12, 8, 12)
            minHeight = 0
            minimumHeight = 0
        }
    }

    private fun cargarOCrearLlavesAdb(): AdbCrypto {
        val base64 = AdbBase64 { data -> Base64.encodeToString(data, Base64.NO_WRAP) }
        val pubFile = File(filesDir, "adb_pub.key")
        val privFile = File(filesDir, "adb_priv.key")
        return if (pubFile.exists() && privFile.exists()) {
            AdbCrypto.loadAdbKeyPair(base64, privFile, pubFile)
        } else {
            val nueva = AdbCrypto.generateAdbKeyPair(base64)
            nueva.saveAdbKeyPair(privFile, pubFile)
            nueva
        }
    }

    private fun obtenerNombre(id: Int): String {
        val prefs = getSharedPreferences("sala_tvs", Context.MODE_PRIVATE)
        return prefs.getString("nombre_$id", "Estación $id") ?: "Estación $id"
    }

    private fun obtenerIp(id: Int): String {
        val prefs = getSharedPreferences("sala_tvs", Context.MODE_PRIVATE)
        return prefs.getString("ip_$id", "192.168.1.${100 + id}") ?: "192.168.1.${100 + id}"
    }

    private fun guardarConfig(id: Int, nombre: String, ip: String) {
        getSharedPreferences("sala_tvs", Context.MODE_PRIVATE).edit()
            .putString("nombre_$id", nombre.ifBlank { "Estación $id" })
            .putString("ip_$id", ip)
            .apply()
    }

    private fun construirTarjetas() {
        contenedorTvs.removeAllViews()
        for (i in 1..numTvs) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = crearFondoRedondeado("#162032", 16f, "#26354D")
                setPadding(24, 20, 24, 20)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 20) }
                layoutParams = params
                tag = "card_$i"
            }

            // Cabecera: Nombre + Botón Editar
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val txtNombre = TextView(this).apply {
                text = "🔴 ${obtenerNombre(i)} (${obtenerIp(i)})"
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                tag = "titulo_$i"
            }
            val btnConfig = crearBotonEstilizado("⚙️ Editar", "#334155", 12f).apply {
                setPadding(20, 10, 20, 10)
                setOnClickListener { mostrarDialogoConfig(i) }
            }
            header.addView(txtNombre)
            header.addView(btnConfig)

            // Reloj central
            val txtReloj = TextView(this).apply {
                text = "--:--:--"
                textSize = 36f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#94A3B8"))
                gravity = Gravity.CENTER
                setPadding(0, 10, 0, 14)
                tag = "reloj_$i"
            }

            // Fila 1: Tiempos (+5m, +15m, +30m, +1h, +2h)
            val filaSumar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val tiempos = listOf(
                Triple(5, "+5m", "#0D9488"),
                Triple(15, "+15m", "#0284C7"),
                Triple(30, "+30m", "#0284C7"),
                Triple(60, "+1h", "#2563EB"),
                Triple(120, "+2h", "#4F46E5")
            )
            tiempos.forEach { (mins, etiqueta, color) ->
                val btn = crearBotonEstilizado(etiqueta, color, 12f).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(4, 0, 4, 10)
                    }
                    setOnClickListener { sumarTiempoTv(i, mins) }
                }
                filaSumar.addView(btn)
            }

            // Fila 2: Control de Volumen (Vol -, Mute, Vol +)
            val filaAudio = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val btnVolDown = crearBotonEstilizado("🔉 Vol -", "#1E293B", 12f).apply {
                background = crearFondoRedondeado("#1E293B", 10f, "#334155")
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(4, 0, 4, 10)
                }
                setOnClickListener { enviarTeclaRapida(i, 25, "Bajando volumen") }
            }
            val btnMute = crearBotonEstilizado("🔇 Mute", "#1E293B", 12f).apply {
                background = crearFondoRedondeado("#1E293B", 10f, "#334155")
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(4, 0, 4, 10)
                }
                setOnClickListener { enviarTeclaRapida(i, 164, "Silencio / Sonido") }
            }
            val btnVolUp = crearBotonEstilizado("🔊 Vol +", "#1E293B", 12f).apply {
                background = crearFondoRedondeado("#1E293B", 10f, "#334155")
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(4, 0, 4, 10)
                }
                setOnClickListener { enviarTeclaRapida(i, 24, "Subiendo volumen") }
            }
            filaAudio.addView(btnVolDown)
            filaAudio.addView(btnMute)
            filaAudio.addView(btnVolUp)

            // Fila 3: Encender, Quitar Timer, Apagar Ya
            val filaAccion = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val btnEncender = crearBotonEstilizado("⚡ Encender", "#16A34A", 12f).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(4, 0, 4, 0)
                }
                setOnClickListener { encenderTv(i) }
            }
            val btnCancelar = crearBotonEstilizado("⏹ Quitar Timer", "#475569", 12f).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(4, 0, 4, 0)
                }
                setOnClickListener { quitarTimerTv(i) }
            }
            val btnApagar = crearBotonEstilizado("🛑 Apagar Ya", "#DC2626", 12f).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(4, 0, 4, 0)
                }
                setOnClickListener { apagarTvAhora(i) }
            }
            filaAccion.addView(btnEncender)
            filaAccion.addView(btnCancelar)
            filaAccion.addView(btnApagar)

            card.addView(header)
            card.addView(txtReloj)
            card.addView(filaSumar)
            card.addView(filaAudio)
            card.addView(filaAccion)
            contenedorTvs.addView(card)
        }
    }

    private fun mostrarDialogoConfig(id: Int) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 12)
        }

        val lblNombre = TextView(this).apply { text = "Nombre de la estación (ej. PS5 - 1):" }
        val inputNombre = EditText(this).apply {
            setText(obtenerNombre(id))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }

        val lblIp = TextView(this).apply {
            text = "Dirección IP del TV:"
            setPadding(0, 16, 0, 0)
        }
        val inputIp = EditText(this).apply {
            setText(obtenerIp(id))
            inputType = InputType.TYPE_CLASS_NUMBER
            keyListener = DigitsKeyListener.getInstance("0123456789.")
        }

        layout.addView(lblNombre)
        layout.addView(inputNombre)
        layout.addView(lblIp)
        layout.addView(inputIp)

        AlertDialog.Builder(this)
            .setTitle("Configurar Estación $id")
            .setView(layout)
            .setPositiveButton("Guardar") { _, _ ->
                guardarConfig(id, inputNombre.text.toString().trim(), inputIp.text.toString().trim())
                construirTarjetas()
                lifecycleScope.launch { actualizarUnTv(id) }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private suspend fun ejecutarAdbShell(ip: String, comando: String): String? = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        var conn: AdbConnection? = null
        try {
            socket = Socket()
            socket.soTimeout = 1500
            socket.connect(InetSocketAddress(ip, 5555), 1500)
            conn = AdbConnection.create(socket, crypto)
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
        } catch (e: Exception) {
            null
        } finally {
            try { conn?.close() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun enviarTeclaRapida(id: Int, keycode: Int, mensaje: String) {
        lifecycleScope.launch {
            val res = ejecutarAdbShell(obtenerIp(id), "input keyevent $keycode")
            if (res == null) {
                Toast.makeText(this@MainActivity, "${obtenerNombre(id)} sin conexión", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun encenderTv(id: Int) {
        lifecycleScope.launch {
            val ip = obtenerIp(id)
            val res = ejecutarAdbShell(ip, "input keyevent 224")
            if (res != null) {
                Toast.makeText(this@MainActivity, "Encendiendo ${obtenerNombre(id)}...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "TV en reposo profundo (usa el control físico)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun quitarTimerTv(id: Int) {
        lifecycleScope.launch {
            val ip = obtenerIp(id)
            val cmd = "kill -9 \$(cat /sdcard/Download/sala_pid.txt 2>/dev/null) 2>/dev/null; rm -f /sdcard/Download/sala_fin.txt /sdcard/Download/sala_pid.txt"
            val res = ejecutarAdbShell(ip, cmd)
            if (res != null) {
                segundosLocales[id] = 0
                pintarTarjeta(id)
                Toast.makeText(this@MainActivity, "Timer quitado en ${obtenerNombre(id)}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "Sin conexión con ${obtenerNombre(id)}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun apagarTvAhora(id: Int) {
        lifecycleScope.launch {
            val ip = obtenerIp(id)
            val cmd = "kill -9 \$(cat /sdcard/Download/sala_pid.txt 2>/dev/null) 2>/dev/null; rm -f /sdcard/Download/sala_fin.txt /sdcard/Download/sala_pid.txt; input keyevent 26"
            val res = ejecutarAdbShell(ip, cmd)
            if (res != null) {
                segundosLocales[id] = 0
                pintarTarjeta(id)
                Toast.makeText(this@MainActivity, "Apagando ${obtenerNombre(id)}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "Sin conexión con ${obtenerNombre(id)}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sumarTiempoTv(id: Int, minutosExtra: Int) {
        lifecycleScope.launch {
            val ip = obtenerIp(id)
            val actual = leerSegundosRestantes(ip)
            if (actual == null) {
                Toast.makeText(this@MainActivity, "${obtenerNombre(id)} sin conexión", Toast.LENGTH_SHORT).show()
                return@launch
            }

            // Si actual == -1 (lectura incompleta), usamos segundosLocales[id]
            val baseSegundos = if (actual >= 0) actual else segundosLocales[id]
            val totalSeg = baseSegundos + (minutosExtra * 60)

            // Advertencia visual triple faltando 2 minutos (120s):
            // 1. Notificación del sistema
            // 2. Pulso doble de volumen (baja y sube 2 puntos) para que salte la barra en pantalla sobre cualquier HDMI
            val loopVigilancia = "AVISO=0; " +
                "while [ -f /sdcard/Download/sala_fin.txt ]; do " +
                "FIN=\$(cat /sdcard/Download/sala_fin.txt 2>/dev/null); " +
                "NOW=\$(date +%s); " +
                "REST=\$((FIN - NOW)); " +
                "if [ \$REST -le 120 ] && [ \$REST -gt 0 ] && [ \$AVISO -eq 0 ]; then " +
                "AVISO=1; " +
                "cmd notification post -S bigtext -t 'EjocGames' 'alerta_tv' 'Quedan 2 minutos de juego' >/dev/null 2>&1; " +
                "input keyevent 25; input keyevent 25; sleep 1; input keyevent 24; input keyevent 24; " +
                "fi; " +
                "if [ \$REST -le 0 ]; then " +
                "rm -f /sdcard/Download/sala_fin.txt /sdcard/Download/sala_pid.txt; " +
                "input keyevent 26; " +
                "break; " +
                "fi; " +
                "sleep 5; " +
                "done"

            val cmd = "kill -9 \$(cat /sdcard/Download/sala_pid.txt 2>/dev/null) 2>/dev/null; " +
                      "FIN=\$((\$(date +%s) + $totalSeg)); echo \$FIN > /sdcard/Download/sala_fin.txt; " +
                      "setsid sh -c \"$loopVigilancia\" </dev/null >/dev/null 2>&1 & echo \$! > /sdcard/Download/sala_pid.txt"

            val res = ejecutarAdbShell(ip, cmd)
            if (res != null) {
                segundosLocales[id] = totalSeg
                estadoOnline[id] = true
                pintarTarjeta(id)
                Toast.makeText(this@MainActivity, "+${minutosExtra}m en ${obtenerNombre(id)}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Devuelve: null = TV desconectado, -1 = lectura incompleta (ignorar), >= 0 = segundos reales válidos
    private suspend fun leerSegundosRestantes(ip: String): Int? {
        val salida = ejecutarAdbShell(ip, "cat /sdcard/Download/sala_fin.txt 2>/dev/null; echo '|'; date +%s") ?: return null
        val partes = salida.split("|")
        if (partes.size < 2) return -1

        val ahora = partes[1].trim().toLongOrNull() ?: return -1
        // Validar que 'ahora' sea un timestamp Unix real (> año 2023) para evitar el bug de miles de horas
        if (ahora < 1700000000L) return -1

        val textoFin = partes[0].trim()
        if (textoFin.isEmpty()) return 0

        val fin = textoFin.toLongOrNull() ?: return -1
        val diff = (fin - ahora).toInt()

        // Descartar valores absurdos (> 24 horas = 86400s) producto de paquetes cortados
        if (diff > 86400) return -1
        return if (diff > 0) diff else 0
    }

    private fun pintarTarjeta(id: Int) {
        val ip = obtenerIp(id)
        val nombre = obtenerNombre(id)
        val txtReloj = contenedorTvs.findViewWithTag<TextView>("reloj_$id") ?: return
        val txtTitulo = contenedorTvs.findViewWithTag<TextView>("titulo_$id") ?: return

        if (!estadoOnline[id]) {
            txtTitulo.text = "🔴 $nombre ($ip)"
            txtReloj.text = "--:--:--"
            txtReloj.setTextColor(Color.parseColor("#EF4444"))
        } else {
            txtTitulo.text = "🟢 $nombre ($ip)"
            val seg = segundosLocales[id]
            val h = seg / 3600
            val m = (seg % 3600) / 60
            val s = seg % 60
            txtReloj.text = String.format("%02d:%02d:%02d", h, m, s)
            txtReloj.setTextColor(
                when {
                    seg == 0 -> Color.parseColor("#94A3B8")
                    seg <= 120 -> Color.parseColor("#F59E0B")
                    else -> Color.parseColor("#22C55E")
                }
            )
        }
    }

    private suspend fun actualizarUnTv(id: Int) {
        val seg = leerSegundosRestantes(obtenerIp(id))
        if (seg == null) {
            estadoOnline[id] = false
        } else {
            estadoOnline[id] = true
            // Solo sobreescribir el reloj si la lectura fue limpia (seg >= 0)
            if (seg >= 0) {
                segundosLocales[id] = seg
            }
        }
        pintarTarjeta(id)
    }

    private fun iniciarRelojVisual() {
        lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                for (i in 1..numTvs) {
                    if (estadoOnline[i] && segundosLocales[i] > 0) {
                        segundosLocales[i] -= 1
                        pintarTarjeta(i)
                    }
                }
            }
        }
    }

    private fun iniciarSincronizacionTv() {
        lifecycleScope.launch {
            while (isActive) {
                val tareas = (1..numTvs).map { id ->
                    async { actualizarUnTv(id) }
                }
                tareas.awaitAll()
                delay(4000)
            }
        }
    }
}
