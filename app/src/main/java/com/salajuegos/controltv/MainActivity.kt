package com.salajuegos.controltv

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var contenedorTvs: LinearLayout
    private val tarjetaExpandida = BooleanArray(EstadoSala.NUM_TVS + 1) { false }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#090D16")) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(32))
        }

        val titulo = TextView(this).apply {
            text = "🎮 EjocGames Time"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
        }
        val subtitulo = TextView(this).apply {
            text = "Control de Pantallas, Tiempos y Audio"
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(20))
        }
        root.addView(titulo)
        root.addView(subtitulo)

        contenedorTvs = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(contenedorTvs)
        scroll.addView(root)
        setContentView(scroll)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        val svcIntent = Intent(this, VigilanteService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svcIntent)
        } else {
            startService(svcIntent)
        }

        construirTarjetas()
        iniciarRefrescoVisual()
    }

    private fun dp(valor: Int): Int {
        return (valor * resources.displayMetrics.density).toInt()
    }

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

    private fun crearBotonGrande(texto: String, colorHex: String, textoSize: Float = 14f, bordeHex: String? = null): Button {
        return Button(this).apply {
            text = texto
            textSize = textoSize
            isAllCaps = false
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = crearFondoRedondeado(colorHex, 12f, bordeHex)
            setPadding(dp(6), dp(14), dp(6), dp(14))
            minHeight = dp(48)
            minimumHeight = dp(48)
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
        for (i in 1..EstadoSala.NUM_TVS) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = crearFondoRedondeado("#162032", 18f, "#26354D")
                setPadding(dp(18), dp(18), dp(18), dp(18))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dp(22)) }
                tag = "card_$i"
            }

            // Cabecera: Estado + Nombre + IP + Editar
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val bloqueInfo = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val txtNombre = TextView(this).apply {
                text = "🔴 ${obtenerNombre(i)}"
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                tag = "titulo_$i"
            }
            val txtSubIp = TextView(this).apply {
                text = "IP: ${obtenerIp(i)}"
                textSize = 13f
                setTextColor(Color.parseColor("#94A3B8"))
                tag = "subip_$i"
            }
            bloqueInfo.addView(txtNombre)
            bloqueInfo.addView(txtSubIp)

            val btnConfig = crearBotonGrande("⚙️ Editar", "#334155", 13f).apply {
                setPadding(dp(16), dp(10), dp(16), dp(10))
                minHeight = dp(42)
                minimumHeight = dp(42)
                setOnClickListener { mostrarDialogoConfig(i) }
            }
            header.addView(bloqueInfo)
            header.addView(btnConfig)

            // Reloj grande
            val txtReloj = TextView(this).apply {
                text = "--:--:--"
                textSize = 42f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#94A3B8"))
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(16))
                tag = "reloj_$i"
            }

            // Fila Principal (Siempre visible): +15m, +30m, +1h, +2h
            val filaPrincipal = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(
                Triple(15, "+15m", "#0284C7"),
                Triple(30, "+30m", "#0284C7"),
                Triple(60, "+1h", "#2563EB"),
                Triple(120, "+2h", "#4F46E5")
            ).forEachIndexed { idx, (mins, etiqueta, color) ->
                val btn = crearBotonGrande(etiqueta, color, 15f).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(if (idx == 0) 0 else dp(4), 0, if (idx == 3) 0 else dp(4), dp(12))
                    }
                    setOnClickListener { sumarTiempoTv(i, mins) }
                }
                filaPrincipal.addView(btn)
            }

            // Panel Desplegable con controles secundarios
            val panelExtra = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (tarjetaExpandida[i]) View.VISIBLE else View.GONE
                setPadding(0, dp(4), 0, dp(10))
                tag = "extra_$i"
            }

            // Subfila Extra 1: +1m Prueba y +5m Cortesía
            val filaExtraTiempo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val btn1m = crearBotonGrande("+1m Prueba", "#D97706", 14f).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 0, dp(5), dp(10))
                }
                setOnClickListener { sumarTiempoTv(i, 1) }
            }
            val btn5m = crearBotonGrande("+5m Cortesía", "#0D9488", 14f).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(5), 0, 0, dp(10))
                }
                setOnClickListener { sumarTiempoTv(i, 5) }
            }
            filaExtraTiempo.addView(btn1m)
            filaExtraTiempo.addView(btn5m)

            // Subfila Extra 2: Volumen (Vol -, Mute, Vol +)
            val filaAudio = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val btnVolDown = crearBotonGrande("🔉 Vol -", "#1E293B", 14f, "#334155").apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 0, dp(5), dp(10))
                }
                setOnClickListener { enviarTeclaRapida(i, 25) }
            }
            val btnMute = crearBotonGrande("🔇 Mute", "#1E293B", 14f, "#334155").apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(5), 0, dp(5), dp(10))
                }
                setOnClickListener { enviarTeclaRapida(i, 164) }
            }
            val btnVolUp = crearBotonGrande("🔊 Vol +", "#1E293B", 14f, "#334155").apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(5), 0, 0, dp(10))
                }
                setOnClickListener { enviarTeclaRapida(i, 24) }
            }
            filaAudio.addView(btnVolDown)
            filaAudio.addView(btnMute)
            filaAudio.addView(btnVolUp)

            // Subfila Extra 3: Encender, Quitar, Apagar
            val filaEnergia = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val btnEncender = crearBotonGrande("⚡ Encender", "#16A34A", 13f).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 0, dp(5), 0)
                }
                setOnClickListener { encenderTv(i) }
            }
            val btnCancelar = crearBotonGrande("⏹ Quitar", "#475569", 13f).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(5), 0, dp(5), 0)
                }
                setOnClickListener { quitarTimerTv(i) }
            }
            val btnApagar = crearBotonGrande("🛑 Apagar", "#DC2626", 13f).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(5), 0, 0, 0)
                }
                setOnClickListener { apagarTvAhora(i) }
            }
            filaEnergia.addView(btnEncender)
            filaEnergia.addView(btnCancelar)
            filaEnergia.addView(btnApagar)

            panelExtra.addView(filaExtraTiempo)
            panelExtra.addView(filaAudio)
            panelExtra.addView(filaEnergia)

            val btnToggle = crearBotonGrande(
                if (tarjetaExpandida[i]) "▲ Ocultar controles" else "▼ Más controles (Audio / Apagar / +1m)",
                "#1E293B",
                13f,
                "#334155"
            ).apply {
                setTextColor(Color.parseColor("#CBD5E1"))
                minHeight = dp(42)
                minimumHeight = dp(42)
                setOnClickListener {
                    tarjetaExpandida[i] = !tarjetaExpandida[i]
                    panelExtra.visibility = if (tarjetaExpandida[i]) View.VISIBLE else View.GONE
                    text = if (tarjetaExpandida[i]) "▲ Ocultar controles" else "▼ Más controles (Audio / Apagar / +1m)"
                }
            }

            card.addView(header)
            card.addView(txtReloj)
            card.addView(filaPrincipal)
            card.addView(panelExtra)
            card.addView(btnToggle)
            contenedorTvs.addView(card)
        }
    }

    private fun mostrarDialogoConfig(id: Int) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }

        val lblNombre = TextView(this).apply { text = "Nombre de la estación (ej. PS5 - 1):" }
        val inputNombre = EditText(this).apply {
            setText(obtenerNombre(id))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }

        val lblIp = TextView(this).apply {
            text = "Dirección IP del TV:"
            setPadding(0, dp(12), 0, 0)
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
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun enviarTeclaRapida(id: Int, keycode: Int) {
        lifecycleScope.launch {
            val res = AdbManager.ejecutar(this@MainActivity, obtenerIp(id), "input keyevent $keycode", esAccionManual = true)
            if (res == null) {
                Toast.makeText(this@MainActivity, "${obtenerNombre(id)} sin conexión", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun encenderTv(id: Int) {
        lifecycleScope.launch {
            val ip = obtenerIp(id)
            val res = AdbManager.ejecutar(this@MainActivity, ip, "input keyevent 224", esAccionManual = true)
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
            val cmd = "rm -f /sdcard/Download/sala_fin.txt /sdcard/Download/sala_pid.txt"
            val res = AdbManager.ejecutar(this@MainActivity, ip, cmd, esAccionManual = true)
            if (res != null) {
                EstadoSala.segundos[id] = 0
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
            val cmd = "rm -f /sdcard/Download/sala_fin.txt /sdcard/Download/sala_pid.txt; input keyevent 26"
            val res = AdbManager.ejecutar(this@MainActivity, ip, cmd, esAccionManual = true)
            if (res != null) {
                EstadoSala.segundos[id] = 0
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
            val salida = AdbManager.ejecutar(
                this@MainActivity,
                ip,
                "cat /sdcard/Download/sala_fin.txt 2>/dev/null; echo '|'; date +%s",
                esAccionManual = true
            )
            if (salida == null) {
                Toast.makeText(this@MainActivity, "${obtenerNombre(id)} sin conexión", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val partes = salida.split("|")
            val ahora = partes.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L
            val finActual = partes.getOrNull(0)?.trim()?.toLongOrNull() ?: 0L
            val baseSegundos = if (ahora > 1700000000L && finActual > ahora) (finActual - ahora).toInt() else 0

            val totalSeg = baseSegundos + (minutosExtra * 60)
            val cmd = "FIN=\$((\$(date +%s) + $totalSeg)); echo \$FIN > /sdcard/Download/sala_fin.txt"
            val res = AdbManager.ejecutar(this@MainActivity, ip, cmd, esAccionManual = true)
            if (res != null) {
                EstadoSala.online[id] = true
                EstadoSala.segundos[id] = totalSeg
                pintarTarjeta(id)
                Toast.makeText(this@MainActivity, "+${minutosExtra}m en ${obtenerNombre(id)}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun pintarTarjeta(id: Int) {
        val ip = obtenerIp(id)
        val nombre = obtenerNombre(id)
        val txtReloj = contenedorTvs.findViewWithTag<TextView>("reloj_$id") ?: return
        val txtTitulo = contenedorTvs.findViewWithTag<TextView>("titulo_$id") ?: return
        val txtSubIp = contenedorTvs.findViewWithTag<TextView>("subip_$id")

        txtSubIp?.text = "IP: $ip"

        if (!EstadoSala.online[id]) {
            txtTitulo.text = "🔴 $nombre"
            txtReloj.text = "--:--:--"
            txtReloj.setTextColor(Color.parseColor("#EF4444"))
        } else {
            txtTitulo.text = "🟢 $nombre"
            val seg = EstadoSala.segundos[id]
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

    private fun iniciarRefrescoVisual() {
        lifecycleScope.launch {
            while (isActive) {
                for (i in 1..EstadoSala.NUM_TVS) {
                    if (EstadoSala.online[i] && EstadoSala.segundos[i] > 0) {
                        EstadoSala.segundos[i] -= 1
                    }
                    pintarTarjeta(i)
                }
                delay(1000)
            }
        }
    }
}
