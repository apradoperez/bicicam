package es.bicilab.bicicam

import es.bicilab.bicicam.insta360.InstaButton
import es.bicilab.bicicam.insta360.InstaCameraFrame
import es.bicilab.bicicam.insta360.InstaDisplay
import es.bicilab.bicicam.insta360.InstaFix
import es.bicilab.bicicam.insta360.InstaFrames
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

private fun hex(text: String): ByteArray = text.replace(" ", "").replace("\n", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** Las tramas de referencia salen de las capturas publicadas en TheAngryRaven/insta360-ble-gps-spec. */
class InstaProtocolTest {
    @Test
    fun `pulsaciones de los botones`() {
        assertArrayEquals(hex("FC EF FE 86 00 03 01 02 00"), InstaFrames.button(0, InstaButton.SHUTTER, InstaButton.CLICK))
        assertArrayEquals(hex("FC EF FE 86 02 03 01 01 00"), InstaFrames.button(2, InstaButton.MODE, InstaButton.CLICK))
        assertArrayEquals(hex("FC EF FE 86 04 03 01 00 03"), InstaFrames.button(4, InstaButton.POWER, InstaButton.HELD))
    }

    @Test
    fun `la baliza que despierta a la camara 34UQG5`() {
        // Anuncio completo: 02 01 05 1B FF 4C 00 + estos 24 bytes
        val expected = hex("02 15 09 4F 52 42 49 54 09 FF 0F 00 33 34 55 51 47 35 00 00 00 00 E4 01")
        assertArrayEquals(expected, InstaFrames.beacon("34UQG5"))
        assertArrayEquals(expected, InstaFrames.beacon("34uqg5"))
        assertEquals(0x004C, InstaFrames.BEACON_COMPANY_ID)
    }

    @Test
    fun `la trama GPS del ejemplo de la especificacion`() {
        // 40 grados N, 74 grados O, el 1 de enero de 2026 a las 12:00:00 UTC
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, Calendar.JANUARY, 1, 12, 0, 0)
        }.timeInMillis
        val fix = InstaFix(latitude = 40.0, longitude = -74.0, speedMps = 0.0, bearing = 0.0)
        assertEquals(
            "\$GNRMC,120000.000,A,4000.0000,N,-7400.0000,E,0.00,0.00,010126,0.0,W,A,V*6E",
            InstaFrames.sentenceFor(fix, utc),
        )
        val expected = hex(
            """
            FC EF FE 83 00 52 2C 32 36 2E 37 2C 07 2C 24 47 4E 52 4D 43 2C 31 32 30 30 30 30 2E 30 30 30 2C 41 2C
            34 30 30 30 2E 30 30 30 30 2C 4E 2C 2D 37 34 30 30 2E 30 30 30 30 2C 45 2C 30 2E 30 30 2C 30 2E 30 30 2C
            30 31 30 31 32 36 2C 30 2E 30 2C 57 2C 41 2C 56 2A 36 45
            """,
        )
        assertArrayEquals(expected, InstaFrames.gps(fix, utc))
    }

    @Test
    fun `sin posicion se manda la frase vacia con su suma`() {
        val frame = InstaFrames.gps(null, 0L)
        val text = frame.copyOfRange(6, frame.size).toString(Charsets.US_ASCII)
        assertTrue(text.startsWith(",26.7,\u0007,\$GNRMC,000000.000,V,,,,,,,010170,0.0,W,N,V*"))
        assertEquals(frame.size - 6, frame[5].toInt() and 0xFF)
    }

    @Test
    fun `grados y minutos`() {
        assertEquals("4025.1234", InstaFrames.degreesMinutes(40.0 + 25.1234 / 60))
        assertEquals("342.5000", InstaFrames.degreesMinutes(-(3.0 + 42.5 / 60)))
        // 59,99996 minutos redondean al grado siguiente, no a "60.0000"
        assertEquals("4100.0000", InstaFrames.degreesMinutes(40.0 + 59.99996 / 60))
    }

    @Test
    fun `saludo con el numero de serie`() {
        val frame = InstaCameraFrame.parse(hex("FE EF FE 07 00 06 33 34 55 51 47 35"))!!
        assertEquals("34UQG5", frame.serial())
        assertNull(frame.displayText())
        assertNull(InstaCameraFrame.parse(hex("FC EF FE 07 00 06 33 34 55 51 47 35")))
    }

    @Test
    fun `textos de pantalla de la camara`() {
        fun display(bytes: String) = InstaDisplay.classify(InstaCameraFrame.parse(hex(bytes))!!.displayText()!!)
        assertEquals(InstaDisplay.Other("4K|30|UW"), display("FE EF FE 10 81 0C 01 1C 5E 00 34 4B 7C 33 30 7C 55 57"))
        assertEquals(InstaDisplay.Runtime("13h09m"), display("FE EF FE 10 80 0B 01 12 46 01 20 31 33 68 30 39 6D"))
        assertEquals(InstaDisplay.Recording(5), display("FE EF FE 10 80 0D 01 0E 46 01 2E 30 30 3A 30 30 3A 30 35"))
        assertEquals(InstaDisplay.Recording(3723), InstaDisplay.classify("01:02:03"))
    }
}
