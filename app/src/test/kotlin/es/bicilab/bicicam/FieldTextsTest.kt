package es.bicilab.bicicam

import es.bicilab.bicicam.camera.AppSettings
import es.bicilab.bicicam.camera.CameraController
import es.bicilab.bicicam.camera.CameraState
import es.bicilab.bicicam.camera.Language
import es.bicilab.bicicam.extension.BatteryDataType
import es.bicilab.bicicam.extension.COMPACT_TEXT_MAX
import es.bicilab.bicicam.extension.CardDataType
import es.bicilab.bicicam.extension.MarkerDataType
import es.bicilab.bicicam.extension.PowerDataType
import es.bicilab.bicicam.extension.RecordBatteryDataType
import es.bicilab.bicicam.extension.RecordBatteryWideDataType
import es.bicilab.bicicam.extension.RecordDataType
import es.bicilab.bicicam.extension.StatusDataType
import es.bicilab.bicicam.extension.TapConfirmation
import es.bicilab.bicicam.extension.TileModel
import es.bicilab.bicicam.extension.batterySegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que se lee en los campos. En un campo de media anchura caben 15 letras de etiqueta o de ayuda:
 * si un texto se pasa, el Karoo lo corta justo cuando dice qué hacer.
 */
class FieldTextsTest {
    private val fields = listOf(
        StatusDataType("bicicam"), RecordDataType("bicicam"), RecordBatteryDataType("bicicam"), RecordBatteryWideDataType("bicicam"),
        BatteryDataType("bicicam"), PowerDataType("bicicam"), CardDataType("bicicam"), MarkerDataType("bicicam"),
    )

    private val ready = CameraState.Connected(statusKnown = true, batteryPercent = 100, remainingSeconds = 12 * 3600 + 30 * 60, format = "4K 60", screenOff = false)
    private val states = listOf(
        CameraState.NotPaired, CameraState.NoPermission, CameraState.Standby, CameraState.Searching, CameraState.Starting, CameraState.Asleep,
        CameraState.Off(canWake = true), CameraState.Off(canWake = false),
        ready, ready.copy(screenOff = true), ready.copy(busy = true), ready.copy(batteryPercent = null, runtime = "1h09m"),
        ready.copy(recording = true, recordingSince = 0, markers = 12), ready.copy(recording = true, recordingSince = 0, remainingSeconds = 300, batteryPercent = 9),
        ready.copy(batteryPercent = null, remainingSeconds = null, format = null, screenOff = null),
    )
    private val pendings = listOf(
        null,
        TapConfirmation.Pending(TapConfirmation.Kind.STOP_RECORDING, confirmed = false),
        TapConfirmation.Pending(TapConfirmation.Kind.STOP_RECORDING, confirmed = true),
        TapConfirmation.Pending(TapConfirmation.Kind.SWITCH_OFF, confirmed = false),
        TapConfirmation.Pending(TapConfirmation.Kind.SWITCH_OFF, confirmed = true),
    )

    /**
     * Ancho aproximado de un texto en Roboto, en múltiplos del tamaño de letra. En un campo de media anchura
     * quedan unos 86 dp para la etiqueta, a 13 sp: 6,6 em. Medido contra el emulador: «Solo al grabar» y
     * «Pulsa su botón» caben; «While recording» y «Pantalla apagada» se cortaban.
     */
    private fun widthEm(text: String): Float = text.sumOf { c ->
        when {
            c in "mwMW" -> 0.87
            c in "il.,:;'!| " -> 0.26
            c in "jtfrI¿?" -> 0.36
            c == '%' -> 0.73
            c.isUpperCase() -> 0.64
            c.isDigit() -> 0.56
            else -> 0.54
        }
    }.toFloat()

    // La cuenta se pasa un 2 % respecto a lo medido («Pulsa su botón» da 6,64 y en pantalla mide 6,5): de ahí el margen
    private val COMPACT_WIDTH_EM = 6.75f

    private fun everyCompactModel(): List<TileModel> = buildList {
        for (field in fields) {
            add(field.previewModel(compact = true))
            for (state in states) for (pending in pendings) add(field.model(state, pending, compact = true))
        }
        // La cámara que desapareció con poca batería (solo se da con un 10 % o menos)
        add(TileModel.unavailable(CameraState.Searching, "Batería", compact = true, empty = CameraController.ProbablyEmpty(10, 0))!!)
    }

