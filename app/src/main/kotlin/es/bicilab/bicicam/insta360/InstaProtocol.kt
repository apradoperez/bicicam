package es.bicilab.bicicam.insta360

import es.bicilab.bicicam.camera.t
import es.bicilab.bicicam.camera.shortUuid
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Protocolo del mando GPS de Insta360 (el "GPS Action Remote", compatible con la Ace Pro 2 según la tienda
 * oficial). Aquí los papeles van al revés que con DJI: el mando es el periférico Bluetooth y es la cámara
 * la que se conecta a él. BiciCAM hace que el Karoo se presente como ese mando.
 *
 * No hay documentación oficial. Esto sale de capturas de tráfico publicadas por la comunidad:
 * - github.com/TheAngryRaven/insta360-ble-gps-spec (X4, capturado en el aire con un nRF52840)
 * - github.com/pchwalek/insta360_ble_esp32 (X3 y ONE RS)
 * - github.com/zTischii/insta360-remote-android (emulador en Android, probado con una X4)
 *
 * Tramas: `<MAGIA:3> <TIPO:1> <B4:1> <LARGO:1> <DATOS>`, con magia `FC EF FE` del mando a la cámara y
 * `FE EF FE` de la cámara al mando.
 */
object InstaUuids {
    /** Nombre con el que se anuncia el mando original: es lo que la cámara busca al emparejar. */
    const val REMOTE_NAME = "Insta360 GPS Remote"

    val SERVICE: UUID = shortUuid("ce80")

    /** La cámara escribe aquí su estado y su número de serie. */
    val FROM_CAMERA: UUID = shortUuid("ce81")

    /** El mando avisa por aquí de botones y GPS. */
    val TO_CAMERA: UUID = shortUuid("ce82")

    /** Lectura fija 0x0201 (probablemente la versión del mando). */
    val INFO: UUID = shortUuid("ce83")

    /** Segundo servicio del mando original; la cámara lo consulta pero no se usa para mandar nada. */
    val SECONDARY_SERVICE: UUID = UUID.fromString("0000d0ff-3c17-d293-8e48-14fe2e4da212")

    private fun secondary(hex: String): UUID = UUID.fromString("0000$hex-3c17-d293-8e48-14fe2e4da212")

    /** Características del segundo servicio con su valor de lectura; null: solo escritura. */
    val SECONDARY_CHARACTERISTICS: List<Pair<UUID, ByteArray?>> = listOf(
        secondary("ffd1") to null,
        secondary("ffd2") to byteArrayOf(0x00),
        secondary("ffd3") to byteArrayOf(0x01, 0x90.toByte(), 0x1E, 0x30),
        secondary("ffd4") to byteArrayOf(0x01, 0x20, 0x00, 0x18),
        secondary("ffd5") to byteArrayOf(0x00),
        secondary("ffd8") to null,
        secondary("fff1") to byteArrayOf(0x00),
        secondary("fff2") to null,
        secondary("ffe0") to byteArrayOf(0x00),
    )

    val INFO_VALUE: ByteArray = byteArrayOf(0x01, 0x02)
}

/** Botones del mando. */
object InstaButton {
    const val POWER = 0x00
    const val MODE = 0x01
    const val SHUTTER = 0x02

    const val CLICK = 0x00
    const val HELD = 0x03
}

object InstaFrames {
    private val TO_CAMERA_MAGIC = byteArrayOf(0xFC.toByte(), 0xEF.toByte(), 0xFE.toByte())

    const val TYPE_GPS = 0x83
    const val TYPE_BUTTON = 0x86

    /** La cámara manda su número de serie al conectar. */
    const val TYPE_SERIAL = 0x07

    /** Texto que el mando original enseña en su pantalla: modo, autonomía o contador de grabación. */
    const val TYPE_DISPLAY = 0x10
    const val TYPE_STATUS = 0x02

