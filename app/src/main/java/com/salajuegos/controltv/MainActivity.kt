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
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.ceil

class MainActivity : AppCompatActivity() {

    private lateinit var contenedorTvs: LinearLayout
    private val tarjetaExpandida = BooleanArray(EstadoSala.NUM_TVS + 1) { false }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#090D16")) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(22), dp(14), dp(32))
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
            text = "Control de Pantallas, Tiempos y Tarifas"
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(18))
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
            background = crearFondoRedondeado(colorHex, 10f, bordeHex)
            setPadding(dp(2), dp(12), dp(2), dp(12))
            minHeight = dp(46)
            minimumHeight = dp(46)
            minWidth = 0
            minimumWidth = 0
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

    private fun obtenerTarifa15(id: Int): Int {
        return getSharedPreferences("sala_tvs", Context.MODE_PRIVATE).getInt("tarifa_15_$id", 1500)
    }

    private fun obtenerTarifa30(id: Int): Int {
        return getSharedPreferences("sala_tvs", Context.MODE_PRIVATE).getInt("tarifa_30_$id", 2500)
    }

    private fun obtenerTarifa45(id: Int): Int {
        return getSharedPreferences("sala_tvs", Context.MODE_PRIVATE).getInt("tarifa_45_$id", 3500)
    }

    private fun obtenerTarifa60(id: Int): Int {
        return getSharedPreferences("sala_tvs", Context.MODE_PRIVATE).getInt("tarifa_60_$id", 4500)
    }

    private fun guardarConfig(
        id: Int,
        nombre: String,
        ip: String,
        t15: Int,
        t30: Int,
        t45: Int,
        t60: Int
    ) {
        getSharedPreferences("sala_tvs", Context.MODE_PRIVATE).edit()
            .putString("nombre_$id", nombre.ifBlank { "Estación $id" })
            .putString("ip_$id", ip)
            .putInt("tarifa_15_$id", t15)
            .putInt("tarifa_30_$id", t30)
            .putInt("tarifa_45_$id", t45)
            .putInt("tarifa_60_$id", t60)
            .apply()
    }

    private fun calcularValorSesion(id: Int, totalMinutos: Int): Int {
        if (totalMinutos <= 0) return 0
        val p15 = obtenerTarifa15(id)
        val p30 = obtenerTarifa30(id)
        val p45 = obtenerTarifa45(id)
        val p60 = obtenerTarifa60(id)

        val horasCompletas = totalMinutos / 60
        val restoMinutos = totalMinutos % 60

        val valorResto = when {
            restoMinutos == 0 -> 0
            restoMinutos <= 15 -> p15
            restoMinutos <= 30 -> p30
            restoMinutos <= 45 -> p45
            else -> p60
        }
        return (horasCompletas * p60) + valorResto
    }

    private fun formatearMoneda(valor: Int): String {
        val formato = NumberFormat.getNumberInstance(Locale("es", "CO"))
        return "$${formato.format(valor)}"
    }

    private fun formatearEnHoras(totalMins: Int): String {
        if (totalMins <= 0) return "0h"
        val h = totalMins / 60
        val m = totalMins % 60
        return when {
            h == 0 -> "${m}m (0.${(m * 100) / 60}h)"
            m == 0 -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }

    private fun estaBloqueada(id: Int): Boolean {
        val prefs = getSharedPreferences("sala_tvs", Context.MODE_PRIVATE)
        return prefs.getBoolean("bloqueada_$id", false)
    }

    private fun obtenerMinutosAcumulados(id: Int): Int {
        val prefs = getSharedPreferences("sala_tvs", Context.MODE_PRIVATE)
        return prefs.getInt("mins_acumulados_$id", 0)
    }

    private fun sumarMinutosAcumulados(id: Int, mins: Int) {
        val prefs = getSharedPreferences("sala_tvs", Context.MODE_PRIVATE)
        val actual = prefs.getInt("mins_acumulados_$id", 0)
        prefs.edit().putInt("mins_acumulados_$id", actual + mins).apply()
    }

    private fun limpiarMinutosAcumulados(id: Int) {
        getSharedPreferences("sala_tvs", Context.MODE_PRIVATE).edit()
            .putInt("mins_acumulados_$id", 0)
            .apply()
    }

    private fun alternarBloqueo(id: Int) {
        val nuevoEstado = !estaBloqueada(id)
        getSharedPreferences("sala_tvs", Context.MODE_PRIVATE).edit()
            .putBoolean("bloqueada_$id", nuevoEstado)
            .apply()
        construirTarjetas()
        pintarTarjeta(id)
        val msg = if (nuevoEstado) "🔒 ${obtenerNombre(id)} bloqueada" else "🔓 ${obtenerNombre(id)} desbloqueada"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun crearBotonTiempoProtegido(id: Int, mins: Int, etiqueta: String, colorHex: String, bloqueada: Boolean): Button {
        val colorActivo = if (bloqueada) "#1E293B" else colorHex
        return crearBotonGrande(etiqueta, colorActivo, 13.5f, if (bloqueada) "#334155" else null).apply {
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
                setPadding(dp(14), dp(16), dp(14), dp(16))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dp(20)) }
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
                textSize = 12.5f
                setTextColor(Color.parseColor("#94A3B8"))
                tag = "subip_$i"
            }
            bloqueInfo.addView(txtNombre)
            bloqueInfo.addView(txtSubIp)

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
                textSize = 40f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#94A3B8"))
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(14))
                tag = "reloj_$i"
            }

            // ÚNICA FILA PRINCIPAL DE TIEMPOS: +15m, +30m, +45m, +1h, +2h
            val filaTiempos = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(
                Triple(15, "+15m", "#0284C7"),
                Triple(30, "+30m", "#0284C7"),
                Triple(45, "+45m", "#0369A1"),
                Triple(60, "+1h", "#2563EB"),
                Triple(120, "+2h", "#4F46E5")
            ).forEachIndexed { idx, (mins, etiqueta, color) ->
                val btn = crearBotonTiempoProtegido(i, mins, etiqueta, color, bloqueada).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(if (idx == 0) 0 else dp(3), 0, if (idx == 4) 0 else dp(3), dp(12))
                    }
                }
                filaTiempos.addView(btn)
            }

            // Panel Desplegable con Tiempo Libre, Cerrar Tiempo, +1m Prueba, Audio y Energía
            val panelExtra = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (tarjetaExpandida[i]) View.VISIBLE else View.GONE
                setPadding(0, dp(4), 0, dp(10))
                tag = "extra_$i"
            }

            // Subfila Extra 1: Tiempo Libre, Cerrar Tiempo, +1m Prueba
            val filaSesionExtra = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val btnIniciarLibre = crearBotonGrande(
                "⏱️ Libre",
                if (bloqueada) "#1E293B" else "#0E7490",
                13f,
                if (bloqueada) "#334155" else null
            ).apply {
                alpha = if (bloqueada) 0.45f else 1.0f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 0, dp(4), dp(10))
                }
                setOnClickListener {
                    if (estaBloqueada(i)) {
                        Toast.makeText(this@MainActivity, "🔒 Estación bloqueada.", Toast.LENGTH_SHORT).show()
                    } else {
                        iniciarTiempoLibre(i)
                    }
                }
            }
            val btnCerrarTiempo = crearBotonGrande(
                "💰 Cerrar Tiempo",
                if (bloqueada) "#1E293B" else "#059669",
                13f,
                if (bloqueada) "#334155" else null
            ).apply {
                alpha = if (bloqueada) 0.45f else 1.0f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f).apply {
                    setMargins(dp(4), 0, dp(4), dp(10))
                }
                setOnClickListener {
                    if (estaBloqueada(i)) {
                        Toast.makeText(this@MainActivity, "🔒 Desbloquea la estación para cerrar tiempo.", Toast.LENGTH_SHORT).show()
                    } else {
                        cerrarSesionCompleta(i)
                    }
                }
            }
            val btn1m = crearBotonTiempoProtegido(i, 1, "+1m Prueba", "#D97706", bloqueada).apply {
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(4), 0, 0, dp(10))
                }
            }
            filaSesionExtra.addView(btnIniciarLibre)
            filaSesionExtra.addView(btnCerrarTiempo)
            filaSesionExtra.addView(btn1m)

            // Subfila Extra 2: Volumen (Siempre habilitado)
            val filaAudio = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val btnVolDown = crearBotonGrande("🔉 Vol -", "#1E293B", 13.5f, "#334155").apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 0, dp(4), dp(10))
                }
                setOnClickListener { enviarTeclaRapida(i, 25) }
            }
            val btnMute = crearBotonGrande("🔇 Mute", "#1E293B", 13.5f, "#334155").apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(4), 0, dp(4), dp(10))
                }
                setOnClickListener { enviarTeclaRapida(i, 164) }
            }
            val btnVolUp = crearBotonGrande("🔊 Vol +", "#1E293B", 13.5f, "#334155").apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(4), 0, 0, dp(10))
                }
                setOnClickListener { enviarTeclaRapida(i, 24) }
            }
            filaAudio.addView(btnVolDown)
            filaAudio.addView(btnMute)
            filaAudio.addView(btnVolUp)

            // Subfila Extra 3: Encender (con salto automático a HDMI 1), Quitar, Apagar
            val filaEnergia = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val btnEncender = crearBotonGrande("⚡ Encender", "#16A34A", 13f).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 0, dp(4), 0)
                }
                setOnClickListener { encenderTv(i) }
            }
            val btnCancelar = crearBotonGrande("⏹ Quitar", if (bloqueada) "#1E293B" else "#475569", 13f).apply {
                alpha = if (bloqueada) 0.45f else 1.0f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(4), 0, dp(4), 0)
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
                    setMargins(dp(4), 0, 0, 0)
                }
                setOnClickListener { apagarTvAhora(i) }
            }
            filaEnergia.addView(btnEncender)
            filaEnergia.addView(btnCancelar)
            filaEnergia.addView(btnApagar)

            panelExtra.addView(filaSesionExtra)
            panelExtra.addView(filaAudio)
            panelExtra.addView(filaEnergia)

            val btnToggle = crearBotonGrande(
                if (tarjetaExpandida[i]) "▲ Ocultar controles" else "▼ Más controles (Libre / Cerrar / Audio / Apagar)",
                "#1E293B",
                12.5f,
                "#334155"
            ).apply {
                setTextColor(Color.parseColor("#CBD5E1"))
                minHeight = dp(42)
                minimumHeight = dp(42)
                setOnClickListener {
                    tarjetaExpandida[i] = !tarjetaExpandida[i]
                    panelExtra.visibility = if (tarjetaExpandida[i]) View.VISIBLE else View.GONE
                    text = if (tarjetaExpandida[i]) "▲ Ocultar controles" else "▼ Más controles (Libre / Cerrar / Audio / Apagar)"
                }
            }

            card.addView(header)
            card.addView(txtReloj)
            card.addView(filaTiempos)
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
        val scrollDialog = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }

        val lblNombre = TextView(this).apply {
            text = "Nombre de la estación (ej. PS5 - 1):"
            setTypeface(null, Typeface.BOLD)
        }
        val inputNombre = EditText(this).apply {
            setText(obtenerNombre(id))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }

        val lblIp = TextView(this).apply {
            text = "Dirección IP del TV:"
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(12), 0, 0)
        }
        val inputIp = EditText(this).apply {
            setText(obtenerIp(id))
            inputType = InputType.TYPE_CLASS_NUMBER
            keyListener = DigitsKeyListener.getInstance("0123456789.")
        }

        val lblTarifasTitulo = TextView(this).apply {
            text = "💰 Tarifas de esta estación (en pesos):"
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(6))
        }

        val lbl15 = TextView(this).apply { text = "Valor 15 minutos:" }
        val input15 = EditText(this).apply {
            setText(obtenerTarifa15(id).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        val lbl30 = TextView(this).apply {
            text = "Valor 30 minutos:"
            setPadding(0, dp(8), 0, 0)
        }
        val input30 = EditText(this).apply {
            setText(obtenerTarifa30(id).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        val lbl45 = TextView(this).apply {
            text = "Valor 45 minutos:"
            setPadding(0, dp(8), 0, 0)
        }
        val input45 = EditText(this).apply {
            setText(obtenerTarifa45(id).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        val lbl60 = TextView(this).apply {
            text = "Valor 1 Hora (60 min):"
            setPadding(0, dp(8), 0, 0)
        }
        val input60 = EditText(this).apply {
            setText(obtenerTarifa60(id).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        layout.addView(lblNombre)
        layout.addView(inputNombre)
        layout.addView(lblIp)
        layout.addView(inputIp)
        layout.addView(lblTarifasTitulo)
        layout.addView(lbl15)
        layout.addView(input15)
        layout.addView(lbl30)
        layout.addView(input30)
        layout.addView(lbl45)
        layout.addView(input45)
        layout.addView(lbl60)
        layout.addView(input60)
        scrollDialog.addView(layout)

        AlertDialog.Builder(this)
            .setTitle("Configurar Estación $id")
            .setView(scrollDialog)
            .setPositiveButton("Guardar") { _, _ ->
                val t15 = input15.text.toString().trim().toIntOrNull() ?: obtenerTarifa15(id)
                val t30 = input30.text.toString().trim().toIntOrNull() ?: obtenerTarifa30(id)
                val t45 = input45.text.toString().trim().toIntOrNull() ?: obtenerTarifa45(id)
                val t60 = input60.text.toString().trim().toIntOrNull() ?: obtenerTarifa60(id)
                guardarConfig(
                    id,
                    inputNombre.text.toString().trim(),
                    inputIp.text.toString().trim(),
                    t15,
                    t30,
                    t45,
                    t60
                )
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
            // 1. Despierta el TV (keyevent 224)
            val res = AdbManager.ejecutar(this@MainActivity, ip, "input keyevent 224", esAccionManual = true)
            if (res != null) {
                EstadoSala.online[id] = true
                pintarTarjeta(id)
                Toast.makeText(this@MainActivity, "Encendiendo ${obtenerNombre(id)} en HDMI 1...", Toast.LENGTH_SHORT).show()
                // 2. Espera 1.2s a que cargue la interfaz y salta directo a HDMI 1 (keyevent 243)
                delay(1200)
                AdbManager.ejecutar(this@MainActivity, ip, "input keyevent 243", esAccionManual = true)
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
                limpiarMinutosAcumulados(id)
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
                limpiarMinutosAcumulados(id)
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

    private fun cerrarSesionCompleta(id: Int) {
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
            val inicioLibre = partes.getOrNull(0)?.trim()?.toLongOrNull() ?: 0L
            val ahora = partes.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L
            val minsPrevios = obtenerMinutosAcumulados(id)

            // Caso 1: Estaba en Tiempo Libre (puede tener además tiempo previo comprado)
            if (inicioLibre > 1700000000L && ahora >= inicioLibre) {
                val segLibre = (ahora - inicioLibre).toInt()
                val bloques30 = ceil(segLibre / 1800.0).toInt().coerceAtLeast(1)
                val minCobroLibre = bloques30 * 30
                val totalFinalMins = minsPrevios + minCobroLibre
                val totalValor = calcularValorSesion(id, totalFinalMins)

                val detallePrevio = if (minsPrevios > 0) "Tiempo previo: ${formatearEnHoras(minsPrevios)}\n" else ""

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("💰 Cerrar Tiempo • ${obtenerNombre(id)}")
                    .setMessage(
                        "${detallePrevio}" +
                        "Tiempo Libre redondeado: ${formatearEnHoras(minCobroLibre)}\n\n" +
                        "⏱️ Tiempo total sesión: ${formatearEnHoras(totalFinalMins)}\n" +
                        "💵 TOTAL A COBRAR: ${formatearMoneda(totalValor)}\n\n" +
                        "¿Confirmar cierre de sesión?"
                    )
                    .setPositiveButton("Confirmar y Cerrar") { _, _ ->
                        lifecycleScope.launch {
                            AdbManager.ejecutar(this@MainActivity, ip, "rm -f /sdcard/Download/sala_libre.txt /sdcard/Download/sala_fin.txt", esAccionManual = true)
                            EstadoSala.esLibre[id] = false
                            EstadoSala.segundos[id] = 0
                            limpiarMinutosAcumulados(id)
                            pintarTarjeta(id)
                            Toast.makeText(this@MainActivity, "Cerrado: ${formatearEnHoras(totalFinalMins)} • ${formatearMoneda(totalValor)}", Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
                return@launch
            }

            // Caso 2: Sesión con temporizador normal (acumulado de todas las veces que pidió tiempo)
            if (minsPrevios > 0) {
                val totalValor = calcularValorSesion(id, minsPrevios)
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("💰 Cerrar Tiempo • ${obtenerNombre(id)}")
                    .setMessage(
                        "⏱️ Tiempo total sesión: ${formatearEnHoras(minsPrevios)}\n" +
                        "💵 TOTAL A COBRAR: ${formatearMoneda(totalValor)}\n\n" +
                        "¿Confirmar cierre y reiniciar contador?"
                    )
                    .setPositiveButton("Confirmar y Cerrar") { _, _ ->
                        lifecycleScope.launch {
                            AdbManager.ejecutar(this@MainActivity, ip, "rm -f /sdcard/Download/sala_fin.txt /sdcard/Download/sala_libre.txt", esAccionManual = true)
                            EstadoSala.esLibre[id] = false
                            EstadoSala.segundos[id] = 0
                            limpiarMinutosAcumulados(id)
                            pintarTarjeta(id)
                            Toast.makeText(this@MainActivity, "Cerrado: ${formatearEnHoras(minsPrevios)} • ${formatearMoneda(totalValor)}", Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            } else {
                Toast.makeText(this@MainActivity, "${obtenerNombre(id)} no tiene tiempo acumulado activo", Toast.LENGTH_SHORT).show()
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
                if (minutosExtra > 1) {
                    sumarMinutosAcumulados(id, minutosExtra)
                }
                EstadoSala.online[id] = true
                EstadoSala.esLibre[id] = false
                EstadoSala.segundos[id] = totalSeg
                pintarTarjeta(id)
                val acum = obtenerMinutosAcumulados(id)
                val valorAcum = calcularValorSesion(id, acum)
                Toast.makeText(
                    this@MainActivity,
                    "+${minutosExtra}m • Total: ${formatearEnHoras(acum)} (${formatearMoneda(valorAcum)})",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun pintarTarjeta(id: Int) {
        val ip = obtenerIp(id)
        val nombre = obtenerNombre(id)
        val bloqueada = estaBloqueada(id)
        val acum = obtenerMinutosAcumulados(id)
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
            val totalEstMins = acum + minCobro
            val valorEst = calcularValorSesion(id, totalEstMins)
            txtTitulo.text = "⏱️ $nombre (LIBRE)"
            txtSubIp?.text = if (bloqueada) {
                "🔒 Libre: ${formatearEnHoras(totalEstMins)} (${formatearMoneda(valorEst)})"
            } else {
                "Libre: ${formatearEnHoras(totalEstMins)} • ${formatearMoneda(valorEst)}"
            }
            txtReloj.setTextColor(Color.parseColor("#38BDF8"))
        } else {
            txtTitulo.text = "🟢 $nombre"
            val infoAcum = if (acum > 0) {
                " • ${formatearEnHoras(acum)} (${formatearMoneda(calcularValorSesion(id, acum))})"
            } else {
                ""
            }
            txtSubIp?.text = if (bloqueada) "IP: $ip • 🔒 Bloqueada$infoAcum" else "IP: $ip$infoAcum"
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
