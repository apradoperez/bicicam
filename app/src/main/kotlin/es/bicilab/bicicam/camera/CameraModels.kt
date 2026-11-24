package es.bicilab.bicicam.camera

import android.os.SystemClock

/**
 * Marcas que se pueden emparejar en esta versión. Insta360 y GoPro tienen su código en el proyecto pero
 * están apagadas: con una Ace Pro 2 (13/11/2026) el Karoo graba, para y apaga, pero la cámara no vuelve a
 * conectar tras un corte (reconoce al mando por su dirección Bluetooth y Android la cambia); GoPro está
 * sin probar con ninguna cámara. Se vuelven a encender añadiéndolas aquí.
 */
val ENABLED_BRANDS: Set<Brand> = setOf(Brand.DJI)

/** Familias de cámara con las que BiciCAM sabe hablar. */
enum class Brand(val label: String) {
    /** Osmo Action 4, 5 Pro y 6 y Osmo 360: el Karoo se conecta a la cámara (protocolo DJI R SDK). */
    DJI("DJI Osmo Action"),

    /** Ace Pro 2 y demás cámaras compatibles con el mando GPS: el Karoo hace de mando y la cámara se conecta a él. */
    INSTA360("Insta360"),

    /** HERO9 Black en adelante (Open GoPro): el Karoo se vincula con la cámara y se conecta a ella. */
    GOPRO("GoPro"),
}

/**
 * La cámara emparejada.
 * @param address dirección Bluetooth de la cámara
 * @param advertName nombre con el que se anunciaba al emparejarla (DJI): sirve para reconocerla si cambia de dirección
 * @param modelId modelo que dijo ser al emparejarla (DJI): una cámara que diga otro no es ella
 */
data class PairedCamera(
    val brand: Brand,
    val address: String,
    val name: String,
    val advertName: String? = null,
    val modelId: Int? = null,
)

/**
 * En qué está puesta la cámara. Lo que no es vídeo importa: en foto, un toque en REC hace una foto, y en
 * timelapse o cámara lenta sale otra cosa que la que se espera. Los campos lo dicen de un vistazo.
 */
enum class CameraMode {
    /** Vídeo normal. */
    VIDEO,

    /** Vídeo nocturno: también es vídeo, y puede ser a propósito. El campo lo dice, sin alarma. */
    NIGHT,
    PHOTO,
    TIMELAPSE,
    SLOW_MOTION,
    HYPERLAPSE,

    /** Un modo que BiciCAM no conoce: se avisa, pero no se toca. */
    OTHER,
}

data class FoundCamera(val address: String, val name: String)

sealed interface CameraState {
    /** Aún no se ha emparejado ninguna cámara en la app. */
    data object NotPaired : CameraState

    /** Falta el permiso de Bluetooth (se da abriendo la app). */
    data object NoPermission : CameraState

    /** Emparejada, pero nadie la necesita ahora mismo: sin conexión. */
    data object Standby : CameraState

    /** Buscando la cámara; puede estar apagada o lejos. */
    data object Searching : CameraState

    /** La cámara ha contestado (hay enlace Bluetooth) pero aún no acepta órdenes. */
    data object Starting : CameraState

    /**
     * La cámara está dormida y el Karoo no puede despertarla: acepta la conexión Bluetooth pero no contesta
     * a nada. Hay que pulsar su botón; BiciCAM sigue probando y conecta sola en cuanto despierta.
     */
    data object Asleep : CameraState

    /**
     * Apagada desde BiciCAM (o dormida por su cuenta): no se intenta conectar hasta que se pida encenderla.
     * @param canWake la cámara puede encenderse desde el Karoo
     */
    data class Off(val canWake: Boolean) : CameraState

    data class Connected(
        /** La cámara ya ha dicho si graba o no: hasta entonces no se le manda grabar ni parar. */
        val statusKnown: Boolean = false,
        val recording: Boolean = false,
        /** [SystemClock.elapsedRealtime] del momento en que empezó la grabación en curso. */
        val recordingSince: Long? = null,
        val batteryPercent: Int? = null,
        val busy: Boolean = false,
        /** Autonomía que anuncia la cámara como texto (Insta360, por ejemplo "1h09m"), si la da. */
        val runtime: String? = null,
        /** La pantalla de la cámara está apagada mientras espera (DJI lo dice; null si no se sabe). */
        val screenOff: Boolean? = null,
        /** Segundos de grabación que caben aún en la tarjeta, si la cámara lo dice. */
        val remainingSeconds: Int? = null,
        /** Modo y calidad en corto, por ejemplo "4K 60" o "Foto". */
        val format: String? = null,
        /** Marcadores puestos desde el Karoo en la grabación en curso. */
        val markers: Int = 0,
        /** En qué modo está la cámara, si lo dice. */
        val mode: CameraMode? = null,
        /**
         * Grababa y ha dejado de dar señales con el enlace aún abierto: no se sabe si sigue grabando. Los
         * campos lo dicen en ámbar y el marco rojo se quita, en vez de seguir contando a ciegas.
         */
        val stalled: Boolean = false,
    ) : CameraState
}

