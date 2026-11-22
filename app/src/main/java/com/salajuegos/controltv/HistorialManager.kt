package com.salajuegos.controltv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class RegistroSesion(
    val timestamp: Long,
    val fecha: String,      // yyyy-MM-dd
    val hora: String,       // HH:mm
    val idEstacion: Int,
    val nombreEstacion: String,
    val minutos: Int,
    val valor: Int
)

data class EstadisticaEstacion(
    val nombreEstacion: String,
    val minutosTotales: Int,
    val valorTotal: Int,
    val sesiones: Int
)

object HistorialManager {

    private const val PREFS_NAME = "ejoc_historial_v1"
    private const val KEY_REGISTROS = "registros_json"

    fun registrarCierre(
        context: Context,
        idEstacion: Int,
        nombreEstacion: String,
        minutos: Int,
        valor: Int
    ) {
        if (minutos <= 1 || valor <= 0) return

        val ahora = Date()
        val fmtFecha = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val fmtHora = SimpleDateFormat("HH:mm", Locale.US)

        val obj = JSONObject().apply {
            put("timestamp", ahora.time)
            put("fecha", fmtFecha.format(ahora))
            put("hora", fmtHora.format(ahora))
            put("idEstacion", idEstacion)
            put("nombreEstacion", nombreEstacion)
            put("minutos", minutos)
            put("valor", valor)
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val actualRaw = prefs.getString(KEY_REGISTROS, "[]") ?: "[]"
        val array = try {
            JSONArray(actualRaw)
        } catch (e: Exception) {
            JSONArray()
        }
        array.put(obj)

        prefs.edit().putString(KEY_REGISTROS, array.toString()).apply()
    }

    fun obtenerTodos(context: Context): List<RegistroSesion> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_REGISTROS, "[]") ?: "[]"
        val lista = mutableListOf<RegistroSesion>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                lista.add(
                    RegistroSesion(
                        timestamp = obj.optLong("timestamp", 0L),
                        fecha = obj.optString("fecha", ""),
                        hora = obj.optString("hora", ""),
                        idEstacion = obj.optInt("idEstacion", 0),
                        nombreEstacion = obj.optString("nombreEstacion", "Estación"),
                        minutos = obj.optInt("minutos", 0),
                        valor = obj.optInt("valor", 0)
                    )
                )
            }
        } catch (e: Exception) {
            // Si hay corrupción puntual, retorna lo que se pudo leer
        }
        return lista.sortedByDescending { it.timestamp }
    }

    fun filtrarPorPeriodo(context: Context, periodo: String): List<RegistroSesion> {
        val todos = obtenerTodos(context)
        val fmtFecha = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val fmtMes = SimpleDateFormat("yyyy-MM", Locale.US)
        val cal = Calendar.getInstance()

        return when (periodo) {
            "HOY" -> {
                val hoy = fmtFecha.format(cal.time)
                todos.filter { it.fecha == hoy }
            }
            "AYER" -> {
                cal.add(Calendar.DAY_OF_YEAR, -1)
                val ayer = fmtFecha.format(cal.time)
                todos.filter { it.fecha == ayer }
            }
            "MES_ACTUAL" -> {
                val mesActual = fmtMes.format(cal.time)
                todos.filter { it.fecha.startsWith(mesActual) }
            }
            "MES_ANTERIOR" -> {
                cal.add(Calendar.MONTH, -1)
                val mesAnterior = fmtMes.format(cal.time)
                todos.filter { it.fecha.startsWith(mesAnterior) }
            }
            else -> todos
        }
    }

    fun calcularRankingConsolas(registros: List<RegistroSesion>): List<EstadisticaEstacion> {
        val mapa = mutableMapOf<String, EstadisticaEstacion>()
        for (reg in registros) {
            val clave = reg.nombreEstacion
            val previo = mapa[clave]
            if (previo == null) {
                mapa[clave] = EstadisticaEstacion(
                    nombreEstacion = clave,
                    minutosTotales = reg.minutos,
                    valorTotal = reg.valor,
                    sesiones = 1
                )
            } else {
                mapa[clave] = previo.copy(
                    minutosTotales = previo.minutosTotales + reg.minutos,
                    valorTotal = previo.valorTotal + reg.valor,
                    sesiones = previo.sesiones + 1
                )
            }
        }
        // Ordenado estrictamente de MAYOR a MENOR tiempo de uso
        return mapa.values.sortedByDescending { it.minutosTotales }
    }
}
