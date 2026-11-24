package es.bicilab.bicicam.camera

import android.content.Context
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Los marcadores puestos desde el Karoo, en un archivo de texto que se puede abrir con cualquier programa:
 * `bicicam-marcadores.csv`, con la hora (UTC), el segundo del clip en que cayó y, solo si el GPS a la cámara
 * está activado, la posición. Es la copia propia de BiciCAM: la marca que guarda la cámara va dentro del vídeo.
 * Solo se guardan los últimos [MAX_LINES] y se borran desde la app.
 */
object MarkerLog {
    private const val FILE_NAME = "bicicam-marcadores.csv"
    private const val HEADER = "hora_utc,segundo_del_clip,latitud,longitud"
    private const val MAX_LINES = 500
    private val writer = Executors.newSingleThreadExecutor()
    private val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private var file: File? = null

    /** Dónde se guarda, o null antes de [init]. */
    val path: String? get() = file?.absolutePath

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        file = File(context.getExternalFilesDir(null) ?: context.filesDir, FILE_NAME)
    }

    @Synchronized
    fun add(clipSeconds: Long, latitude: Double?, longitude: Double?) {
        val target = file ?: return
        val line = listOf(
            format.format(Date()),
            clipSeconds.toString(),
            latitude?.let { String.format(Locale.US, "%.6f", it) } ?: "",
            longitude?.let { String.format(Locale.US, "%.6f", it) } ?: "",
        ).joinToString(",")
        writer.execute {
            try {
                val kept = if (target.exists()) target.readLines().drop(1).takeLast(MAX_LINES - 1) else emptyList()
                target.writeText((listOf(HEADER) + kept + line).joinToString("\n", postfix = "\n"))
            } catch (_: IOException) {
                // el archivo es una comodidad: nunca se falla por él
            }
        }
    }

    /** Borra todos los marcadores guardados. */
    @Synchronized
    fun clear() {
        val target = file ?: return
        writer.execute {
            try {
                target.writeText(HEADER + "\n")
            } catch (_: IOException) {
                // ver arriba
            }
        }
    }
}