    /** Identificador de fabricante del anuncio: el de Apple, porque el mando imita un iBeacon. */
    const val BEACON_COMPANY_ID = 0x004C

    /**
     * Pulsación de un botón: `FC EF FE 86 <SN> 03 01 <BOTÓN> <ESTADO>`.
     * @param sn contador que sube de 2 en 2 con cada pulsación y vuelve a 0 en cada conexión
     */
    fun button(sn: Int, button: Int, state: Int): ByteArray =
        TO_CAMERA_MAGIC + byteArrayOf(TYPE_BUTTON.toByte(), sn.toByte(), 0x03, 0x01, button.toByte(), state.toByte())

    /**
     * Datos de fabricante del anuncio que despierta a la cámara: un iBeacon falso con la palabra ORBIT y
     * los seis caracteres del número de serie de la cámara. Una cámara dormida busca este anuncio y se
     * enciende al ver su propio número.
     * @param serial seis letras o cifras; null para el anuncio sin destinatario (ceros)
     */
    fun beacon(serial: String?): ByteArray {
        val out = ByteArray(24)
        byteArrayOf(0x02, 0x15, 0x09, 0x4F, 0x52, 0x42, 0x49, 0x54, 0x09, 0xFF.toByte(), 0x0F, 0x00).copyInto(out, 0)
        if (serial != null) {
            require(isSerial(serial)) { t("El número de serie tiene que tener seis letras o cifras: $serial", "The serial number must be six letters or digits: $serial") }
            serial.uppercase(Locale.US).toByteArray(Charsets.US_ASCII).copyInto(out, 12)
        }
        out[22] = 0xE4.toByte()
        out[23] = 0x01
        return out
    }

    fun isSerial(text: String): Boolean = text.length == 6 && text.all { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' }

    /**
     * Posición GPS: `FC EF FE 83 00 <LARGO> ,26.7,<07>, $GNRMC,...*CS`. El mando original manda una cada
     * décima de segundo y la cámara lo da por perdido si calla; sin posición válida se manda la frase vacía
     * (estado V). Las rarezas del formato se copian tal cual: longitud con signo y siempre "E", y una "V" de
     * más antes de la suma de comprobación.
     */
    fun gps(fix: InstaFix?, utcMillis: Long): ByteArray {
        val sentence = if (fix == null) voidSentence(utcMillis) else activeSentence(fix, utcMillis)
        val payload = ",26.7,\u0007,".toByteArray(Charsets.US_ASCII) + sentence.toByteArray(Charsets.US_ASCII)
        return TO_CAMERA_MAGIC + byteArrayOf(TYPE_GPS.toByte(), 0x00, payload.size.toByte()) + payload
    }

    private fun activeSentence(fix: InstaFix, utcMillis: Long): String {
        val body = String.format(
            Locale.US,
            "\$GNRMC,%s,A,%s,%s,%s,E,%.2f,%.2f,%s,0.0,W,A,V",
            utcTime(utcMillis),
            degreesMinutes(fix.latitude),
            if (fix.latitude >= 0) "N" else "S",
            // La longitud va con signo y la letra es siempre E
            (if (fix.longitude < 0) "-" else "") + degreesMinutes(fix.longitude),
            fix.speedMps * 1.943844495,
            fix.bearing,
            utcDate(utcMillis),
        )
        return withChecksum(body)
    }

    private fun voidSentence(utcMillis: Long): String =
        withChecksum(String.format(Locale.US, "\$GNRMC,%s,V,,,,,,,%s,0.0,W,N,V", utcTime(utcMillis), utcDate(utcMillis)))

    private fun utc(millis: Long): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply { timeInMillis = millis }

    private fun utcTime(millis: Long): String = utc(millis).let {
        String.format(
            Locale.US, "%02d%02d%02d.%03d",
            it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE), it.get(Calendar.SECOND), it.get(Calendar.MILLISECOND),
        )
    }

