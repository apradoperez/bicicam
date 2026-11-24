package es.bicilab.bicicam

import es.bicilab.bicicam.camera.AUTO_STOP_GRACE_MS
import es.bicilab.bicicam.camera.CameraMode
import es.bicilab.bicicam.camera.CameraState
import es.bicilab.bicicam.camera.shouldReturnToVideo
import es.bicilab.bicicam.camera.RecordIntent
import es.bicilab.bicicam.camera.RecordOrder
import es.bicilab.bicicam.camera.autoStopDelayMs
import es.bicilab.bicicam.camera.cameraStalled
import es.bicilab.bicicam.camera.isStaleRecordingStatus
import es.bicilab.bicicam.camera.recordIntentAtTap
import es.bicilab.bicicam.camera.recordOrder
import es.bicilab.bicicam.camera.scanWaitMs
import es.bicilab.bicicam.extension.TapConfirmation
import es.bicilab.bicicam.extension.TileIcon
import es.bicilab.bicicam.extension.centersInField
import es.bicilab.bicicam.extension.RIGHT_MAX_OF_VALUE
import es.bicilab.bicicam.extension.leftFit
import es.bicilab.bicicam.extension.wideFit
import es.bicilab.bicicam.camera.clockIsRealTime
import es.bicilab.bicicam.camera.clockTrusted
import es.bicilab.bicicam.extension.textEm
import es.bicilab.bicicam.camera.steadyTicksAfter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Las decisiones que, si fallan, cortan una grabación o engañan a quien va en la bici. */
class DecisionsTest {
    private val idle = CameraState.Connected(statusKnown = true)
    private val recording = CameraState.Connected(statusKnown = true, recording = true, recordingSince = 1_000)

    // --- Qué quiere el toque (fallo C1 de la auditoría del 21/11/2026)

    @Test
    fun `un toque sin conexion nunca es parar`() {
        for (state in listOf(CameraState.Searching, CameraState.Standby, CameraState.Starting, CameraState.Asleep, CameraState.Off(canWake = false), CameraState.NotPaired)) {
            assertEquals(state.toString(), RecordIntent.START, recordIntentAtTap(state))
        }
    }

    @Test
    fun `solo es parar si el campo decia que grababa`() {
        assertEquals(RecordIntent.STOP, recordIntentAtTap(recording))
        assertEquals(RecordIntent.START, recordIntentAtTap(idle))
        // Conectada pero sin saber aún si graba: el campo enseña REC, el toque es grabar
        assertEquals(RecordIntent.START, recordIntentAtTap(CameraState.Connected(statusKnown = false, recording = true)))
        // Colgada: no se sabe si graba; un toque no la para
        assertEquals(RecordIntent.START, recordIntentAtTap(recording.copy(stalled = true)))
    }

    @Test
    fun `el caso del 14 de noviembre - toque durante el corte y la camara vuelve grabando`() {
        val intent = recordIntentAtTap(CameraState.Searching)
        assertEquals(RecordOrder.ALREADY_RECORDING, recordOrder(intent, recordingNow = true))
    }

    @Test
    fun `la orden sale solo si cambia algo`() {
        assertEquals(RecordOrder.START, recordOrder(RecordIntent.START, recordingNow = false))
        assertEquals(RecordOrder.STOP, recordOrder(RecordIntent.STOP, recordingNow = true))
        assertEquals(RecordOrder.ALREADY_STOPPED, recordOrder(RecordIntent.STOP, recordingNow = false))
    }

    // --- Parada automática (fallo A2)

    @Test
    fun `sin limite no hay parada`() {
        assertNull(autoStopDelayMs(since = 0, limitSetAt = null, minutes = 0, now = 10_000))
    }

    @Test
    fun `el limite cuenta desde que empezo el clip`() {
        assertEquals(120_000L, autoStopDelayMs(since = 0, limitSetAt = null, minutes = 3, now = 60_000))
        assertEquals(0L, autoStopDelayMs(since = 0, limitSetAt = null, minutes = 3, now = 200_000))
    }

    @Test
    fun `cambiar el ajuste con cinco minutos grabados no corta el clip`() {
        val fiveMinutes = 300_000L
        // El ajuste pasa por 3 min camino de 6: antes el retraso salía negativo y paraba en el acto
        val wait = autoStopDelayMs(since = 0, limitSetAt = fiveMinutes, minutes = 3, now = fiveMinutes)!!
        assertEquals(180_000L, wait)
        assertTrue(wait >= AUTO_STOP_GRACE_MS)
    }

    @Test
    fun `un ajuste tocado antes de este clip no cuenta`() {
        assertEquals(170_000L, autoStopDelayMs(since = 100_000, limitSetAt = 50_000, minutes = 3, now = 110_000))
    }

    // --- Cámara colgada (fallo A1)

