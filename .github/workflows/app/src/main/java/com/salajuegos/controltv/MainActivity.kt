Kotlin
package com.salajuegos.controltv

import android.content.Context
import android.graphics.Color
import android.os.Bundle
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
    private val numTvs = 6 // Configurado para 6 televisores (puedes cambiarlo)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inicializar llaves RSA para que el TV reconozca este teléfono siempre
        crypto = cargarOCrearLlavesAdb()

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0F172A")) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val titulo = TextView(this).apply {
            text = "🎮 Control Pantallas ADB"
            textSize = 22f
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        root.addView(titulo)

        contenedorTvs = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(contenedorTvs)
        scroll.addView(root)
        setContentView(scroll)

        construirTarjetas()
        iniciarSincronizacionAutomatica()
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

    private fun obtenerIp(id: Int): String {
        val prefs = getSharedPreferences("sala_tvs", Context.MODE_PRIVATE)
        return prefs.getString("ip_$id", "192.168.1.${100 + id}") ?: "192.168.1.${100 + id}"
    }

    private fun guardarIp(id: Int, ip: String) {
        getSharedPreferences("sala_tvs", Context.MODE_PRIVATE).edit().putString("ip_$id", ip).apply()
    }

    private fun construirTarjetas() {
        contenedorTvs.removeAllViews()
        for (i in 1..numTvs) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#1E293B"))
                setPadding(28, 24, 28, 24)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 24) }
                layoutParams = params
                tag = "card_$i"
            }

            val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val txtNombre = TextView(this).apply {
                text = "TV $i (${obtenerIp(i)})"
                textSize = 16f
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                tag = "titulo_$i"
            }
            val btnConfig = Button(this).apply {
                text = "⚙️ IP"
                setOnClickListener { mostrarDialogoIp(i) }
            }
            header.addView(txtNombre)
            header.addView(btnConfig)

            val txtReloj = TextView(this).apply {
                text = "00:00:00"
                textSize = 34f
                setTextColor(Color.parseColor("#22C55E"))
                gravity = Gravity.CENTER
                setPadding(0, 12, 0, 16)
                tag = "reloj_$i"
            }

            val filaSumar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(15 to "+15m", 30 to "+30m", 60 to "+1h").forEach { (mins, etiqueta) ->
                val btn = Button(this).apply {
                    text = etiqueta
                    setBackgroundColor(Color.parseColor("#0284C7"))
                    setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(6, 0, 6, 8)
                    }
                    setOnClickListener { sumarTiempoTv(i, mins) }
                }
                filaSumar.addView(btn)
            }

            val filaAccion = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val btnCancelar = Button(this).apply {
                text = "Quitar Timer"
                setBackgroundColor(Color.parseColor("#475569"))
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(6, 0, 6, 0)
                }
                setOnClickListener { enviarComandoTv(i, "pkill -f 'SALA_TIMER'") }
            }
            val btnApagar = Button(this).apply {
                text = "Apagar Ya"
                setBackgroundColor(Color.parseColor("#DC2626"))
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(6, 0, 6, 0)
                }
                setOnClickListener {
                    enviarComandoTv(i, "pkill -f 'SALA_TIMER'; input keyevent 223")
                }
            }
            filaAccion.addView(btnCancelar)
            filaAccion.addView(btnApagar)

            card.addView(header)
            card.addView(txtReloj)
            card.addView(filaSumar)
            card.addView(filaAccion)
            contenedorTvs.addView(card)
        }
    }

    private fun mostrarDialogoIp(id: Int) {
        val input = EditText(this).apply { setText(obtenerIp(id)) }
        AlertDialog.Builder(this)
            .setTitle("IP Fija del TV $id")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                guardarIp(id, input.text.toString().trim())
                construirTarjetas()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private suspend fun ejecutarAdbShell(ip: String, comando: String): String? = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        var conn: AdbConnection? = null
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(ip, 5555), 2500)
            conn = AdbConnection.create(socket, crypto)
            conn.connect()
            val stream = conn.open("shell:$comando")
            val salida = StringBuilder()
            while (!stream.isClosed) {
                val bytes = stream.read()
                if (bytes != null) salida.append(String(bytes))
            }
            salida.toString()
        } catch (e: Exception) {
            null
        } finally {
            try { conn?.close() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun enviarComandoTv(id: Int, cmd: String) {
        lifecycleScope.launch {
            ejecutarAdbShell(obtenerIp(id), cmd)
            actualizarEstadoTv(id)
        }
    }

    private fun sumarTiempoTv(id: Int, minutosExtra: Int) {
        lifecycleScope.launch {
            val ip = obtenerIp(id)
            val actual = leerSegundosRestantes(ip) ?: 0
            val totalSeg = actual + (minutosExtra * 60)

            // Script dentro del TV:
            // 1. Guarda la hora exacta de apagado en /sdcard/.sala_fin
            // 2. Si faltan > 2 min, espera hasta 2 min antes y lanza notificación flotante en el TV
            // 3. Al cumplirse el tiempo, suspende la pantalla (keyevent 223)
            val avisoSeg = if (totalSeg > 120) totalSeg - 120 else 0
            val script = if (avisoSeg > 0) {
                "pkill -f 'SALA_TIMER'; " +
                "echo \$((\$(date +%s) + $totalSeg)) > /sdcard/.sala_fin; " +
                "nohup sh -c 'sleep $avisoSeg # SALA_TIMER\n" +
                "am broadcast -a android.intent.action.SHOW_TOAST --es message \"⚠️ Quedan 2 minutos de juego\" >/dev/null 2>&1 || cmd statusbar expand-notifications\n" +
                "sleep 120\n" +
                "rm -f /sdcard/.sala_fin\n" +
                "input keyevent 223' >/dev/null 2>&1 &"
            } else {
                "pkill -f 'SALA_TIMER'; " +
                "echo \$((\$(date +%s) + $totalSeg)) > /sdcard/.sala_fin; " +
                "nohup sh -c 'sleep $totalSeg # SALA_TIMER\n" +
                "rm -f /sdcard/.sala_fin\n" +
                "input keyevent 223' >/dev/null 2>&1 &"
            }

            ejecutarAdbShell(ip, script)
            actualizarEstadoTv(id)
        }
    }

    private suspend fun leerSegundosRestantes(ip: String): Int? {
        val salida = ejecutarAdbShell(ip, "cat /sdcard/.sala_fin 2>/dev/null; date +%s") ?: return null
        val lineas = salida.trim().lines().filter { it.isNotBlank() }
        return if (lineas.size >= 2) {
            val fin = lineas[0].trim().toLongOrNull() ?: 0L
            val ahora = lineas[1].trim().toLongOrNull() ?: 0L
            maxOf(0, (fin - ahora).toInt())
        } else {
            0
        }
    }

    private suspend fun actualizarEstadoTv(id: Int) {
        val ip = obtenerIp(id)
        val seg = leerSegundosRestantes(ip)
        val txtReloj = contenedorTvs.findViewWithTag<TextView>("reloj_$id") ?: return
        val txtTitulo = contenedorTvs.findViewWithTag<TextView>("titulo_$id") ?: return

        if (seg == null) {
            txtTitulo.text = "TV $id ($ip) - 🔴 Sin conexión"
            txtReloj.text = "--:--:--"
            txtReloj.setTextColor(Color.parseColor("#EF4444"))
        } else {
            txtTitulo.text = "TV $id ($ip) - 🟢 Conectado"
            val h = seg / 3600
            val m = (seg % 3600) / 60
            val s = seg % 60
            txtReloj.text = String.format("%02d:%02d:%02d", h, m, s)
            txtReloj.setTextColor(
                when {
                    seg == 0 -> Color.parseColor("#94A3B8")
                    seg <= 120 -> Color.parseColor("#F59E0B") // Naranja en últimos 2 min
                    else -> Color.parseColor("#22C55E")
                }
            )
        }
    }

    private fun iniciarSincronizacionAutomatica() {
        lifecycleScope.launch {
            while (isActive) {
                for (i in 1..numTvs) {
                    launch { actualizarEstadoTv(i) }
                }
                delay(3000)
            }
        }
    }
}