    private fun utcDate(millis: Long): String = utc(millis).let {
        String.format(Locale.US, "%02d%02d%02d", it.get(Calendar.DAY_OF_MONTH), it.get(Calendar.MONTH) + 1, it.get(Calendar.YEAR) % 100)
    }

    /** Grados decimales a `ggmm.mmmm`, sin signo: en latitud lo lleva la letra N o S y en longitud se antepone. */
    internal fun degreesMinutes(value: Double): String {
        val magnitude = abs(value)
        var degrees = floor(magnitude).toLong()
        var minutes = ((magnitude - degrees) * 60.0 * 10000.0).roundToLong() // diezmilésimas de minuto
        if (minutes >= 600000L) {
            degrees += 1
            minutes -= 600000L
        }
        return String.format(Locale.US, "%d.%04d", degrees * 100 + minutes / 10000, minutes % 10000)
    }

    /** Suma NMEA: XOR de todo lo que va entre `$` y `*`, en dos cifras hexadecimales. */
    internal fun withChecksum(sentence: String): String {
        var sum = 0
        for (i in 1 until sentence.length) sum = sum xor sentence[i].code
        return String.format(Locale.US, "%s*%02X", sentence, sum)
    }

    /** Frase RMC de una posición, para las pruebas. */
    internal fun sentenceFor(fix: InstaFix, utcMillis: Long): String = activeSentence(fix, utcMillis)
}

/** Posición que se le pasa a la cámara. */
data class InstaFix(val latitude: Double, val longitude: Double, val speedMps: Double, val bearing: Double)

/** Trama que la cámara escribe en el mando. */
class InstaCameraFrame(val type: Int, val flag: Int, val payload: ByteArray) {
    /** Número de serie de la trama de saludo (tipo 0x07), si lo es. */
    fun serial(): String? {
        if (type != InstaFrames.TYPE_SERIAL) return null
        val text = payload.toString(Charsets.US_ASCII).trim()
        return text.takeIf { InstaFrames.isSerial(it) }?.uppercase(Locale.US)
    }

    /** Texto de pantalla de una trama 0x10: tras cuatro bytes de control viene el texto. */
    fun displayText(): String? {
        if (type != InstaFrames.TYPE_DISPLAY || payload.size < 5) return null
        return payload.copyOfRange(4, payload.size).toString(Charsets.US_ASCII)
    }

    companion object {
        fun parse(value: ByteArray): InstaCameraFrame? {
            if (value.size < 6) return null
            if (value[0] != 0xFE.toByte() || value[1] != 0xEF.toByte() || value[2] != 0xFE.toByte()) return null
            val length = value[5].toInt() and 0xFF
            val end = minOf(6 + length, value.size)
            return InstaCameraFrame(value[3].toInt() and 0xFF, value[4].toInt() and 0xFF, value.copyOfRange(6, end))
        }
    }
}

/** Qué dice un texto de pantalla de la cámara. */
sealed interface InstaDisplay {
    /** Contador de grabación (`.00:01:23`): la señal fiable de que está grabando. */
    data class Recording(val seconds: Int) : InstaDisplay

    /** Autonomía que calcula la cámara (`1h09m`). */
    data class Runtime(val text: String) : InstaDisplay

    /** Modo, resolución y lente (`4K|30|UW`), u otro texto. */
    data class Other(val text: String) : InstaDisplay

    companion object {
        private val timer = Regex("""^\D?(\d{2}):(\d{2}):(\d{2})$""")
        private val runtime = Regex("""^(\d+)h(\d+)m$""")

        fun classify(text: String): InstaDisplay {
            val clean = text.trim()
            timer.find(clean)?.let { m ->
                val (h, min, s) = m.destructured
                return Recording(h.toInt() * 3600 + min.toInt() * 60 + s.toInt())
            }
            if (runtime.matches(clean)) return Runtime(clean)
            return Other(clean)
        }
    }
}