    @Test
    fun `en los campos estrechos ninguna etiqueta ni ayuda pasa de 15 letras`() {
        for (language in Language.entries) {
            AppSettings.setLanguage(language)
            for (model in everyCompactModel()) {
                for (text in listOfNotNull(model.label, model.hint)) {
                    assertTrue("${language.code}: «$text» tiene ${text.length} letras", text.length <= COMPACT_TEXT_MAX)
                    // Lo que se acaba no son las letras sino el ancho: una «W» ocupa como tres «i»
                    assertTrue("${language.code}: «$text» mide ${widthEm(text)} em", widthEm(text) <= COMPACT_WIDTH_EM)
                }
                assertTrue("${language.code}: campo sin valor", model.value.isNotBlank())
            }
        }
        AppSettings.setLanguage(Language.SPANISH)
    }

    @Test
    fun `la ayuda solo sale cuando hay que hacer algo distinto de tocar`() {
        AppSettings.setLanguage(Language.SPANISH)
        val record = RecordDataType("bicicam")
        // Lista y grabando: el campo entero es el botón y no hace falta decir nada
        assertNull(record.model(ready, null, compact = true).hint)
        assertNull(record.model(ready.copy(recording = true, recordingSince = 0), null, compact = true).hint)
        // Dormida, apagada sin poder encenderla, o pidiendo confirmar: ahí sí
        assertEquals("Pulsa su botón", record.model(CameraState.Asleep, null, compact = true).hint)
        assertEquals("Pulsa su botón", record.model(CameraState.Off(canWake = false), null, compact = true).hint)
        assertNull(record.model(CameraState.Off(canWake = true), null, compact = true).hint)
        val stop = TapConfirmation.Pending(TapConfirmation.Kind.STOP_RECORDING, confirmed = false)
        assertEquals("Toca otra vez", record.model(ready.copy(recording = true, recordingSince = 0), stop, compact = true).hint)
    }

    @Test
    fun `cada estado tiene su color y el texto se lee sobre el`() {
        val record = RecordDataType("bicicam")
        val recording = ready.copy(recording = true, recordingSince = 0)
        assertEquals(TileModel.READY, record.model(ready, null, compact = false).background)
        assertEquals(TileModel.RECORDING, record.model(recording, null, compact = false).background)
        assertEquals(TileModel.UNAVAILABLE, record.model(CameraState.Searching, null, compact = false).background)
        val stop = TapConfirmation.Pending(TapConfirmation.Kind.STOP_RECORDING, confirmed = false)
        val asking = record.model(recording, stop, compact = false)
        assertEquals(TileModel.ATTENTION, asking.background)
        // Sobre gris claro y ámbar, tinta marina; sobre marino y rojo, blanco
        assertEquals(TileModel.INK, asking.foreground)
        assertEquals(TileModel.INK, record.model(CameraState.Searching, null, compact = false).foreground)
        assertNotEquals(TileModel.INK, record.model(recording, null, compact = false).foreground)
    }

    @Test
    fun `la bateria pasa de verde a amarillo y a rojo, y se lee sobre cada fondo`() {
        for (background in listOf(TileModel.READY, TileModel.RECORDING, TileModel.UNAVAILABLE)) {
            val good = TileModel.batteryColor(80, background)
            val medium = TileModel.batteryColor(20, background)
            assertEquals(good, TileModel.batteryColor(30, background))
            assertEquals(medium, TileModel.batteryColor(29, background))
            assertEquals(medium, TileModel.batteryColor(15, background))
            assertNotEquals(good, medium)
            // Ningún color de batería puede confundirse con su fondo
            for (percent in listOf(100, 20, 5)) assertNotEquals(background, TileModel.batteryColor(percent, background))
        }
        assertNotEquals(TileModel.batteryColor(20, TileModel.READY), TileModel.batteryColor(14, TileModel.READY))
    }

    @Test
    fun `los segmentos de la bateria van con sus colores`() {
        assertEquals(0, batterySegments(null))
        assertEquals(0, batterySegments(0))
        // Los segmentos van con los colores: rojo, ninguno; amarillo, uno; verde, de dos a cuatro
        assertEquals(0, batterySegments(14))
        assertEquals(1, batterySegments(15))
        assertEquals(1, batterySegments(29))
        assertEquals(2, batterySegments(30))
        assertEquals(3, batterySegments(55))
        assertEquals(3, batterySegments(79))
        assertEquals(4, batterySegments(80))
        assertEquals(4, batterySegments(100))
    }

    @Test
    fun `la tarjeta dice las horas como se dicen en cada idioma`() {
        AppSettings.setLanguage(Language.SPANISH)
        assertEquals("1 h 32", CardDataType.format(92 * 60))
        assertEquals("45 min", CardDataType.format(45 * 60))
        AppSettings.setLanguage(Language.ENGLISH)
        assertEquals("1 h 32", CardDataType.format(92 * 60))
        assertEquals("87%", TileModel.percent(87))
        AppSettings.setLanguage(Language.SPANISH)
        assertEquals("87 %", TileModel.percent(87))
    }
}
