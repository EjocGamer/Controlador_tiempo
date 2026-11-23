package com.salajuegos.controltv

import android.annotation.SuppressLint
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
import android.view.MotionEvent
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
        getSharedPreferences("sala_tvs
