package es.bicilab.bicicam.dji

import es.bicilab.bicicam.camera.t
import es.bicilab.bicicam.camera.shortUuid
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Protocolo DJI R SDK, el que usa el mando GPS Bluetooth de las Osmo Action.
 * Sale de la documentación y la demo oficiales de DJI (github.com/dji-sdk/Osmo-GPS-Controller-Demo,
 * docs/protocol.md y docs/protocol_data_segment.md), que cubren Osmo Action 4, 5 Pro y 6 y Osmo 360.
 *
 * Trama (todo en little-endian):
 * ```
 * [0xAA][Ver/Len:2][CmdType:1][Enc:1][Res:3][Seq:2][CRC16:2][CmdSet:1][CmdId:1][datos][CRC32:4]
 * ```
 * El CRC16 cubre los bytes 0 a 9 y el CRC32 todo lo anterior a él.
 */
object DjiUuids {
    val SERVICE: UUID = shortUuid("fff0")

    /** De la cámara al mando; hay que activar sus avisos. */
    val NOTIFY: UUID = shortUuid("fff4")

    /** Del mando a la cámara. */
    val WRITE: UUID = shortUuid("fff5")

    /** Identificador de fabricante de DJI en los anuncios Bluetooth (bytes AA 08). */
    const val COMPANY_ID = 0x08AA
}

/**
 * Las cámaras compatibles se reconocen por el campo de fabricante de su anuncio: bytes 0, 1 y 4 iguales a
 * AA, 08 y FA (docs/Q&A.md, punto 4). Android entrega ese campo sin los dos primeros bytes, que son el
 * identificador [DjiUuids.COMPANY_ID].
 */
fun isDjiCameraAdvert(manufacturerData: ByteArray?): Boolean =
    manufacturerData != null && manufacturerData.size >= 3 && (manufacturerData[2].toInt() and 0xFF) == 0xFA

object DjiDeviceId {
    const val ACTION_4 = 0xFF33
    const val ACTION_5_PRO = 0xFF44
    const val ACTION_6 = 0xFF55
    const val OSMO_360 = 0xFF66

    fun name(id: Int): String? = when (id and 0xFFFF) {
        ACTION_4 -> "Osmo Action 4"
        ACTION_5_PRO -> "Osmo Action 5 Pro"
        ACTION_6 -> "Osmo Action 6"
        OSMO_360 -> "Osmo 360"
        else -> null
    }
}

/** CRC de la trama: los dos con valor inicial 0x3AA3 y sin XOR final (custom_crc16.c y custom_crc32.c de la demo). */
object DjiCrc {
    private val table16 = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xA001 else c ushr 1 }
        c
    }

    private val table32 = LongArray(256) { i ->
        var c = i.toLong()
        repeat(8) { c = if (c and 1L != 0L) (c ushr 1) xor 0xEDB88320L else c ushr 1 }
        c
    }

    fun crc16(data: ByteArray, length: Int): Int {
        var crc = 0x3AA3
        for (i in 0 until length) {
            crc = (table16[(crc xor (data[i].toInt() and 0xFF)) and 0xFF] xor (crc ushr 8)) and 0xFFFF
        }
        return crc
    }

    fun crc32(data: ByteArray, length: Int): Long {
        var crc = 0x3AA3L
        for (i in 0 until length) {
            crc = (table32[((crc xor (data[i].toLong() and 0xFF)) and 0xFF).toInt()] xor (crc ushr 8)) and 0xFFFFFFFFL
        }
        return crc
    }
}

internal fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF
internal fun ByteArray.u16(at: Int): Int = u8(at) or (u8(at + 1) shl 8)
internal fun ByteArray.u32(at: Int): Long =
    u8(at).toLong() or (u8(at + 1).toLong() shl 8) or (u8(at + 2).toLong() shl 16) or (u8(at + 3).toLong() shl 24)

private fun ByteArrayOutputStream.u8(value: Int) = write(value and 0xFF)
private fun ByteArrayOutputStream.u16(value: Int) {
    u8(value)
    u8(value shr 8)
}

private fun ByteArrayOutputStream.u32(value: Long) {
    u16((value and 0xFFFF).toInt())
    u16(((value shr 16) and 0xFFFF).toInt())
}

private fun ByteArrayOutputStream.zeros(count: Int) = repeat(count) { write(0) }

