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
import kotlin.math.ceil

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
        sincronizarAhoraEnParalelo()
        iniciarRefrescoVisual()
    }

    override fun onResume() {
        super.onResume()
        sincronizarAhoraEnParalelo()
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

    private fun estaBloqueada(id: Int): Boolean {
        val prefs = getSharedPreferences("sala_tvs", Context.MODE_PRIVATE)
        return prefs.getBoolean("bloqueada_$id", false)
    }

    private fun alternarBloqueo(id: Int) {
        val nuevoEstado = !estaBloqueada(id)
        getSharedPreferences("sala_tvs", Context.MODE_PRIVATE).edit()
            .putBoolean("bloqueada_$id", nuevoEstado)
            .apply()
        construirTarjetas()
        pintarTarjeta(id)
        val msg = if (nuevoEstado) "🔒 ${obtenerNombre(id)} bloqueada (tiempos protegidos)" else "🔓 ${obtenerNombre(id)} desbloqueada"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun guardarConfig(id: Int, nombre: String, ip: String) {
        getSharedPreferences("sala_tvs", Context.MODE_PRIVATE).edit()
            .putString("nombre_$id", nombre.ifBlank { "Estación $id" })
            .putString("ip_$id", ip)
            .apply()
    }

    private fun crearBotonTiempoProtegido(id: Int, mins: Int, etiqueta: String, colorHex: String, bloqueada: Boolean): Button {
        val colorActivo = if (bloqueada) "#1E293B" else colorHex
        return crearBotonGrande(etiqueta, colorActivo, 15f, if (bloqueada) "#334155" else null).apply {
            alpha = if (bloqueada) 0.45f else 1.0f
            setOnClickListener {
                if (estaBloqueada(id)) {
                    Toast.makeText(this@MainActivity, "🔒 Estación bloqueada. Toca el candado para editar tiempo.", Toast.LENGTH_SHORT).show()
                } else {
                    sumarTiempoTv(id, mins)
                }
            }
        }
    }

    private fun construirTarjetas() {
        contenedorTvs.removeAllViews()
        for (i in 1..EstadoSala.NUM_TVS) {
            val bloqueada = estaBloqueada(i)

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = crearFondoRedondeado(
                    if (bloqueada) "#1C1926" else "#162032",
                    18f,
                    if (bloqueada) "#F59E0B" else "#26354D"
                )
                setPadding(dp(18), dp(18), dp(18), dp(18))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dp(22)) }
                tag = "card_$i"
            }

            // Cabecera: Estado + Nombre + IP + Botón Candado + Botón Editar
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val bloqueInfo = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val txtNombre = TextView(this).apply {
                text = if (EstadoSala.online[i]) "🟢 ${obtenerNombre(i)}" else "🔴 ${obtenerNombre(i)}"
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

            // Botón Candado
            val btnCandado = crearBotonGrande(
                if (bloqueada) "🔒" else "🔓",
                if (bloqueada) "#B45309" else "#1E293B",
                16f,
                if (bloqueada) "#F59E0B" else "#475569"
            ).apply {
                setPadding(dp(14), dp(8), dp(14), dp(8))
                minHeight = dp(42)
                minimumHeight = dp(42)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, dp(8), 0) }
                setOnClickListener { alternarBloqueo(i) }
            }

            val btnConfig = crearBotonGrande("⚙️", "#334155", 15f).apply {
                setPadding(dp(14), dp(8), dp(14), dp(8))
                minHeight = dp(42)
                minimumHeight = dp(42)
                setOnClickListener { mostrarDialogoConfig(i) }
            }
            header.addView(bloqueInfo)
            header.addView(btnCandado)
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

            // Fila Principal 1 (Minutos): +15m, +30m, +45m
            val filaMinutos = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(
                Triple(15, "+15m", "#0284C7"),
                Triple(30, "+30m", "#0284C7"),
                Triple(45, "+45m", "#0369A1")
            ).forEachIndexed { idx, (mins, etiqueta, color) ->
                val btn = crearBotonTiempoProtegido(i, mins, etiqueta, color, bloqueada).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(if (idx == 0) 0 else dp(5), 0, if (idx == 2) 0 else dp(5), dp(10))
                    }
                }
                filaMinutos.addView(btn)
            }

            // Fila Principal 2 (Horas): +1h, +2h
            val filaHoras = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(
                Triple(60, "+1h", "#2563EB"),
                Triple(120, "+2h", "#4F46E5")
            ).forEachIndexed { idx, (mins, etiqueta, color) ->
                val btn = crearBotonTiempoProtegido(i, mins, etiqueta, color, bloqueada).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(if (idx == 0) 0 else dp(5), 0, if (idx == 1) 0 else dp(5), dp(10))
                    }
                }
                filaHoras.addView(btn)
            }

            // Fila Principal 3 (Tiempo Libre): Iniciar Libre / Cerrar y Redondear 30m
            val filaLibre = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val btnIniciarLibre = crearBotonGrande(
                "⏱️ Tiempo Libre",
                if (bloqueada) "#1E293B" else "#0E7490",
                14f,
                if (bloqueada) "#334155" else null
            ).apply {
                alpha = if (bloqueada) 0.45f else 1.0f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 0, dp(5), dp(12))
                }
                setOnClickListener {
                    if (estaBloqueada(i)) {
                        Toast.makeText(this@MainActivity, "🔒 Estación bloqueada. Toca el candado para iniciar Tiempo Libre.", Toast.LENGTH_SHORT).show()
                    } else {
                        iniciarTiempoLibre(i)
                    }
                }
            }
            val btnCerrarLibre = crearBotonGrande(
                "💰 Cerrar Libre",
                if (bloqueada) "#1E293B" else "#059669",
                14f,
                if (bloqueada) "#334155" else null
            ).apply {
                alpha = if (bloqueada) 0.45f else 1.0f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(5), 0, 0, dp(12))
                }
                setOnClickListener {
                    if (estaBloqueada(i)) {
                        Toast.makeText(this@MainActivity, "🔒 Desbloquea la estación para cerrar Tiempo Libre.", Toast.LENGTH_SHORT).show()
                    } else {
                        cerrarYCobrarTiempoLibre(i)
                    }
                }
            }
            filaLibre.addView(btnIniciarLibre)
            filaLibre.addView(btnCerrarLibre)

            // Panel Desplegable con controles secundarios
            val panelExtra = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (tarjetaExpandida[i]) View.VISIBLE else View.GONE
                setPadding(0, dp(4), 0, dp(10))
                tag = "extra_$i"
            }

            // Subfila Extra 1: +1m Prueba y +5m Cortesía
            val filaExtraTiempo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val btn1m = crearBotonTiempoProtegido(i, 1, "+1m Prueba", "#D97706", bloqueada).apply {
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 0, dp(5), dp(10))
                }
            }
            val btn5m = crearBotonTiempoProtegido(i, 5, "+5m Cortesía", "#0D9488", bloqueada).apply {
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(5), 0, 0, dp(10))
                }
            }
            filaExtraTiempo.addView(btn1m)
            filaExtraTiempo.addView(btn5m)

            // Subfila Extra 2: Volumen (Siempre habilitado)
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

            // Subfila Extra 3: Encender, Quitar (Protegido), Apagar
            val filaEnergia = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val btnEncender = crearBotonGrande("⚡ Encender", "#16A34A", 13f).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 0, dp(5), 0)
                }
                setOnClickListener { encenderTv(i) }
            }
            val btnCancelar = crearBotonGrande("⏹ Quitar", if (bloqueada) "#1E293B" else "#475569", 13f).apply {
                alpha = if (bloqueada) 0.45f else 1.0f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(5), 0, dp(5), 0)
                }
                setOnClickListener {
                    if (estaBloqueada(i)) {
                        Toast.makeText(this@MainActivity, "🔒 Desbloquea la estación para quitar el tiempo.", Toast.LENGTH_SHORT).show()
                    } else {
                        quitarTimerTv(i)
                    }
                }
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
            card.addView(filaMinutos)
            card.addView(filaHoras)
            card.addView(filaLibre)
            card.addView(panelExtra)
            card.addView(btnToggle)
            contenedorTvs.addView(card)
            pintarTarjeta(i)
        }
    }

    private fun sincronizarAhoraEnParalelo() {
        lifecycleScope.launch {
            (1..EstadoSala.NUM_TVS).map { id ->
                async {
                    val ip = obtenerIp(id)
                    val salida = AdbManager.ejecutar(
                        this@MainActivity,
                        ip,
                        "cat /sdcard/Download/sala_fin.txt 2>/dev/null; echo '|'; cat /sdcard/Download/sala_libre.txt 2>/dev/null; echo '|'; date +%s",
                        esAccionManual = true
                    )
                    if (salida != null) {
                        EstadoSala.online[id] = true
                        val partes = salida.split("|")
                        val ahora = partes.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L
                        val inicioLibre = partes.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L
                        val fin = partes.getOrNull(0)?.trim()?.toLongOrNull() ?: 0L

                        if (ahora > 1700000000L && inicioLibre > 1700000000L && ahora >= inicioLibre) {
                            EstadoSala.esLibre[id] = true
                            EstadoSala.segundos[id] = (ahora - inicioLibre).toInt()
                        } else if (ahora > 1700000000L && fin > ahora) {
                            EstadoSala.esLibre[id] = false
                            EstadoSala.segundos[id] = (fin - ahora).toInt()
                        } else {
                            EstadoSala.esLibre[id] = false
                            EstadoSala.segundos[id] = 0
                        }
                    } else {
                        EstadoSala.online[id] = false
                    }
                    pintarTarjeta(id)
                }
            }.awaitAll()
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
                sincronizarAhoraEnParalelo()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun enviarTeclaRapida(id: Int, keycode: Int) {
        lifecycleScope.launch {
            val res = AdbManager.ejecutar(this@MainActivity, obtenerIp(id), "input keyevent $keycode", esAccionManual = true)
            if (res != null) {
                EstadoSala.online[id] = true
                pintarTarjeta(id)
            } else {
                EstadoSala.online[id] = false
                pintarTarjeta(id)
                Toast.makeText(this@MainActivity, "${obtenerNombre(id)} sin conexión", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun encenderTv(id: Int) {
        lifecycleScope.launch {
            val ip = obtenerIp(id)
            val res = AdbManager.ejecutar(this@MainActivity, ip, "input keyevent 224", esAccionManual = true)
            if (res != null) {
                EstadoSala.online[id] = true
                pintarTarjeta(id)
                Toast.makeText(this@MainActivity, "Encendiendo ${obtenerNombre(id)}...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "TV en reposo profundo (usa el control físico)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun quitarTimerTv(id: Int) {
        lifecycleScope.launch {
            val ip = obtenerIp(id)
            val cmd = "rm -f /sdcard/Download/sala_fin.txt /sdcard/Download/sala_pid.txt /sdcard/Download/sala_libre.txt"
            val res = AdbManager.ejecutar(this@MainActivity, ip, cmd, esAccionManual = true)
            if (res != null) {
                EstadoSala.online[id] = true
                EstadoSala.esLibre[id] = false
                EstadoSala.segundos[id] = 0
                pintarTarjeta(id)
                Toast.makeText(this@MainActivity, "Reloj reiniciado en ${obtenerNombre(id)}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "Sin conexión con ${obtenerNombre(id)}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun apagarTvAhora(id: Int) {
        lifecycleScope.launch {
            val ip = obtenerIp(id)
            val cmd = "rm -f /sdcard/Download/sala_fin.txt /sdcard/Download/sala_pid.txt /sdcard/Download/sala_libre.txt; input keyevent 26"
            val res = AdbManager.ejecutar(this@MainActivity, ip, cmd, esAccionManual = true)
            if (res != null) {
                EstadoSala.online[id] = true
                EstadoSala.esLibre[id] = false
                EstadoSala.segundos[id] = 0
                pintarTarjeta(id)
                Toast.makeText(this@MainActivity, "Apagando ${obtenerNombre(id)}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "Sin conexión con ${obtenerNombre(id)}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun iniciarTiempoLibre(id: Int) {
        lifecycleScope.launch {
            val ip = obtenerIp(id)
            val cmd = "rm -f /sdcard/Download/sala_fin.txt /sdcard/Download/sala_pid.txt; date +%s > /sdcard/Download/sala_libre.txt"
            val res = AdbManager.ejecutar(this@MainActivity, ip, cmd, esAccionManual = true)
            if (res != null) {
                EstadoSala.online[id] = true
                EstadoSala.esLibre[id] = true
                EstadoSala.segundos[id] = 0
                pintarTarjeta(id)
                Toast.makeText(this@MainActivity, "⏱️ Tiempo Libre iniciado en ${obtenerNombre(id)}", Toast.LENGTH_SHORT).show()
            } else {
                EstadoSala.online[id] = false
                pintarTarjeta(id)
                Toast.makeText(this@MainActivity, "${obtenerNombre(id)} sin conexión", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun cerrarYCobrarTiempoLibre(id: Int) {
        lifecycleScope.launch {
            val ip = obtenerIp(id)
            val salida = AdbManager.ejecutar(
                this@MainActivity,
                ip,
                "cat /sdcard/Download/sala_libre.txt 2>/dev/null; echo '|'; date +%s",
                esAccionManual = true
            )
            if (salida == null) {
                Toast.makeText(this@MainActivity, "${obtenerNombre(id)} sin conexión", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val partes = salida.split("|")
            val inicio = partes.getOrNull(0)?.trim()?.toLongOrNull() ?: 0L
            val ahora = partes.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L

            if (inicio < 1700000000L || ahora < inicio) {
                Toast.makeText(this@MainActivity, "${obtenerNombre(id)} no está en Tiempo Libre", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val segJugados = (ahora - inicio).toInt()
            val minReales = ceil(segJugados / 60.0).toInt().coerceAtLeast(1)
            val bloques30 = ceil(segJugados / 1800.0).toInt().coerceAtLeast(1)
            val minACobrar = bloques30 * 30

            val hReal = segJugados / 3600
            val mReal = (segJugados % 3600) / 60
            val sReal = segJugados % 60
            val textoReal = String.format("%02d:%02d:%02d", hReal, mReal, sReal)

            val textoCobro = if (minACobrar % 60 == 0) {
                "${minACobrar / 60} Hora(s)"
            } else {
                "${minACobrar / 60}h ${minACobrar % 60}min"
            }

            AlertDialog.Builder(this@MainActivity)
                .setTitle("💰 Cerrar Tiempo Libre • ${obtenerNombre(id)}")
                .setMessage(
                    "Tiempo real jugado: $textoReal ($minReales min)\n\n" +
                    "Tiempo redondeado a cobrar (bloques 30m):\n" +
                    "👉 $textoCobro ($minACobrar minutos)\n\n" +
                    "¿Confirmar cierre?"
                )
                .setPositiveButton("Confirmar y Cerrar") { _, _ ->
                    lifecycleScope.launch {
                        AdbManager.ejecutar(this@MainActivity, ip, "rm -f /sdcard/Download/sala_libre.txt", esAccionManual = true)
                        EstadoSala.esLibre[id] = false
                        EstadoSala.segundos[id] = 0
                        pintarTarjeta(id)
                        Toast.makeText(this@MainActivity, "Sesión cerrada ($textoCobro)", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Seguir jugando", null)
                .show()
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
                EstadoSala.online[id] = false
                pintarTarjeta(id)
                Toast.makeText(this@MainActivity, "${obtenerNombre(id)} sin conexión", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val partes = salida.split("|")
            val ahora = partes.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L
            val finActual = partes.getOrNull(0)?.trim()?.toLongOrNull() ?: 0L
            val baseSegundos = if (ahora > 1700000000L && finActual > ahora) (finActual - ahora).toInt() else 0

            val totalSeg = baseSegundos + (minutosExtra * 60)
            val cmd = "rm -f /sdcard/Download/sala_libre.txt; FIN=\$((\$(date +%s) + $totalSeg)); echo \$FIN > /sdcard/Download/sala_fin.txt"
            val res = AdbManager.ejecutar(this@MainActivity, ip, cmd, esAccionManual = true)
            if (res != null) {
                EstadoSala.online[id] = true
                EstadoSala.esLibre[id] = false
                EstadoSala.segundos[id] = totalSeg
                pintarTarjeta(id)
                Toast.makeText(this@MainActivity, "+${minutosExtra}m en ${obtenerNombre(id)}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun pintarTarjeta(id: Int) {
        val ip = obtenerIp(id)
        val nombre = obtenerNombre(id)
        val bloqueada = estaBloqueada(id)
        val txtReloj = contenedorTvs.findViewWithTag<TextView>("reloj_$id") ?: return
        val txtTitulo = contenedorTvs.findViewWithTag<TextView>("titulo_$id") ?: return
        val txtSubIp = contenedorTvs.findViewWithTag<TextView>("subip_$id")

        if (!EstadoSala.online[id]) {
            txtTitulo.text = "🔴 $nombre"
            txtSubIp?.text = if (bloqueada) "IP: $ip • 🔒 Bloqueada" else "IP: $ip"
            txtReloj.text = "--:--:--"
            txtReloj.setTextColor(Color.parseColor("#EF4444"))
            return
        }

        val seg = EstadoSala.segundos[id]
        val h = seg / 3600
        val m = (seg % 3600) / 60
        val s = seg % 60
        txtReloj.text = String.format("%02d:%02d:%02d", h, m, s)

        if (EstadoSala.esLibre[id]) {
            val bloques = ceil(seg.coerceAtLeast(1) / 1800.0).toInt().coerceAtLeast(1)
            val minCobro = bloques * 30
            txtTitulo.text = "⏱️ $nombre (LIBRE)"
            txtSubIp?.text = if (bloqueada) "🔒 Cobro redondeado: $minCobro min" else "IP: $ip • Cobro redondeado: $minCobro min"
            txtReloj.setTextColor(Color.parseColor("#38BDF8"))
        } else {
            txtTitulo.text = "🟢 $nombre"
            txtSubIp?.text = if (bloqueada) "IP: $ip • 🔒 Tiempos bloqueados" else "IP: $ip"
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
                    if (EstadoSala.online[i]) {
                        if (EstadoSala.esLibre[i]) {
                            EstadoSala.segundos[i] += 1
                        } else if (EstadoSala.segundos[i] > 0) {
                            EstadoSala.segundos[i] -= 1
                        }
                    }
                    pintarTarjeta(i)
                }
                delay(1000)
            }
        }
    }
}
