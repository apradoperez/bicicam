package es.bicilab.bicicam

import es.bicilab.bicicam.gopro.GoProCommands
import es.bicilab.bicicam.gopro.GoProStatus
import es.bicilab.bicicam.gopro.PacketAccumulator
import es.bicilab.bicicam.gopro.TlvResponse
import es.bicilab.bicicam.gopro.canWakeOverBle
import es.bicilab.bicicam.gopro.parseModelName
import es.bicilab.bicicam.gopro.toUnsignedInt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Las tramas son las que publica GoPro en la especificación de Open GoPro. */
class GoProProtocolTest {
    private fun bytes(vararg v: Int) = v.map { it.toByte() }.toByteArray()

    @Test
    fun commands() {
        assertArrayEquals(bytes(0x03, 0x01, 0x01, 0x01), GoProCommands.shutter(true))
        assertArrayEquals(bytes(0x03, 0x01, 0x01, 0x00), GoProCommands.shutter(false))
        assertArrayEquals(bytes(0x01, 0x05), GoProCommands.SLEEP_COMMAND)
        assertArrayEquals(bytes(0x01, 0x18), GoProCommands.HILIGHT_COMMAND)
        assertArrayEquals(bytes(0x03, 0x5B, 0x01, 0x42), GoProCommands.KEEP_ALIVE)
        assertArrayEquals(bytes(0x04, 0x53, 8, 10, 70), GoProCommands.registerStatusUpdates(8, 10, 70))
    }

    @Test
    fun statusPush() {
        // Aviso de estado: graba, 75 % de batería, 5 h 25 de tarjeta
        val message = PacketAccumulator().accept(bytes(0x10, 0x93, 0x00, 10, 1, 1, 70, 1, 75, 35, 4, 0x00, 0x00, 0x4C, 0x2C, 8, 1, 0))!!
        val response = TlvResponse.parse(message)!!
        assertEquals(GoProCommands.QUERY_STATUS_PUSH, response.id)
        assertEquals(0, response.status)
        val values = response.values()
        assertEquals(1L, values[GoProStatus.ENCODING]!!.toUnsignedInt())
        assertEquals(75L, values[GoProStatus.BATTERY_PERCENT]!!.toUnsignedInt())
        assertEquals(19500L, values[GoProStatus.REMAINING_VIDEO_TIME]!!.toUnsignedInt())
        assertEquals(0L, values[GoProStatus.BUSY]!!.toUnsignedInt())
    }

    @Test
    fun continuationPackets() {
        val acc = PacketAccumulator()
        // Cabecera extendida de 13 bits: 25 bytes repartidos en dos paquetes
        assertNull(acc.accept(bytes(0x20, 25) + ByteArray(18) { it.toByte() }))
        val message = acc.accept(bytes(0x80) + ByteArray(7) { (18 + it).toByte() })!!
        assertArrayEquals(ByteArray(25) { it.toByte() }, message)
        // Una continuación suelta no es un mensaje
        assertNull(PacketAccumulator().accept(bytes(0x80, 1, 2)))
    }

    @Test
    fun truncatedValuesAreIgnored() {
        val response = TlvResponse(0x93, 0, bytes(70, 1, 50, 35, 4, 0x00))
        assertEquals(setOf(70), response.values().keys)
    }

    @Test
    fun model() {
        val payload = bytes(4, 0, 0, 0, 62, 12) + "HERO12 Black".toByteArray() + bytes(4, 1, 2, 3, 4)
        assertEquals("HERO12 Black", parseModelName(payload))
        assertNull(parseModelName(bytes(4, 0, 0)))
        assertTrue(canWakeOverBle("HERO12 Black (GoPro 1234)"))
        assertTrue(canWakeOverBle("HERO9 Black"))
        assertFalse(canWakeOverBle("HERO8 Black"))
        assertTrue(canWakeOverBle("GoPro 1234"))
    }
}