class DjiFrame(
    val cmdSet: Int,
    val cmdId: Int,
    val seq: Int,
    val cmdType: Int,
    val payload: ByteArray = ByteArray(0),
) {
    /** Trama de respuesta (bit 5 de CmdType); si no, es una orden. */
    val isResponse: Boolean get() = cmdType and RESPONSE != 0

    fun isCommand(set: Int, id: Int) = cmdSet == set && cmdId == id

    fun encode(): ByteArray {
        val total = HEADER + 2 + payload.size + 4
        require(total <= 0x3FF) { t("La trama no cabe en 10 bits de longitud: $total", "Frame does not fit the 10-bit length: $total") }
        val out = ByteArray(total)
        out[0] = SOF.toByte()
        out[1] = (total and 0xFF).toByte()
        out[2] = ((total shr 8) and 0x03).toByte() // la versión (bits 15 a 10) es 0
        out[3] = cmdType.toByte()
        // out[4] cifrado (0: ninguno); out[5..7] reservados
        out[8] = (seq and 0xFF).toByte()
        out[9] = ((seq shr 8) and 0xFF).toByte()
        val crc16 = DjiCrc.crc16(out, 10)
        out[10] = (crc16 and 0xFF).toByte()
        out[11] = (crc16 shr 8).toByte()
        out[12] = cmdSet.toByte()
        out[13] = cmdId.toByte()
        payload.copyInto(out, 14)
        val crc32 = DjiCrc.crc32(out, total - 4)
        for (i in 0 until 4) out[total - 4 + i] = ((crc32 shr (8 * i)) and 0xFF).toByte()
        return out
    }

    override fun toString(): String =
        "%s %02X/%02X seq=%d datos=%d".format(if (isResponse) "resp" else "orden", cmdSet, cmdId, seq, payload.size)

    companion object {
        const val SOF = 0xAA
        const val HEADER = 12
        const val MIN_SIZE = HEADER + 2 + 4

        /** La cámara no tiene que contestar. */
        const val NO_RESPONSE = 0x00

        /** Se pide respuesta, pero no pasa nada si no llega. */
        const val RESPONSE_OPTIONAL = 0x01

        /** Se exige respuesta. */
        const val RESPONSE_REQUIRED = 0x02

        /** Bit que marca una trama de respuesta. */
        const val RESPONSE = 0x20
    }
}

/**
 * Recompone tramas R SDK a partir de los avisos Bluetooth, que pueden traer una trama partida, varias
 * seguidas o tramas de otro protocolo (DUML, que empieza por 0x55 y lleva telemetría que no hace falta).
 */
class DjiFrameAssembler {
    private var buffer = ByteArray(0)

    fun feed(chunk: ByteArray): List<DjiFrame> {
        buffer += chunk
        val frames = mutableListOf<DjiFrame>()
        while (true) {
            // Lo que no empieza por 0xAA no es R SDK
            val start = buffer.indexOfFirst { (it.toInt() and 0xFF) == DjiFrame.SOF }
            if (start < 0) {
                buffer = ByteArray(0)
                break
            }
            if (start > 0) buffer = buffer.copyOfRange(start, buffer.size)
            if (buffer.size < DjiFrame.HEADER) break
            val length = buffer.u16(1) and 0x3FF
            val headerOk = length >= DjiFrame.MIN_SIZE && DjiCrc.crc16(buffer, 10) == buffer.u16(10)
            if (!headerOk) {
                // Un 0xAA suelto dentro de otros datos: se salta
                buffer = buffer.copyOfRange(1, buffer.size)
                continue
            }
            if (buffer.size < length) break
            if (DjiCrc.crc32(buffer, length - 4) == buffer.u32(length - 4)) {
                frames += DjiFrame(
                    cmdSet = buffer.u8(12),
                    cmdId = buffer.u8(13),
                    seq = buffer.u16(8),
                    cmdType = buffer.u8(3),
                    payload = buffer.copyOfRange(14, length - 4),
                )
                buffer = buffer.copyOfRange(length, buffer.size)
            } else {
                buffer = buffer.copyOfRange(1, buffer.size)
            }
        }
        return frames
    }
}

/** Órdenes del protocolo; los campos siguen docs/protocol_data_segment.md. */
object DjiCommands {
    const val SET_GENERAL = 0x00
    const val SET_CAMERA = 0x1D

    const val KEY_REPORT = 0x11
    const val GPS_PUSH = 0x17
    const val POWER_MODE = 0x1A
    const val CONNECTION = 0x19
    const val STATUS_PUSH = 0x02
    const val RECORD_CONTROL = 0x03
    const val MODE_SWITCH = 0x04
    const val STATUS_SUBSCRIBE = 0x05

    /** Posición de verify_mode en la petición de conexión; verify_data va justo después. */
    private const val VERIFY_MODE_AT = 26