    @Test
    fun `sin grabar nunca se da por colgada`() {
        assertFalse(cameraStalled(recording = false, lastStatusAt = 0, clockAdvancedAt = 0, now = 60_000))
    }

    @Test
    fun `grabando y callada mas de cinco segundos`() {
        assertFalse(cameraStalled(recording = true, lastStatusAt = 10_000, clockAdvancedAt = 10_000, now = 14_000))
        assertTrue(cameraStalled(recording = true, lastStatusAt = 10_000, clockAdvancedAt = 10_000, now = 15_500))
    }

    @Test
    fun `grabando con el contador parado aunque siga mandando estado`() {
        assertTrue(cameraStalled(recording = true, lastStatusAt = 19_900, clockAdvancedAt = 13_000, now = 20_000))
        // Una cámara que no manda contador no se juzga por él
        assertFalse(cameraStalled(recording = true, lastStatusAt = 19_900, clockAdvancedAt = null, now = 20_000))
    }

    // --- Estado viejo tras una orden (fallo A3)

    @Test
    fun `un estado contrario a la orden recien mandada es viejo`() {
        assertTrue(isStaleRecordingStatus(reported = false, ordered = true, orderedAt = 1_000, now = 1_400))
        assertFalse(isStaleRecordingStatus(reported = true, ordered = true, orderedAt = 1_000, now = 1_400))
        assertFalse(isStaleRecordingStatus(reported = false, ordered = true, orderedAt = 1_000, now = 3_000))
        assertFalse(isStaleRecordingStatus(reported = false, ordered = null, orderedAt = 0, now = 100))
    }

    // --- Dos toques (fallo M5)

    @Test
    fun `parando se limpia cuando la camara deja de grabar`() {
        val stop = TapConfirmation.Pending(TapConfirmation.Kind.STOP_RECORDING, confirmed = true)
        assertFalse(TapConfirmation.isDone(stop, recording))
        assertTrue(TapConfirmation.isDone(stop, idle))
        assertTrue(TapConfirmation.isDone(stop, CameraState.Searching))
        // Esperando el segundo toque no hay nada hecho todavía
        assertFalse(TapConfirmation.isDone(stop.copy(confirmed = false), idle))
        val off = TapConfirmation.Pending(TapConfirmation.Kind.SWITCH_OFF, confirmed = true)
        assertFalse(TapConfirmation.isDone(off, idle))
        assertTrue(TapConfirmation.isDone(off, CameraState.Off(canWake = false)))
    }

    // --- Búsquedas Bluetooth (fallo M2)

    @Test
    fun `las busquedas se espacian`() {
        assertEquals(0L, scanWaitMs(last = 0, now = 5_000, minGap = 6_500))
        assertEquals(4_500L, scanWaitMs(last = 10_000, now = 12_000, minGap = 6_500))
        assertEquals(0L, scanWaitMs(last = 10_000, now = 30_000, minGap = 6_500))
    }

    // --- Dibujo de los campos anchos

    @Test
    fun `el punto va encima cuando asi REC sale mas grande`() {
        // Campo ancho de altura normal: sobra alto, el ancho manda
        val fit = leftFit(textEm = 3 * 0.6f, icon = TileIcon.Record, room = 90f, rowHeight = 60f, maxSp = 60f)
        assertTrue(fit.above)
        assertTrue(fit.size <= 60f / 1.7f + 0.01f)
    }

    @Test
    fun `en un campo bajo el punto sigue delante`() {
        val fit = leftFit(textEm = 3 * 0.6f, icon = TileIcon.Record, room = 110f, rowHeight = 24f, maxSp = 60f)
        assertFalse(fit.above)
    }

    @Test
    fun `la bateria nunca lleva el dibujo encima`() {
        assertFalse(leftFit(textEm = 4 * 0.6f, icon = TileIcon.Battery(80), room = 110f, rowHeight = 60f, maxSp = 60f).above)
        assertFalse(leftFit(textEm = 8 * 0.6f, icon = null, room = 110f, rowHeight = 60f, maxSp = 60f).above)
    }

    @Test
    fun `el contador largo cabe en su ancho`() {
        for (length in listOf(5, 7)) {
            val fit = leftFit(textEm = length * 0.6f, icon = TileIcon.Pilot(lit = true), room = 110f, rowHeight = 60f, maxSp = 60f)
            val widthEm = if (fit.above) length * 0.6f else length * 0.6f + 1.0f + 0.3f
            assertTrue("$length letras", fit.size * widthEm <= 110f + 0.01f)
        }
    }

    @Test
    fun `el valor se centra en el campo entero solo si no pisa la cabecera`() {
        assertTrue(centersInField(fieldHeight = 60f, valueSize = 30f, headerHeight = 14f))
        assertFalse(centersInField(fieldHeight = 60f, valueSize = 44f, headerHeight = 14f))
        assertTrue(centersInField(fieldHeight = 60f, valueSize = 58f, headerHeight = 0f))
    }

