package es.bicilab.bicicam.gopro

import java.io.ByteArrayOutputStream
import java.util.UUID

/*
 * Protocolo Open GoPro por Bluetooth (https://gopro.github.io/OpenGoPro/ble).
 * Adaptado de Kamerad, de Horst Tellioglu (licencia MIT), que es de donde viene BiciCAM.
 */
object GoProUuids {
    /** Servicio de 16 bits que anuncian las cámaras GoPro. */
    val ADVERTISED_SERVICE: UUID = UUID.fromString("0000fea6-0000-1000-8000-00805f9b34fb")

    private fun gp(id: String): UUID = UUID.fromString("b5f9$id-aa8d-11e3-9046-0002a5d5c51b")

    val COMMAND: UUID = gp("0072")
    val COMMAND_RESPONSE: UUID = gp("0073")
    val SETTING: UUID = gp("0074")
    val SETTING_RESPONSE: UUID = gp("0075")
    val QUERY: UUID = gp("0076")
    val QUERY_RESPONSE: UUID = gp("0077")
}

object GoProCommands {
    const val SET_SHUTTER = 0x01
    const val SLEEP = 0x05
    const val TAG_HILIGHT = 0x18
    const val GET_HARDWARE_INFO = 0x3C

    const val QUERY_GET_STATUS = 0x13
    const val QUERY_REGISTER_STATUS = 0x53
    const val QUERY_STATUS_PUSH = 0x93

    fun shutter(on: Boolean): ByteArray = byteArrayOf(0x03, SET_SHUTTER.toByte(), 0x01, if (on) 0x01 else 0x00)

    val GET_HARDWARE_INFO_COMMAND: ByteArray = byteArrayOf(0x01, GET_HARDWARE_INFO.toByte())

    /** Duerme la cámara; sigue anunciándose y despierta cuando un cliente se conecta (HERO9 en adelante). */
    val SLEEP_COMMAND: ByteArray = byteArrayOf(0x01, SLEEP.toByte())

    /** Marca HiLight en el vídeo que se está grabando. */
    val HILIGHT_COMMAND: ByteArray = byteArrayOf(0x01, TAG_HILIGHT.toByte())

    /** Ajuste 91 (LED) = 66: la señal de «sigo aquí» que documenta GoPro; hay que mandarla cada pocos segundos. */
    val KEEP_ALIVE: ByteArray = byteArrayOf(0x03, 0x5B, 0x01, 0x42)

    fun registerStatusUpdates(vararg statusIds: Int): ByteArray =
        byteArrayOf((statusIds.size + 1).toByte(), QUERY_REGISTER_STATUS.toByte()) +
            statusIds.map { it.toByte() }.toByteArray()
}

object GoProStatus {
    const val BUSY = 8
    const val ENCODING = 10

    /** Segundos que lleva el vídeo en curso. */
    const val VIDEO_DURATION = 13

    /** Segundos de vídeo que caben aún en la tarjeta con los ajustes actuales. */
    const val REMAINING_VIDEO_TIME = 35
    const val BATTERY_PERCENT = 70
}

/** Una respuesta completa: `id`, `status` y el resto. */
class TlvResponse(val id: Int, val status: Int, val payload: ByteArray) {
    /** El contenido de una consulta de estado, como pares `id -> valor`. */
    fun values(): Map<Int, ByteArray> {
        val result = mutableMapOf<Int, ByteArray>()
        var i = 0
        while (i + 2 <= payload.size) {
            val id = payload[i].toInt() and 0xFF
            val len = payload[i + 1].toInt() and 0xFF
            if (i + 2 + len > payload.size) break
            result[id] = payload.copyOfRange(i + 2, i + 2 + len)
            i += 2 + len
        }
        return result
    }

    companion object {
        fun parse(message: ByteArray): TlvResponse? =
            if (message.size < 2) {
                null
            } else {
                TlvResponse(message[0].toInt() and 0xFF, message[1].toInt() and 0xFF, message.copyOfRange(2, message.size))
            }
    }
}

/** Valor sin signo, con el byte más alto primero. */
fun ByteArray.toUnsignedInt(): Long = fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }

/**
 * Junta los paquetes Bluetooth (cabecera general de 5 bits, extendidas de 13 y 16, y paquetes de
 * continuación) en mensajes completos. Una instancia por característica de respuesta.
 */
class PacketAccumulator {
    private val buffer = ByteArrayOutputStream()
    private var remaining = 0

    /** Devuelve el mensaje completo cuando han llegado todos sus paquetes; si no, null. */
    fun accept(packet: ByteArray): ByteArray? {
        if (packet.isEmpty()) return null
        val header = packet[0].toInt() and 0xFF
        val payloadStart: Int
        if (header and 0x80 != 0) {
            if (remaining <= 0) return null
            payloadStart = 1
        } else {
            buffer.reset()
            when ((header and 0x60) shr 5) {
                0 -> {
                    remaining = header and 0x1F
                    payloadStart = 1
                }
                1 -> {
                    if (packet.size < 2) return null
                    remaining = ((header and 0x1F) shl 8) or (packet[1].toInt() and 0xFF)
                    payloadStart = 2
                }
                2 -> {
                    if (packet.size < 3) return null
                    remaining = ((packet[1].toInt() and 0xFF) shl 8) or (packet[2].toInt() and 0xFF)
                    payloadStart = 3
                }
                else -> return null
            }
        }
        val chunk = packet.copyOfRange(minOf(payloadStart, packet.size), packet.size)
        buffer.write(chunk)
        remaining -= chunk.size
        if (remaining > 0) return null
        remaining = 0
        return buffer.toByteArray()
    }
}

/** Nombre del modelo (por ejemplo "HERO12 Black") en la respuesta a Get Hardware Info; null si viene mal. */
fun parseModelName(payload: ByteArray): String? {
    val numberLength = payload.getOrNull(0)?.toInt()?.and(0xFF) ?: return null
    val nameAt = 1 + numberLength
    val nameLength = payload.getOrNull(nameAt)?.toInt()?.and(0xFF) ?: return null
    if (nameAt + 1 + nameLength > payload.size) return null
    return payload.copyOfRange(nameAt + 1, nameAt + 1 + nameLength).decodeToString().trim()
}

/**
 * Si una cámara dormida de este modelo despierta cuando se le conecta por Bluetooth. GoPro lo documenta
 * de la HERO9 en adelante; una HERO8 sigue dormida. Con un modelo desconocido se supone que sí.
 */
fun canWakeOverBle(modelName: String?): Boolean {
    val generation = modelName?.let { Regex("""HERO(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: return true
    return generation >= 9
}