    /**
     * Petición de conexión (00/19).
     * @param verifyMode 1: primer emparejamiento, la cámara enseña el código y pide confirmar;
     *   0: ya emparejado, la cámara decide según su historial si vuelve a preguntar
     * @param verifyCode código de 4 cifras que la cámara enseña para comprobar que es este mando
     * @param mac identidad del mando (6 bytes), la misma en todas las conexiones
     */
    fun connectionRequest(seq: Int, deviceId: Long, mac: ByteArray, verifyMode: Int, verifyCode: Int): DjiFrame {
        require(mac.size <= 16)
        val data = ByteArrayOutputStream(33).apply {
            u32(deviceId)
            u8(mac.size)
            write(mac)
            zeros(16 - mac.size)
            u32(0) // fw_version: con 0 la cámara no ofrece actualizaciones de firmware al mando
            u8(0) // conidx, reservado
            u8(verifyMode)
            u16(verifyCode)
            zeros(4)
        }.toByteArray()
        return DjiFrame(SET_GENERAL, CONNECTION, seq, DjiFrame.RESPONSE_REQUIRED, data)
    }

    /**
     * Respuesta a la petición de conexión que manda la propia cámara. Sin ella, la cámara repite su petición
     * sin parar y la sesión no llega a asentarse. Va con el mismo número de secuencia que la petición.
     */
    fun connectionResponse(cameraSeq: Int, deviceId: Long): DjiFrame {
        val data = ByteArrayOutputStream(9).apply {
            u32(deviceId)
            u8(0) // ret_code: correcto
            u32(0) // número de cámara: 0 con una sola cámara
        }.toByteArray()
        return DjiFrame(SET_GENERAL, CONNECTION, cameraSeq, DjiFrame.RESPONSE or DjiFrame.NO_RESPONSE, data)
    }

    /** Resultado que trae la petición de conexión de la cámara (verify_mode 2): true admite, false rechaza. */
    fun verifyResult(frame: DjiFrame): Boolean? {
        if (!frame.isCommand(SET_GENERAL, CONNECTION) || frame.isResponse) return null
        val data = frame.payload
        if (data.size < VERIFY_MODE_AT + 3 || data.u8(VERIFY_MODE_AT) != 2) return null
        return data.u16(VERIFY_MODE_AT + 1) == 0
    }

    /** Empezar (0) o parar (1) la grabación (1D/03). En modo foto, empezar hace la foto. */
    fun recordControl(seq: Int, cameraDeviceId: Long, start: Boolean): DjiFrame {
        val data = ByteArrayOutputStream(9).apply {
            u32(cameraDeviceId)
            u8(if (start) 0 else 1)
            zeros(4)
        }.toByteArray()
        return DjiFrame(SET_CAMERA, RECORD_CONTROL, seq, DjiFrame.RESPONSE_OPTIONAL, data)
    }

    /** Cambio de modo (1D/04): 1 es vídeo. Los valores son los de `camera_mode` del estado. */
    fun modeSwitch(seq: Int, cameraDeviceId: Long, mode: Int): DjiFrame {
        val data = ByteArrayOutputStream(9).apply {
            u32(cameraDeviceId)
            u8(mode)
            zeros(4)
        }.toByteArray()
        return DjiFrame(SET_CAMERA, MODE_SWITCH, seq, DjiFrame.RESPONSE_OPTIONAL, data)
    }

    /** Suscripción al estado (1D/05): modo 3 (periódico y al cambiar) a 2 Hz, la única frecuencia admitida. */
    fun statusSubscribe(seq: Int): DjiFrame {
        val data = ByteArrayOutputStream(6).apply {
            u8(3)
            u8(20)
            zeros(4)
        }.toByteArray()
        return DjiFrame(SET_CAMERA, STATUS_SUBSCRIBE, seq, DjiFrame.NO_RESPONSE, data)
    }

    /**
     * Posición GPS (00/17). La cámara la guarda con el vídeo, y DJI Mimo saca de ahí la velocidad, el
     * recorrido y demás datos que se pueden sobreimprimir.
     * @param date año*10000 + mes*100 + día, en la hora que se manda a la cámara (ver DjiDriver.feedGps: UTC+1, porque la cámara le resta una hora)
     * @param time hora*10000 + minuto*100 + segundo, en esa misma hora
     * @param longitudeE7 grados por 10 millones
     * @param heightMm altitud en milímetros
     * @param northCms velocidad hacia el norte en cm/s; [eastCms] y [downCms], lo mismo hacia el este y hacia abajo
     */
    fun gpsPush(
        seq: Int,
        date: Int,
        time: Int,
        longitudeE7: Int,
        latitudeE7: Int,
        heightMm: Int,
        northCms: Float,
        eastCms: Float,
        downCms: Float,
        satellites: Int,
    ): DjiFrame {
        val data = ByteArrayOutputStream(48).apply {
            u32(date.toLong())
            u32(time.toLong())
            u32(longitudeE7.toLong() and 0xFFFFFFFFL)
            u32(latitudeE7.toLong() and 0xFFFFFFFFL)
            u32(heightMm.toLong() and 0xFFFFFFFFL)
            u32(northCms.toRawBits().toLong() and 0xFFFFFFFFL)
            u32(eastCms.toRawBits().toLong() and 0xFFFFFFFFL)
            u32(downCms.toRawBits().toLong() and 0xFFFFFFFFL)
            u32(1000) // precisión vertical estimada, en mm (el valor de la demo de DJI)
            u32(1000) // precisión horizontal, en mm
            u32(10) // precisión de la velocidad, en cm/s
            u32(satellites.toLong())
        }.toByteArray()
        return DjiFrame(SET_GENERAL, GPS_PUSH, seq, DjiFrame.NO_RESPONSE, data)
    }

