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
    private val numTvs = 6

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                setOnClickListener { enviarComandoTv(i, "pkill -f SALA_TIMER; rm -f /data/local/tmp/.sala_fin") }
            }
            val btnApagar = Button(this).apply {
                text = "Apagar Ya"
                setBackgroundColor(Color.parseColor("#DC2626"))
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(6, 0, 6, 0)
                }
                setOnClickListener {
                    enviarComandoTv(i, "pkill -f SALA_TIMER; rm -f /data/local/tmp/.sala_fin; input keyevent 223")
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
            socket.soTimeout = 3000
            socket.connect(InetSocketAddress(ip, 5555), 3000)
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

    private fun enviarComandoTv(id: Int, cmd: String) {
        lifecycleScope.launch {
            ejecutarAdbShell(obtenerIp(id), cmd)
            delay(300)
            actualizarEstadoTv(id)
        }
    }

    private fun sumarTiempoTv(id: Int, minutosExtra: Int) {
        lifecycleScope.launch {
            val ip = obtenerIp(id)
            Toast.makeText(this@MainActivity, "Enviando +${minutosExtra}m al TV $id...", Toast.LENGTH_SHORT).show()

            val actual = leerSegundosRestantes(ip) ?: 0
            val totalSeg = actual + (minutosExtra * 60)
            val avisoSeg = if (totalSeg > 120) totalSeg - 120 else 0

            val cmd = if (avisoSeg > 0) {
                "pkill -f SALA_TIMER; " +
                "NOW=\$(date +%s); FIN=\$((NOW + $totalSeg)); echo \$FIN > /data/local/tmp/.sala_fin; " +
                "(sleep $avisoSeg; cmd notification post -S bigtext -t 'Sala de Juegos' 'alerta_tv' 'Quedan 2 minutos de juego' >/dev/null 2>&1; sleep 120; rm -f /data/local/tmp/.sala_fin; input keyevent 223) >/dev/null 2>&1 & # SALA_TIMER"
            } else {
                "pkill -f SALA_TIMER; " +
                "NOW=\$(date +%s); FIN=\$((NOW + $totalSeg)); echo \$FIN > /data/local/tmp/.sala_fin; " +
                "(sleep $totalSeg; rm -f /data/local/tmp/.sala_fin; input keyevent 223) >/dev/null 2>&1 & # SALA_TIMER"
            }

            val res = ejecutarAdbShell(ip, cmd)
            if (res != null) {
                Toast.makeText(this@MainActivity, "Tiempo agregado al TV $id", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "Error: el TV $id no respondió", Toast.LENGTH_SHORT).show()
            }
            delay(500)
            actualizarEstadoTv(id)
        }
    }

    private suspend fun leerSegundosRestantes(ip: String): Int? {
        val salida = ejecutarAdbShell(ip, "cat /data/local/tmp/.sala_fin 2>/dev/null; echo '---'; date +%s") ?: return null
        val partes = salida.split("---")
        if (partes.size < 2) return 0
        val fin = partes[0].trim().toLongOrNull() ?: 0L
        val ahora = partes[1].trim().toLongOrNull() ?: 0L
        return if (fin > ahora) (fin - ahora).toInt() else 0
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
                    seg <= 120 -> Color.parseColor("#F59E0B")
                    else -> Color.parseColor("#22C55E")
                }
            )
        }
    }

    private fun iniciarSincronizacionAutomatica() {
        lifecycleScope.launch {
            while (isActive) {
                for (i in 1..numTvs) {
                    actualizarEstadoTv(i)
                    delay(400)
                }
                delay(5000)
            }
        }
    }
}
