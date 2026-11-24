package es.bicilab.bicicam

import es.bicilab.bicicam.camera.Language
import es.bicilab.bicicam.camera.AppSettings
import es.bicilab.bicicam.dji.DjiCommands
import es.bicilab.bicicam.dji.DjiFrame
import es.bicilab.bicicam.dji.DjiFrameAssembler
import es.bicilab.bicicam.dji.DjiStatus
import es.bicilab.bicicam.dji.isDjiCameraAdvert
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun hex(text: String): ByteArray = text.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** Las tramas de referencia son las que publica DJI en su demo oficial (Osmo-GPS-Controller-Demo). */
class DjiProtocolTest {
    /** docs/protocol_data_segment.md, ejemplo de cambio a Hyperlapse. */
    private val modeSwitch = hex("AA 1B 00 01 00 00 00 00 05 00 57 EE 1D 04 00 00 33 FF 0A 01 47 39 36 F4 FA E1 D0")

    /** test/connect_cmd_frame_builder/connect_cmd_frame.txt, primera línea. */
    private val connection = hex(
        "AA330002000000000100982F00197856341206383456789ABC00000000000000000000000000000000FE1F00000000796C667C",
    )

    @Test
    fun `codifica la trama de cambio de modo igual que el ejemplo de DJI`() {
        val frame = DjiFrame(0x1D, 0x04, seq = 5, cmdType = DjiFrame.RESPONSE_OPTIONAL, payload = hex("00 00 33 FF 0A 01 47 39 36"))
        assertArrayEquals(modeSwitch, frame.encode())
    }

    @Test
    fun `la peticion de conexion sale identica a la de la demo de DJI`() {
        val frame = DjiCommands.connectionRequest(
            seq = 1, deviceId = 0x12345678, mac = hex("38 34 56 78 9A BC"), verifyMode = 0, verifyCode = 0x1FFE,
        )
        assertArrayEquals(connection, frame.encode())
    }

    @Test
    fun `recompone tramas partidas y salta lo que no es R SDK`() {
        val assembler = DjiFrameAssembler()
        // Telemetria DUML (0x55...) con un 0xAA suelto dentro, y luego la trama partida en tres avisos
        assertTrue(assembler.feed(hex("55 0D 04 AA 33 02 01")).isEmpty())
        assertTrue(assembler.feed(modeSwitch.copyOfRange(0, 7)).isEmpty())
        assertTrue(assembler.feed(modeSwitch.copyOfRange(7, 20)).isEmpty())
        val frames = assembler.feed(modeSwitch.copyOfRange(20, modeSwitch.size) + connection)
        assertEquals(2, frames.size)
        assertTrue(frames[0].isCommand(0x1D, 0x04))
        assertEquals(5, frames[0].seq)
        assertFalse(frames[0].isResponse)
        assertTrue(frames[1].isCommand(0x00, 0x19))
        assertEquals(33, frames[1].payload.size)
    }

    @Test
    fun `descarta una trama con el CRC32 estropeado`() {
        val broken = modeSwitch.copyOf().also { it[16] = 0x00 }
        assertTrue(DjiFrameAssembler().feed(broken).isEmpty())
    }

    @Test
    fun `lee el veredicto de la peticion de conexion de la camara`() {
        fun cameraRequest(mode: Int, result: Int) = DjiFrame(
            0x00, 0x19, seq = 9, cmdType = DjiFrame.RESPONSE_REQUIRED,
            payload = hex("55 FF 00 00") + ByteArray(22) + byteArrayOf(mode.toByte(), result.toByte(), 0, 0, 0, 0, 0),
        )
        assertEquals(true, DjiCommands.verifyResult(cameraRequest(2, 0)))
        assertEquals(false, DjiCommands.verifyResult(cameraRequest(2, 1)))
        assertNull(DjiCommands.verifyResult(cameraRequest(1, 0)))
    }

    @Test
    fun `ordenes de grabar parar y dormir`() {
        val start = DjiCommands.recordControl(seq = 2, cameraDeviceId = 0xFF55, start = true)
        assertArrayEquals(hex("55 FF 00 00 00 00 00 00 00"), start.payload)
        val stop = DjiCommands.recordControl(seq = 3, cameraDeviceId = 0xFF55, start = false)
        assertArrayEquals(hex("55 FF 00 00 01 00 00 00 00"), stop.payload)
        assertArrayEquals(hex("03"), DjiCommands.powerMode(seq = 4, sleep = true).payload)
        assertArrayEquals(hex("03 14 00 00 00 00"), DjiCommands.statusSubscribe(seq = 5).payload)
    }

    @Test
    fun `lee el estado que empuja la camara`() {
        val data = ByteArray(38)
        data[0] = 0x01 // modo video
        data[1] = 0x03 // grabando
        data[5] = 0x2C; data[6] = 0x01 // 300 s
        data[28] = 0x00
        data[37] = 87
        val status = DjiStatus.parse(data)!!
        assertTrue(status.recording)
        assertEquals(300, status.recordSeconds)
        assertFalse(status.asleep)
        assertEquals(87, status.batteryPercent)
        assertNull(DjiStatus.parse(ByteArray(20)))
        data[28] = 0x03
        assertTrue(DjiStatus.parse(data)!!.asleep)
    }

    @Test
    fun `la posicion GPS sale identica a la de prueba de DJI`() {
        // test/test_gps.c, primer paquete: 3 de julio de 2025, 17:34:50 (UTC+8), 113,9389116 E, 22,5791907 N
        val expected = hex(
            "AA 42 00 00 00 00 00 00 03 00 D2 DD 00 17 4F 00 35 01 8A A5 02 00 BC B2 E9 43 A3 4F 75 0D B9 6D" +
                "00 00 DE 58 24 41 8D 9A C5 41 00 00 00 00 E8 03 00 00 E8 03 00 00 0A 00 00 00 0E 00 00 00 63 B5 D1 31",
        )
        val frame = DjiCommands.gpsPush(
            seq = 3,
            date = 20250703,
            time = 173450,
            longitudeE7 = 1139389116,
            latitudeE7 = 225791907,
            heightMm = 28089,
            northCms = Float.fromBits(0x412458DE),
            eastCms = Float.fromBits(0x41C59A8D),
            downCms = 0f,
            satellites = 14,
        )
        assertArrayEquals(expected, frame.encode())
    }

    @Test
    fun `lee la tarjeta y el modo del estado`() {
        val data = ByteArray(38)
        data[0] = 0x01 // video
        data[2] = 16 // 4K
        data[3] = 6 // 60 fps
        data[23] = 0x90.toByte(); data[24] = 0x15 // 5520 s
        AppSettings.setLanguage(Language.SPANISH)
        val status = DjiStatus.parse(data)!!
        assertEquals(5520L, status.remainingSeconds)
        assertEquals("4K 60", status.format)
        data[0] = 0x05
        assertEquals("Foto", DjiStatus.parse(data)!!.format)
        AppSettings.setLanguage(Language.ENGLISH)
        assertEquals("Photo", DjiStatus.parse(data)!!.format)
    }

    @Test
    fun `reconoce el anuncio de una camara DJI`() {
        // Campo de fabricante AA 08 xx xx FA: Android lo entrega sin los dos primeros bytes
        assertTrue(isDjiCameraAdvert(hex("12 00 FA 01")))
        assertFalse(isDjiCameraAdvert(hex("12 00 FB 01")))
        assertFalse(isDjiCameraAdvert(null))
    }
}