/** Lo que el controlador de una marca cuenta de la cámara; los campos nulos no cambian. */
data class StatusUpdate(
    val recording: Boolean? = null,
    /** Segundos que lleva la grabación en curso. */
    val recordingSeconds: Int? = null,
    val batteryPercent: Int? = null,
    val busy: Boolean? = null,
    val runtime: String? = null,
    /** La cámara avisa de que se ha dormido. */
    val asleep: Boolean? = null,
    /** En espera, con la pantalla apagada (true) o encendida (false). */
    val screenOff: Boolean? = null,
    val remainingSeconds: Int? = null,
    val format: String? = null,
    val mode: CameraMode? = null,
    /** Graba vídeo normal, a tiempo real: su contador avanza un segundo cada segundo (no timelapse ni cámara lenta). */
    val realTimeVideo: Boolean? = null,
)

/** Lo que una sesión con la cámara le va contando a [CameraController]. */
interface SessionHost {
    /** Hay enlace Bluetooth: la cámara ha contestado. */
    fun onLinkUp()

    /** La cámara acepta órdenes. */
    fun onReady()

    fun onStatus(update: StatusUpdate)

    /** Línea para el registro de eventos. */
    fun log(message: String)
}

/**
 * Un fallo con arreglo conocido. Lleva lo que se le enseña a quien va en la bici: qué pasa ([title], corto,
 * porque el Karoo corta los títulos hacia las 15 letras) y qué hacer ([advice]). Los detalles técnicos van
 * al registro de eventos, no a la pantalla.
 */
class CameraProblem(val title: String, val advice: String) : Exception("$title. $advice")

/**
 * El Karoo tiene apagado el Bluetooth para las extensiones. Pasa en plena ruta (visto en un Karoo, a los
 * pocos segundos de parar una grabación): hay que volver a pedírselo, no esperar a que vuelva solo.
 */
class BluetoothOffException : java.io.IOException(t("El Bluetooth está apagado", "Bluetooth is off"))

/** La cámara no ha contestado en el tiempo que duraba el intento. */
class CameraNotAnsweringException : Exception()

/** La cámara acepta la conexión pero no contesta a ninguna orden: está dormida. */
class CameraAsleepException : Exception(t("la cámara está dormida", "the camera is asleep"))

/** El protocolo de una marca. Una instancia por cámara emparejada. */
interface CameraDriver {
    /** La cámara puede encenderse desde el Karoo. */
    val canWake: Boolean

    /** La cámara dice ella misma si graba; si no, BiciCAM solo sabe lo que le ha pedido. */
    val confirmsRecording: Boolean get() = true

    /**
     * Un intento de conexión y, si sale bien, la sesión entera: no vuelve hasta que se pierde el enlace.
     * @param quick intento corto (unos segundos); si no, uno largo y paciente
     * @param calm lleva muchos intentos fallidos: se busca gastando menos batería
     * @throws CameraNotAnsweringException si la cámara no contesta
     */
    suspend fun runSession(host: SessionHost, quick: Boolean, calm: Boolean = false)

    suspend fun setRecording(on: Boolean)

    /** Duerme o apaga la cámara. */
    suspend fun sleep()

    /**
     * Deja una marca en el vídeo que se está grabando, si la cámara sabe hacerlo.
     * @return false si esta cámara no admite marcas desde el mando (el marcador queda solo en el Karoo)
     */
    suspend fun mark(): Boolean = false

    /**
     * Para las cámaras que el Karoo no puede encender: mira si la han encendido a mano.
     * @param calm mirar gastando menos batería (lleva rato apagada)
     * @return true si la cámara contesta a órdenes
     */
    suspend fun probeAwake(calm: Boolean = false, log: (String) -> Unit): Boolean = false

    /**
     * Pone la cámara en modo vídeo.
     * @return false si esta cámara no sabe cambiar de modo desde el mando
     */
    suspend fun setVideoMode(): Boolean = false

    /** Ya no se busca la cámara (nadie la necesita, está apagada o se ha olvidado): se recoge lo que quede. */
    fun onIdle() {}
}
