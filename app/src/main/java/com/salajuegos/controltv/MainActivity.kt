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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

class MainActivity : AppCompatActivity() {

    private lateinit var contenedorTvs: LinearLayout
    private val tarjetasExpandidas = HashSet<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#090D16")) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(20), dp(14), dp(32))
        }

        // Cabecera superior con título y botón directo de Reportes
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(16))
        }

        val bloqueTitulo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titulo = TextView(this).apply {
            text = "🎮 EjocGames Time"
            textSize = 23f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
        }
        val subtitulo = TextView(this).apply {
            text = "Control de Pantallas y Tarifas"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
        }
        bloqueTitulo.addView(titulo)
        bloqueTitulo.addView(subtitulo)

        val btnReportes = crearBotonGrande("📊 Reportes", "#1E293B", 13.5f, "#38BDF8").apply {
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            minHeight = dp(42)
            minimumHeight = dp(42)
            setOnClickListener { mostrarDialogoReportes() }
        }

        topBar.addView(bloqueTitulo)
        topBar.addView(btnReportes)
        root.addView(topBar)

        contenedorTvs = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(contenedorTvs)

        val btnAgregarEstacion = crearBotonGrande("➕ Agregar Estación", "#1E293B", 15f, "#38BDF8").apply {
            setTextColor(Color.parseColor("#38BDF8"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(4), 0, dp(16)) }
            setOnClickListener { mostrarDialogoNuevaEstacion() }
        }
        root.addView(btnAgregarEstacion)

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

    private fun eliminarEstacion(id: Int) {
        val actuales = EstadoSala.obtenerIdsEstaciones(this).toMutableList()
        actuales.remove(id)
        EstadoSala.guardarIdsEstaciones(this, actuales)

        getSharedPreferences("sala_tvs", Context.MODE_PRIVATE).edit()
            .remove("nombre_$id")
            .remove("ip_$id")
            .remove("tarifa_15_$id")
            .remove("tarifa_30_$id")
            .remove("tarifa_45_$id")
            .remove("tarifa_60_$id")
            .remove("bloqueada_$id")
            .remove("mins_acumulados_$id")
            .remove("libre_es_gracia_$id")
            .apply()

        EstadoSala.online.remove(id)
        EstadoSala.segundos.remove(id)
        EstadoSala.esLibre.remove(id)
        tarjetasExpandidas.remove(id)

        construirTarjetas()
        Toast.makeText(this, "Estación eliminada", Toast.LENGTH_SHORT).show()
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

    private fun redondearBloques15Min(segundos: Int): Int {
        val bloques15 = ceil(segundos.coerceAtLeast(1) / 900.0).toInt().coerceAtLeast(1)
        return bloques15 * 15
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
            h == 0 -> "${m}m"
            m == 0 -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }

    private fun estaBloqueada(id: Int): Boolean {
        val prefs = getSharedPreferences("sala_tvs", Context.MODE_PRIVATE)
        return prefs.getBoolean("bloqueada_$id", false)
    }

    private fun esVentanaGracia(id: Int): Boolean {
        return getSharedPreferences("sala_tvs", Context.MODE_PRIVATE).getBoolean("libre_es_gracia_$id", false)
    }

    private fun setVentanaGracia(id: Int, esGracia: Boolean) {
        getSharedPreferences("sala_tvs", Context.MODE_PRIVATE).edit()
            .putBoolean("libre_es_gracia_$id", esGracia)
            .apply()
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
            .putBoolean("libre_es_gracia_$id", false)
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
        val ids = EstadoSala.obtenerIdsEstaciones(this)

        if (ids.isEmpty()) {
            val vacio = TextView(this).apply {
                text = "No hay estaciones configuradas.\nToca '+ Agregar Estación' para crear la primera."
                textSize = 15f
                setTextColor(Color.parseColor("#94A3B8"))
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(32), dp(16), dp(32))
            }
            contenedorTvs.addView(vacio)
            return
        }

        for (i in ids) {
            val bloqueada = estaBloqueada(i)
            val estaOnline = EstadoSala.online[i] == true
            val expandida = tarjetasExpandidas.contains(i)

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

            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val bloqueInfo = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val txtNombre = TextView(this).apply {
                text = if (estaOnline) "🟢 ${obtenerNombre(i)}" else "🔴 ${obtenerNombre(i)}"
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
                setOnClickListener { mostrarDialogoConfig(i, esNueva = false) }
            }
            header.addView(bloqueInfo)
            header.addView(btnCandado)
            header.addView(btnConfig)

            val txtReloj = TextView(this).apply {
                text = "--:--:--"
                textSize = 40f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#94A3B8"))
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(14))
                tag = "reloj_$i"
            }

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

            val panelExtra = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (expandida) View.VISIBLE else View.GONE
                setPadding(0, dp(4), 0, dp(10))
                tag = "extra_$i"
            }

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
                if (expandida) "▲ Ocultar controles" else "▼ Más controles (Libre / Cerrar / Audio / Apagar)",
                "#1E293B",
                12.5f,
                "#334155"
            ).apply {
                setTextColor(Color.parseColor("#CBD5E1"))
                minHeight = dp(42)
                minimumHeight = dp(42)
                setOnClickListener {
                    val ahoraExpandida = if (tarjetasExpandidas.contains(i)) {
                        tarjetasExpandidas.remove(i)
                        false
                    } else {
                        tarjetasExpandidas.add(i)
                        true
                    }
                    panelExtra.visibility = if (ahoraExpandida) View.VISIBLE else View.GONE
                    text = if (ahoraExpandida) "▲ Ocultar controles" else "▼ Más controles (Libre / Cerrar / Audio / Apagar)"
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

    // ==================== PANTALLA DE REPORTES INTEGRADA ====================

    private fun mostrarDialogoReportes() {
        var periodoActual = "HOY"

        val contenedorPrincipal = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        // Fila de filtros de período: Hoy | Ayer | Este Mes | Mes Ant.
        val filaFiltros = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(12))
        }

        val scrollContenido = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(430)
            )
        }
        val cuerpoReporte = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollContenido.addView(cuerpoReporte)

        val botonesFiltro = mutableMapOf<String, Button>()

        fun renderizarReporte(periodo: String) {
            periodoActual = periodo
            botonesFiltro.forEach { (clave, btn) ->
                val activo = clave == periodo
                btn.background = crearFondoRedondeado(
                    if (activo) "#0284C7" else "#1E293B",
                    8f,
                    if (activo) "#38BDF8" else "#334155"
                )
                btn.setTextColor(if (activo) Color.WHITE else Color.parseColor("#94A3B8"))
            }

            cuerpoReporte.removeAllViews()
            val registros = HistorialManager.filtrarPorPeriodo(this, periodo)
            val totalDinero = registros.sumOf { it.valor }
            val totalMinutos = registros.sumOf { it.minutos }
            val ranking = HistorialManager.calcularRankingConsolas(registros)

            // 1. Tarjeta de Totales del Período
            val cardTotales = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = crearFondoRedondeado("#162032", 14f, "#26354D")
                setPadding(dp(14), dp(14), dp(14), dp(14))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dp(14)) }
            }

            val tituloPeriodo = when (periodo) {
                "HOY" -> "Resumen de Hoy"
                "AYER" -> "Resumen de Ayer"
                "MES_ACTUAL" -> "Acumulado Este Mes"
                "MES_ANTERIOR" -> "Acumulado Mes Anterior"
                else -> "Resumen"
            }

            cardTotales.addView(TextView(this).apply {
                text = tituloPeriodo
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#38BDF8"))
                setPadding(0, 0, 0, dp(6))
            })

            cardTotales.addView(TextView(this).apply {
                text = "💵 Total Recaudado: ${formatearMoneda(totalDinero)}"
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#22C55E"))
                setPadding(0, 0, 0, dp(4))
            })

            cardTotales.addView(TextView(this).apply {
                text = "⏱️ Horas jugadas: ${formatearEnHoras(totalMinutos)}  •  🎮 Sesiones: ${registros.size}"
                textSize = 14f
                setTextColor(Color.parseColor("#E2E8F0"))
            })

            cuerpoReporte.addView(cardTotales)

            // 2. Ranking de Consolas (Mayor a menor tiempo de uso)
            cuerpoReporte.addView(TextView(this).apply {
                text = "🏆 Consolas Más Jugadas (Mayor a Menor)"
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                setPadding(0, dp(4), 0, dp(8))
            })

            if (ranking.isEmpty()) {
                cuerpoReporte.addView(TextView(this).apply {
                    text = "Aún no hay cierres de tiempo registrados en este período."
                    textSize = 13.5f
                    setTextColor(Color.parseColor("#94A3B8"))
                    setPadding(0, 0, 0, dp(14))
                })
            } else {
                ranking.forEachIndexed { idx, est ->
                    val porcentaje = if (totalMinutos > 0) (est.minutosTotales * 100) / totalMinutos else 0
                    val filaEst = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = crearFondoRedondeado("#1E293B", 10f, "#334155")
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, 0, 0, dp(8)) }
                    }

                    val colIzq = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    colIzq.addView(TextView(this).apply {
                        text = "#${idx + 1}  ${est.nombreEstacion}"
                        textSize = 15f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(Color.WHITE)
                    })
                    colIzq.addView(TextView(this).apply {
                        text = "Uso: ${formatearEnHoras(est.minutosTotales)} ($porcentaje%) • ${est.sesiones} turno(s)"
                        textSize = 12.5f
                        setTextColor(Color.parseColor("#94A3B8"))
                    })

                    val txtValorEst = TextView(this).apply {
                        text = formatearMoneda(est.valorTotal)
                        textSize = 15f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(Color.parseColor("#38BDF8"))
                    }

                    filaEst.addView(colIzq)
                    filaEst.addView(txtValorEst)
                    cuerpoReporte.addView(filaEst)
                }
            }

            // 3. Detalle cronológico de sesiones cerradas
            if (registros.isNotEmpty()) {
                cuerpoReporte.addView(TextView(this).apply {
                    text = "🧾 Detalle de Cierres (${registros.size})"
                    textSize = 15f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    setPadding(0, dp(10), 0, dp(8))
                })

                registros.forEach { reg ->
                    val lineaReg = TextView(this).apply {
                        val etiquetaFecha = if (periodo == "HOY" || periodo == "AYER") reg.hora else "${reg.fecha} ${reg.hora}"
                        text = "• [$etiquetaFecha] ${reg.nombreEstacion}: ${formatearEnHoras(reg.minutos)} → ${formatearMoneda(reg.valor)}"
                        textSize = 13f
                        setTextColor(Color.parseColor("#CBD5E1"))
                        setPadding(dp(4), dp(4), dp(4), dp(4))
                    }
                    cuerpoReporte.addView(lineaReg)
                }
            }

            // 4. Botón opcional de compartir/exportar texto (sin obligar a bajar archivos)
            if (registros.isNotEmpty()) {
                val btnCompartirOpcional = crearBotonGrande("📤 Compartir Resumen (Opcional)", "#1E293B", 13f, "#475569").apply {
                    setTextColor(Color.parseColor("#CBD5E1"))
                    minHeight = dp(40)
                    minimumHeight = dp(40)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, dp(14), 0, dp(4)) }
                    setOnClickListener {
                        compartirResumenTexto(tituloPeriodo, totalDinero, totalMinutos, ranking, registros)
                    }
                }
                cuerpoReporte.addView(btnCompartirOpcional)
            }
        }

        listOf(
            "HOY" to "Hoy",
            "AYER" to "Ayer",
            "MES_ACTUAL" to "Este Mes",
            "MES_ANTERIOR" to "Mes Ant."
        ).forEachIndexed { idx, (clave, etiqueta) ->
            val btn = crearBotonGrande(etiqueta, "#1E293B", 12.5f, "#334155").apply {
                minHeight = dp(38)
                minimumHeight = dp(38)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(if (idx == 0) 0 else dp(3), 0, if (idx == 3) 0 else dp(3), 0)
                }
                setOnClickListener { renderizarReporte(clave) }
            }
            botonesFiltro[clave] = btn
            filaFiltros.addView(btn)
        }

        contenedorPrincipal.addView(filaFiltros)
        contenedorPrincipal.addView(scrollContenido)

        renderizarReporte(periodoActual)

        AlertDialog.Builder(this)
            .setTitle("📊 Caja y Estadísticas • EjocGames")
            .setView(contenedorPrincipal)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun compartirResumenTexto(
        tituloPeriodo: String,
        totalDinero: Int,
        totalMinutos: Int,
        ranking: List<EstadisticaEstacion>,
        registros: List<RegistroSesion>
    ) {
        val sb = StringBuilder()
        sb.append("🎮 *EjocGames Time - $tituloPeriodo*\n")
        sb.append("💵 Total: *${formatearMoneda(totalDinero)}*\n")
        sb.append("⏱️ Horas: *${formatearEnHoras(totalMinutos)}* (${registros.size} sesiones)\n\n")
        sb.append("🏆 *Ranking por uso:*\n")
        ranking.forEachIndexed { idx, est ->
            sb.append("${idx + 1}. ${est.nombreEstacion}: ${formatearEnHoras(est.minutosTotales)} (${formatearMoneda(est.valorTotal)})\n")
        }

        val intentShare = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Reporte EjocGames - $tituloPeriodo")
            putExtra(Intent.EXTRA_TEXT, sb.toString())
        }
        startActivity(Intent.createChooser(intentShare, "Compartir resumen opcional"))
    }

    // ==================== FIN PANTALLA DE REPORTES ====================

    private fun sincronizarAhoraEnParalelo() {
        lifecycleScope.launch {
            val ids = EstadoSala.obtenerIdsEstaciones(this@MainActivity)
            ids.map { id ->
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

    private fun mostrarDialogoNuevaEstacion() {
        val ids = EstadoSala.obtenerIdsEstaciones(this)
        val nuevoId = (ids.maxOrNull() ?: 0) + 1
        mostrarDialogoConfig(nuevoId, esNueva = true)
    }

    private fun mostrarDialogoConfig(id: Int, esNueva: Boolean) {
        val scrollDialog = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }

        val lblNombre = TextView(this).apply {
            text = "Nombre de la estación (ej. PS5 - $id):"
            setTypeface(null, Typeface.BOLD)
        }
        val inputNombre = EditText(this).apply {
            setText(if (esNueva) "Estación $id" else obtenerNombre(id))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }

        val lblIp = TextView(this).apply {
            text = "Dirección IP del TV:"
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(12), 0, 0)
        }
        val inputIp = EditText(this).apply {
            setText(if (esNueva) "192.168.1.${100 + id}" else obtenerIp(id))
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

        val builder = AlertDialog.Builder(this)
            .setTitle(if (esNueva) "➕ Nueva Estación" else "⚙️ Configurar ${obtenerNombre(id)}")
            .setView(scrollDialog)
            .setPositiveButton("Guardar") { _, _ ->
                val t15 = input15.text.toString().trim().toIntOrNull() ?: obtenerTarifa15(id)
                val t30 = input30.text.toString().trim().toIntOrNull() ?: obtenerTarifa30(id)
                val t45 = input45.text.toString().trim().toIntOrNull() ?: obtenerTarifa45(id)
                val t60 = input60.text.toString().trim().toIntOrNull() ?: obtenerTarifa60(id)

                if (esNueva) {
                    val actuales = EstadoSala.obtenerIdsEstaciones(this).toMutableList()
                    actuales.add(id)
                    EstadoSala.guardarIdsEstaciones(this, actuales)
                }

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

        if (!esNueva) {
            builder.setNeutralButton("🗑️ Eliminar") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Eliminar ${obtenerNombre(id)}")
                    .setMessage("¿Seguro que deseas eliminar esta estación de la lista?")
                    .setPositiveButton("Sí, eliminar") { _, _ ->
                        eliminarEstacion(id)
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }

        builder.show()
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
                Toast.makeText(this@MainActivity, "Encendiendo ${obtenerNombre(id)} en HDMI 1...", Toast.LENGTH_SHORT).show()
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
            val estabaEnLibre = EstadoSala.esLibre[id] == true
            if (estabaEnLibre) {
                // 1A. Detener Tiempo Libre y ocultar overlay
                val cmd = "rm -f /sdcard/Download/sala_fin.txt /sdcard/Download/sala_pid.txt /sdcard/Download/sala_libre.txt; am startservice -n com.salajuegos.ejocoverlay/.OverlayService --es accion CLEAR 2>/dev/null"
                val res = AdbManager.ejecutar(this@MainActivity, ip, cmd, esAccionManual = true)
                if (res != null) {
                    EstadoSala.online[id] = true
                    EstadoSala.esLibre[id] = false
                    EstadoSala.segundos[id] = 0
                    setVentanaGracia(id, false)
                    pintarTarjeta(id)
                    Toast.makeText(this@MainActivity, "Tiempo Libre detenido en ${obtenerNombre(id)}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Sin conexión con ${obtenerNombre(id)}", Toast.LENGTH_SHORT).show()
                }
            } else {
                // Pasar a Ventana de seguridad y avisar con accion ESPERA
                val cmd = "rm -f /sdcard/Download/sala_fin.txt /sdcard/Download/sala_pid.txt; T=\$(date +%s); echo \$T > /sdcard/Download/sala_libre.txt; am startservice -n com.salajuegos.ejocoverlay/.OverlayService --es accion ESPERA --el ts \$T 2>/dev/null"
                val res = AdbManager.ejecutar(this@MainActivity, ip, cmd, esAccionManual = true)
                if (res != null) {
                    EstadoSala.online[id] = true
                    EstadoSala.esLibre[id] = true
                    EstadoSala.segundos[id] = 0
                    setVentanaGracia(id, true)
                    pintarTarjeta(id)
                    val acum = obtenerMinutosAcumulados(id)
                    val msgAcum = if (acum > 0) " (Mantiene ${formatearEnHoras(acum)})" else ""
                    Toast.makeText(this@MainActivity, "🛡️ Ventana de seguridad activa$msgAcum", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Sin conexión con ${obtenerNombre(id)}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun apagarTvAhora(id: Int) {
        lifecycleScope.launch {
            val ip = obtenerIp(id)
            val cmd = "rm -f /sdcard/Download/sala_fin.txt /sdcard/Download/sala_pid.txt /sdcard/Download/sala_libre.txt; am startservice -n com.salajuegos.ejocoverlay/.OverlayService --es accion CLEAR 2>/dev/null; input keyevent 26"
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
            val cmd = "rm -f /sdcard/Download/sala_fin.txt /sdcard/Download/sala_pid.txt; T=\$(date +%s); echo \$T > /sdcard/Download/sala_libre.txt; am startservice -n com.salajuegos.ejocoverlay/.OverlayService --es accion LIBRE --el ts \$T 2>/dev/null"
            val res = AdbManager.ejecutar(this@MainActivity, ip, cmd, esAccionManual = true)
            if (res != null) {
                EstadoSala.online[id] = true
                EstadoSala.esLibre[id] = true
                EstadoSala.segundos[id] = 0
                setVentanaGracia(id, false)
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
            val nombreEstacion = obtenerNombre(id)
            val salida = AdbManager.ejecutar(
                this@MainActivity,
                ip,
                "cat /sdcard/Download/sala_libre.txt 2>/dev/null; echo '|'; date +%s",
                esAccionManual = true
            )
            if (salida == null) {
                Toast.makeText(this@MainActivity, "$nombreEstacion sin conexión", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val partes = salida.split("|")
            val inicioLibre = partes.getOrNull(0)?.trim()?.toLongOrNull() ?: 0L
            val ahora = partes.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L
            val minsPrevios = obtenerMinutosAcumulados(id)
            val esGracia = esVentanaGracia(id)

            // Caso 1: Estaba corriendo en Tiempo Libre o Ventana de Seguridad
            if (inicioLibre > 1700000000L && ahora >= inicioLibre) {
                val segLibre = (ahora - inicioLibre).toInt()
                val minCobroLibre = if (esGracia) 0 else redondearBloques15Min(segLibre)
                val totalFinalMins = minsPrevios + minCobroLibre
                val totalValor = calcularValorSesion(id, totalFinalMins)

                if (totalFinalMins <= 0 && esGracia) {
                    AdbManager.ejecutar(this@MainActivity, ip, "rm -f /sdcard/Download/sala_libre.txt /sdcard/Download/sala_fin.txt; am startservice -n com.salajuegos.ejocoverlay/.OverlayService --es accion CLEAR 2>/dev/null", esAccionManual = true)
                    EstadoSala.esLibre[id] = false
                    EstadoSala.segundos[id] = 0
                    limpiarMinutosAcumulados(id)
                    pintarTarjeta(id)
                    Toast.makeText(this@MainActivity, "Ventana de seguridad cerrada sin cobro pendiente", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val detalleLibre = if (esGracia) {
                    "Ventana de seguridad (${segLibre / 60}m sin costo adicional)\n"
                } else {
                    val detallePrevio = if (minsPrevios > 0) "Tiempo previo: ${formatearEnHoras(minsPrevios)}\n" else ""
                    "${detallePrevio}Tiempo Libre redondeado (15m): ${formatearEnHoras(minCobroLibre)}\n"
                }

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("💰 Cerrar Tiempo • $nombreEstacion")
                    .setMessage(
                        "${detalleLibre}\n" +
                        "⏱️ Tiempo total sesión: ${formatearEnHoras(totalFinalMins)}\n" +
                        "💵 TOTAL A COBRAR: ${formatearMoneda(totalValor)}\n\n" +
                        "¿Confirmar cierre de sesión y registrar en reportes?"
                    )
                    .setPositiveButton("Confirmar y Cerrar") { _, _ ->
                        lifecycleScope.launch {
                            AdbManager.ejecutar(this@MainActivity, ip, "rm -f /sdcard/Download/sala_libre.txt /sdcard/Download/sala_fin.txt; am startservice -n com.salajuegos.ejocoverlay/.OverlayService --es accion CLEAR 2>/dev/null", esAccionManual = true)
                            HistorialManager.registrarCierre(this@MainActivity, id, nombreEstacion, totalFinalMins, totalValor)
                            EstadoSala.esLibre[id] = false
                            EstadoSala.segundos[id] = 0
                            limpiarMinutosAcumulados(id)
                            pintarTarjeta(id)
                            Toast.makeText(this@MainActivity, "✅ Registrado: ${formatearEnHoras(totalFinalMins)} • ${formatearMoneda(totalValor)}", Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
                return@launch
            }

            // Caso 2: Sesión con temporizador normal
            if (minsPrevios > 0) {
                val totalValor = calcularValorSesion(id, minsPrevios)
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("💰 Cerrar Tiempo • $nombreEstacion")
                    .setMessage(
                        "⏱️ Tiempo total sesión: ${formatearEnHoras(minsPrevios)}\n" +
                        "💵 TOTAL A COBRAR: ${formatearMoneda(totalValor)}\n\n" +
                        "¿Confirmar cierre y registrar en reportes?"
                    )
                    .setPositiveButton("Confirmar y Cerrar") { _, _ ->
                        lifecycleScope.launch {
                            AdbManager.ejecutar(this@MainActivity, ip, "rm -f /sdcard/Download/sala_libre.txt /sdcard/Download/sala_fin.txt; am startservice -n com.salajuegos.ejocoverlay/.OverlayService --es accion CLEAR 2>/dev/null", esAccionManual = true)
                            HistorialManager.registrarCierre(this@MainActivity, id, nombreEstacion, minsPrevios, totalValor)
                            EstadoSala.esLibre[id] = false
                            EstadoSala.segundos[id] = 0
                            limpiarMinutosAcumulados(id)
                            pintarTarjeta(id)
                            Toast.makeText(this@MainActivity, "✅ Registrado: ${formatearEnHoras(minsPrevios)} • ${formatearMoneda(totalValor)}", Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            } else {
                Toast.makeText(this@MainActivity, "$nombreEstacion no tiene tiempo acumulado activo", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sumarTiempoTv(id: Int, minutosExtra: Int) {
        lifecycleScope.launch {
            val ip = obtenerIp(id)
            val salida = AdbManager.ejecutar(
                this@MainActivity,
                ip,
                "cat /sdcard/Download/sala_fin.txt 2>/dev/null; echo '|'; cat /sdcard/Download/sala_libre.txt 2>/dev/null; echo '|'; date +%s",
                esAccionManual = true
            )
            if (salida == null) {
                EstadoSala.online[id] = false
                pintarTarjeta(id)
                Toast.makeText(this@MainActivity, "${obtenerNombre(id)} sin conexión", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val partes = salida.split("|")
            val finActual = partes.getOrNull(0)?.trim()?.toLongOrNull() ?: 0L
            val inicioLibre = partes.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L
            val ahora = partes.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L

            val baseSegundos = if (ahora > 1700000000L && finActual > ahora) (finActual - ahora).toInt() else 0

            val segYaJugadosEnLibre = if (ahora > 1700000000L && inicioLibre > 1700000000L && ahora >= inicioLibre) {
                (ahora - inicioLibre).toInt()
            } else {
                0
            }

            val segSolicitados = minutosExtra * 60
            val totalSeg = (baseSegundos + segSolicitados - segYaJugadosEnLibre).coerceAtLeast(0)

            val cmd = "rm -f /sdcard/Download/sala_libre.txt; FIN=\$((\$(date +%s) + $totalSeg)); echo \$FIN > /sdcard/Download/sala_fin.txt; am startservice -n com.salajuegos.ejocoverlay/.OverlayService --es accion TIMER --el ts \$FIN 2>/dev/null"
            val res = AdbManager.ejecutar(this@MainActivity, ip, cmd, esAccionManual = true)
            if (res != null) {
                if (minutosExtra > 1) {
                    sumarMinutosAcumulados(id, minutosExtra)
                }
                setVentanaGracia(id, false)
                EstadoSala.online[id] = true
                EstadoSala.esLibre[id] = false
                EstadoSala.segundos[id] = totalSeg
                pintarTarjeta(id)
                val acum = obtenerMinutosAcumulados(id)
                val valorAcum = calcularValorSesion(id, acum)
                val notaDescuento = if (segYaJugadosEnLibre >= 60) {
                    " (-${segYaJugadosEnLibre / 60}m ya jugados)"
                } else {
                    ""
                }
                Toast.makeText(
                    this@MainActivity,
                    "+${minutosExtra}m$notaDescuento • Total: ${formatearEnHoras(acum)} (${formatearMoneda(valorAcum)})",
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
        val esGracia = esVentanaGracia(id)
        val txtReloj = contenedorTvs.findViewWithTag<TextView>("reloj_$id") ?: return
        val txtTitulo = contenedorTvs.findViewWithTag<TextView>("titulo_$id") ?: return
        val txtSubIp = contenedorTvs.findViewWithTag<TextView>("subip_$id")

        if (EstadoSala.online[id] != true) {
            txtTitulo.text = "🔴 $nombre"
            txtSubIp?.text = if (bloqueada) "IP: $ip • 🔒 Bloqueada" else "IP: $ip"
            txtReloj.text = "--:--:--"
            txtReloj.setTextColor(Color.parseColor("#EF4444"))
            return
        }

        val seg = EstadoSala.segundos[id] ?: 0
        val h = seg / 3600
        val m = (seg % 3600) / 60
        val s = seg % 60
        txtReloj.text = String.format("%02d:%02d:%02d", h, m, s)

        if (EstadoSala.esLibre[id] == true) {
            if (esGracia) {
                val valorPrevio = calcularValorSesion(id, acum)
                val infoPrevio = if (acum > 0) "${formatearEnHoras(acum)} (${formatearMoneda(valorPrevio)})" else "Sin cobro previo"
                txtTitulo.text = "🛡️ $nombre (ESPERA)"
                txtSubIp?.text = if (bloqueada) "🔒 Espera • $infoPrevio" else "Ventana seguridad • $infoPrevio"
                txtReloj.setTextColor(Color.parseColor("#F59E0B"))
            } else {
                val minCobro = redondearBloques15Min(seg)
                val totalEstMins = acum + minCobro
                val valorEst = calcularValorSesion(id, totalEstMins)
                txtTitulo.text = "⏱️ $nombre (LIBRE)"
                txtSubIp?.text = if (bloqueada) {
                    "🔒 Libre: ${formatearEnHoras(totalEstMins)} (${formatearMoneda(valorEst)})"
                } else {
                    "Libre: ${formatearEnHoras(totalEstMins)} • ${formatearMoneda(valorEst)}"
                }
                txtReloj.setTextColor(Color.parseColor("#38BDF8"))
            }
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
                val ids = EstadoSala.obtenerIdsEstaciones(this@MainActivity)
                for (i in ids) {
                    if (EstadoSala.online[i] == true) {
                        val segActual = EstadoSala.segundos[i] ?: 0
                        if (EstadoSala.esLibre[i] == true) {
                            EstadoSala.segundos[i] = segActual + 1
                        } else if (segActual > 0) {
                            EstadoSala.segundos[i] = segActual - 1
                        }
                    }
                    pintarTarjeta(i)
                }
                delay(1000)
            }
        }
    }
}