    /**
     * Pulsación corta del botón lateral de la cámara (00/11, tecla 2). Mientras graba deja una marca de
     * momento destacado; sin grabar cambia de modo, así que solo se manda grabando.
     */
    fun quickSwitchKey(seq: Int): DjiFrame =
        DjiFrame(SET_GENERAL, KEY_REPORT, seq, DjiFrame.RESPONSE_OPTIONAL, byteArrayOf(0x02, 0x01, 0x00, 0x00))

    /** Modo de energía (00/1A): 3 duerme la cámara, 0 es el modo normal. */
    fun powerMode(seq: Int, sleep: Boolean): DjiFrame =
        DjiFrame(SET_GENERAL, POWER_MODE, seq, DjiFrame.RESPONSE_REQUIRED, byteArrayOf(if (sleep) 3 else 0))
}

/** Estado que la cámara empuja dos veces por segundo (1D/02). */
data class DjiStatus(
    val mode: Int,
    /** 0 pantalla apagada, 1 en espera, 2 reproduciendo, 3 grabando o haciendo foto, 5 pregrabando. */
    val cameraStatus: Int,
    val recordSeconds: Int,
    /** Segundos de grabación que caben en la tarjeta con los ajustes actuales. */
    val remainingSeconds: Long,
    private val resolution: Int,
    private val fps: Int,
    val asleep: Boolean,
    /** 0 normal, 1 aviso, 2 demasiado caliente para grabar, 3 apagándose por temperatura. */
    val overheat: Int,
    val batteryPercent: Int,
) {
    val recording: Boolean get() = cameraStatus == 3
    val playback: Boolean get() = cameraStatus == 2

    /** El modo, para los campos. Los que graban vídeo a tiempo real (normal, noche, seguimiento) cuentan como vídeo. */
    val cameraMode: es.bicilab.bicicam.camera.CameraMode
        get() = when (mode) {
            0x01, 0x34 -> es.bicilab.bicicam.camera.CameraMode.VIDEO
            0x28 -> es.bicilab.bicicam.camera.CameraMode.NIGHT
            0x05 -> es.bicilab.bicicam.camera.CameraMode.PHOTO
            0x02 -> es.bicilab.bicicam.camera.CameraMode.TIMELAPSE
            0x00 -> es.bicilab.bicicam.camera.CameraMode.SLOW_MOTION
            0x0A -> es.bicilab.bicicam.camera.CameraMode.HYPERLAPSE
            else -> es.bicilab.bicicam.camera.CameraMode.OTHER
        }

    /** Modo y calidad en corto: "4K 60" en vídeo, o el nombre del modo. */
    val format: String
        get() {
            val quality = listOfNotNull(RESOLUTIONS[resolution], FPS[fps]?.toString()).joinToString(" ")
            return when (mode) {
                0x01 -> quality.ifEmpty { t("Vídeo", "Video") }
                0x00 -> t("Cámara lenta", "Slow motion")
                0x02 -> "Timelapse"
                0x05 -> t("Foto", "Photo")
                0x0A -> "Hyperlapse"
                0x28 -> if (quality.isEmpty()) t("Noche", "Night") else t("Noche $quality", "Night $quality")
                else -> quality.ifEmpty { t("Otro modo", "Other mode") }
            }
        }

    companion object {
        private const val MIN_SIZE = 38
        private val RESOLUTIONS = mapOf(10 to "1080p", 16 to "4K", 45 to "2.7K", 66 to "1080p", 67 to "2.7K", 95 to "2.7K", 103 to "4K", 109 to "4K")
        private val FPS = mapOf(1 to 24, 2 to 25, 3 to 30, 4 to 48, 5 to 50, 6 to 60, 10 to 100, 7 to 120, 19 to 200, 8 to 240)

        fun parse(data: ByteArray): DjiStatus? {
            if (data.size < MIN_SIZE) return null
            return DjiStatus(
                mode = data.u8(0),
                cameraStatus = data.u8(1),
                recordSeconds = data.u16(5),
                remainingSeconds = data.u32(23),
                resolution = data.u8(2),
                fps = data.u8(3),
                asleep = data.u8(28) == 3,
                overheat = data.u8(30),
                batteryPercent = data.u8(37),
            )
        }
    }
}
