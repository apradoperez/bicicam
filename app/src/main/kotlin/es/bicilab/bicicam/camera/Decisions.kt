package es.bicilab.bicicam.camera

/**
 * Las decisiones que no pueden fallar, sacadas a funciones sin Android ni corrutinas para poder probarlas:
 * qué quiere quien toca el campo, cuándo se para sola una grabación y cuándo se da a la cámara por colgada.
 */

/** Lo que pide un toque en el campo de grabar. */
enum class RecordIntent { START, STOP }

/**
 * Qué quiere quien toca, decidido con lo que el campo enseñaba EN EL MOMENTO del toque, no con lo que diga
 * la cámara cuando la orden llegue a salir. Solo se para si el campo decía que grababa. Un toque hecho sin
 * conexión (o sin saber aún si graba) es siempre «grabar»: si al reconectar resulta que la cámara ya graba,
 * no se le manda nada. Así un toque para «ver si vuelve» tras un corte nunca corta el clip.
 */
fun recordIntentAtTap(state: CameraState): RecordIntent {
    val connected = state as? CameraState.Connected
    return if (connected != null && connected.statusKnown && connected.recording && !connected.stalled) RecordIntent.STOP else RecordIntent.START
}

/** Qué se hace con la intención cuando por fin hay cámara y se sabe si graba. */
enum class RecordOrder { START, STOP, ALREADY_RECORDING, ALREADY_STOPPED }

fun recordOrder(intent: RecordIntent, recordingNow: Boolean): RecordOrder = when (intent) {
    RecordIntent.START -> if (recordingNow) RecordOrder.ALREADY_RECORDING else RecordOrder.START
    RecordIntent.STOP -> if (recordingNow) RecordOrder.STOP else RecordOrder.ALREADY_STOPPED
}

/** Tras cambiar el ajuste con una grabación en marcha, la parada automática no llega antes de este tiempo. */
const val AUTO_STOP_GRACE_MS = 30_000L

/**
 * Milisegundos que faltan para que la grabación se pare sola; null si no hay límite.
 *
 * El límite cuenta desde que empezó el clip, salvo que el ajuste se haya tocado con la grabación ya en
 * marcha: entonces cuenta desde ese cambio. El ajuste se cambia tocándolo en ciclo (nunca, 3, 6, 9), y sin
 * esto pasar por «3 min» con cinco minutos grabados cortaba el clip en el acto.
 *
 * @param since cuándo empezó el clip
 * @param limitSetAt cuándo se cambió el ajuste por última vez; null si no se ha tocado durante este clip
 */
fun autoStopDelayMs(since: Long, limitSetAt: Long?, minutes: Int, now: Long): Long? {
    if (minutes <= 0) return null
    val from = if (limitSetAt != null && limitSetAt > since) limitSetAt else since
    val wait = from + minutes * 60_000L - now
    val floor = if (limitSetAt != null && limitSetAt > since) limitSetAt + AUTO_STOP_GRACE_MS - now else 0L
    return maxOf(wait, floor, 0L)
}

/** Grabando, la cámara manda su estado dos veces por segundo. Sin noticias este tiempo, algo va mal. */
const val STALL_SILENCE_MS = 5_000L

/** Grabando, el contador de la cámara avanza cada segundo. Parado este tiempo, la cámara está colgada. */
const val STALL_FROZEN_CLOCK_MS = 6_000L

/**
 * ¿Hay que dar a la cámara por colgada? Solo se mira mientras graba, que es cuando engaña: el campo sigue
 * rojo y el contador corre con el reloj del Karoo aunque la cámara ya no haga nada.
 *
 * @param lastStatusAt cuándo llegó el último estado de la cámara
 * @param clockAdvancedAt cuándo avanzó por última vez el contador de la cámara; null si esta cámara no lo manda
 */
fun cameraStalled(recording: Boolean, lastStatusAt: Long, clockAdvancedAt: Long?, now: Long): Boolean {
    if (!recording) return false
    if (now - lastStatusAt > STALL_SILENCE_MS) return true
    return clockAdvancedAt != null && now - clockAdvancedAt > STALL_FROZEN_CLOCK_MS
}

/** Reconexiones forzadas por clip como mucho: insistir más es darle tráfico a una cámara enferma. */
const val MAX_STALL_RECONNECTS = 2

/**
 * El contador de la cámara solo vale de vigía si va a tiempo real. En timelapse, hyperlapse o cámara lenta
 * puede avanzar más despacio que el reloj, y un contador «parado» sería normal. Se da por bueno cuando ha
 * avanzado [STEADY_TICKS] veces seguidas con menos de [STEADY_TICK_MS] entre una y otra.
 * @param steadyTicks avances seguidos a ritmo de un segundo que lleva el contador en este clip
 */
fun clockIsRealTime(steadyTicks: Int): Boolean = steadyTicks >= STEADY_TICKS

/**
 * ¿Se puede juzgar a la cámara por su contador? En vídeo normal, desde el primer avance: el bloqueo del
 * 14/11/2026 llegó a los 2 o 3 segundos de empezar a grabar, antes de que el contador diera tres pasos. En los
 * demás modos, solo cuando ha demostrado ir a tiempo real.
 */
fun clockTrusted(normalVideo: Boolean, steadyTicks: Int): Boolean = normalVideo || clockIsRealTime(steadyTicks)

const val STEADY_TICKS = 3
const val STEADY_TICK_MS = 1_600L

/** Los avances seguidos a buen ritmo tras este avance: uno más si llegó a tiempo, cero si tardó. */
fun steadyTicksAfter(previous: Int, sinceLastAdvanceMs: Long?): Int =
    if (sinceLastAdvanceMs != null && sinceLastAdvanceMs <= STEADY_TICK_MS) previous + 1 else 0

/**
 * ¿Hay que devolver la cámara a vídeo? Depende de lo elegido en los ajustes: nada, solo vídeo, o vídeo y
 * vídeo nocturno (por si se graba de noche a propósito). Nunca mientras graba ni en la galería: un timelapse
 * en marcha no se corta.
 * @param lock 0: no se toca; 1: solo vídeo; 2: vídeo o noche
 */
fun shouldReturnToVideo(lock: Int, mode: CameraMode?, recording: Boolean, busy: Boolean, stalled: Boolean): Boolean {
    if (lock == 0 || recording || busy || stalled || mode == null) return false
    return when (mode) {
        CameraMode.VIDEO -> false
        CameraMode.NIGHT -> lock == 1
        else -> true
    }
}

/** Tras mandar grabar o parar, un estado que diga lo contrario durante este tiempo es de antes de la orden. */
const val STALE_STATUS_MS = 1_500L

/** ¿Este estado de «graba / no graba» es viejo, de antes de la orden que se acaba de mandar? */
fun isStaleRecordingStatus(reported: Boolean, ordered: Boolean?, orderedAt: Long, now: Long): Boolean =
    ordered != null && reported != ordered && now - orderedAt < STALE_STATUS_MS