    @Test
    fun `con el contador de una hora el valor sigue mandando sobre la bateria`() {
        // Campo ancho de 192 de ancho y 60 de alto, columna derecha «87 %» con su dibujo
        val rightEm = 1.21f + 0.3f + 4 * 0.6f
        for (length in listOf(3, 5, 7)) {
            val fit = wideFit(textEm = length * 0.6f, icon = TileIcon.Pilot(lit = true), rightEm = rightEm, rightStart = 27f, rightMin = 16f, widthSp = 192f, rowHeight = 60f, maxSp = 60f)
            assertTrue("$length letras: derecha ${fit.rightSize}, valor ${fit.left.size}", fit.rightSize <= maxOf(16f, fit.left.size * RIGHT_MAX_OF_VALUE) + 0.01f)
            assertTrue(fit.rightSize >= 16f - 0.01f)
        }
    }

    // --- El contador de la cámara como vigía, solo a tiempo real (problema N1 de la ronda 2)

    @Test
    fun `el contador vale de vigia cuando lleva tres avances a ritmo de un segundo`() {
        var ticks = 0
        ticks = steadyTicksAfter(ticks, null)
        assertFalse(clockIsRealTime(ticks))
        repeat(3) { ticks = steadyTicksAfter(ticks, 1_000) }
        assertTrue(clockIsRealTime(ticks))
    }

    @Test
    fun `en timelapse el contador avanza despacio y no se usa`() {
        var ticks = 0
        repeat(10) { ticks = steadyTicksAfter(ticks, 4_000) }
        assertFalse(clockIsRealTime(ticks))
    }

    @Test
    fun `un bloqueo a los dos segundos de grabar en video normal se ve`() {
        // El caso del 14/11/2026: el contador solo ha dado dos pasos y se para, y la cámara sigue diciendo «graba»
        var ticks = 0
        ticks = steadyTicksAfter(ticks, null)
        ticks = steadyTicksAfter(ticks, 1_000)
        assertFalse(clockIsRealTime(ticks))
        assertTrue(clockTrusted(normalVideo = true, steadyTicks = ticks))
        assertTrue(cameraStalled(recording = true, lastStatusAt = 9_900, clockAdvancedAt = 2_000.takeIf { clockTrusted(true, ticks) }?.toLong(), now = 10_000))
        // En otro modo, con dos pasos aún no se juzga
        assertFalse(clockTrusted(normalVideo = false, steadyTicks = ticks))
    }

    // --- Ancho de los textos

    @Test
    fun `un contador mide menos que el mismo numero de letras`() {
        assertTrue(textEm("1:02:45") < 3.6f)
        assertTrue(textEm("03:12") < 2.7f)
        // Nunca por debajo de lo que mide de verdad en Roboto negrita (cifras 0,57; dos puntos 0,27)
        assertTrue(textEm("1:02:45") >= 5 * 0.57f + 2 * 0.27f)
        assertTrue(textEm("REC") >= 1.84f)
        assertTrue(textEm("87 %") >= 2 * 0.57f + 0.25f + 0.74f)
    }

    // --- Modo de la cámara: el que tenga, solo vídeo, o vídeo y noche

    @Test
    fun `sin el ajuste no se toca ningun modo`() {
        for (mode in CameraMode.entries) assertFalse(shouldReturnToVideo(lock = 0, mode = mode, recording = false, busy = false, stalled = false))
    }

    @Test
    fun `solo video devuelve todo lo demas, tambien la noche`() {
        assertFalse(shouldReturnToVideo(lock = 1, mode = CameraMode.VIDEO, recording = false, busy = false, stalled = false))
        for (mode in CameraMode.entries - CameraMode.VIDEO) assertTrue(mode.name, shouldReturnToVideo(lock = 1, mode = mode, recording = false, busy = false, stalled = false))
    }

    @Test
    fun `video o noche respeta la noche`() {
        assertFalse(shouldReturnToVideo(lock = 2, mode = CameraMode.NIGHT, recording = false, busy = false, stalled = false))
        assertTrue(shouldReturnToVideo(lock = 2, mode = CameraMode.PHOTO, recording = false, busy = false, stalled = false))
        assertTrue(shouldReturnToVideo(lock = 2, mode = CameraMode.OTHER, recording = false, busy = false, stalled = false))
    }

    @Test
    fun `nunca se cambia de modo grabando ni en la galeria`() {
        assertFalse(shouldReturnToVideo(lock = 1, mode = CameraMode.TIMELAPSE, recording = true, busy = false, stalled = false))
        assertFalse(shouldReturnToVideo(lock = 1, mode = CameraMode.PHOTO, recording = false, busy = true, stalled = false))
        assertFalse(shouldReturnToVideo(lock = 1, mode = CameraMode.PHOTO, recording = false, busy = false, stalled = true))
        assertFalse(shouldReturnToVideo(lock = 1, mode = null, recording = false, busy = false, stalled = false))
    }
}
