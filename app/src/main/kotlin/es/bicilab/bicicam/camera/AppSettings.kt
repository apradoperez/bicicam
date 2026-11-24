package es.bicilab.bicicam.camera

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lo que el usuario elige en la pantalla de ajustes. Se guarda en el Karoo. */
object AppSettings {
    private const val PREFS = "settings"
    private lateinit var prefs: SharedPreferences

    private val _stopWithOneTap = MutableStateFlow(true)

    /** Parar la grabación con un solo toque en el campo; si no, hacen falta dos. */
    val stopWithOneTap: StateFlow<Boolean> = _stopWithOneTap.asStateFlow()

    private val _clipMinutes = MutableStateFlow(0)

    /** Minutos tras los que la grabación se para sola; 0: no se para. */
    val clipMinutes: StateFlow<Int> = _clipMinutes.asStateFlow()

    private val _alertBattery = MutableStateFlow(true)

    /** Avisar cuando a la cámara le queda poca batería. */
    val alertBattery: StateFlow<Boolean> = _alertBattery.asStateFlow()

    private val _alertCard = MutableStateFlow(true)

    /** Avisar cuando queda poco sitio en la tarjeta. */
    val alertCard: StateFlow<Boolean> = _alertCard.asStateFlow()

    private val _alertStopped = MutableStateFlow(true)

    /** Avisar cuando la cámara deja de grabar sin que se le haya pedido desde el Karoo. */
    val alertStopped: StateFlow<Boolean> = _alertStopped.asStateFlow()

    private val _gpsToCamera = MutableStateFlow(true)

    /** Pasarle a la cámara la posición y la velocidad del Karoo. */
    val gpsToCamera: StateFlow<Boolean> = _gpsToCamera.asStateFlow()

    private val _blinkPilot = MutableStateFlow(true)

    /** Mientras graba, el punto del campo de REC parpadea cada segundo, como el piloto de una cámara. */
    val blinkPilot: StateFlow<Boolean> = _blinkPilot.asStateFlow()

    private val _videoLock = MutableStateFlow(VIDEO_LOCK_OFF)

    /**
     * Qué modos se le dejan a la cámara: [VIDEO_LOCK_OFF] todos, [VIDEO_LOCK_VIDEO] solo vídeo, o
     * [VIDEO_LOCK_VIDEO_NIGHT] vídeo y vídeo nocturno. Si se pone en otro sin estar grabando, se la devuelve a vídeo.
     */
    val videoLock: StateFlow<Int> = _videoLock.asStateFlow()

    const val VIDEO_LOCK_OFF = 0
    const val VIDEO_LOCK_VIDEO = 1
    const val VIDEO_LOCK_VIDEO_NIGHT = 2

    private val _batterySaver = MutableStateFlow(false)

    /** Ahorro de batería de la cámara: el GPS solo se le manda mientras graba, no también en espera. */
    val batterySaver: StateFlow<Boolean> = _batterySaver.asStateFlow()

    private val _language = MutableStateFlow(Language.ofDevice())

    /** Idioma de la app. Mientras no se elija, el del Karoo. */
    val language: StateFlow<Language> = _language.asStateFlow()

    private val _fitMarks = MutableStateFlow(false)

    /** Apuntar en el archivo de la ruta (FIT) cuándo graba la cámara y cada marcador. */
    val fitMarks: StateFlow<Boolean> = _fitMarks.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _stopWithOneTap.value = prefs.getBoolean("stop_one_tap", true)
        _clipMinutes.value = prefs.getInt("clip_minutes", 0)
        _alertBattery.value = prefs.getBoolean("alert_battery", true)
        _alertCard.value = prefs.getBoolean("alert_card", true)
        _alertStopped.value = prefs.getBoolean("alert_stopped", true)
        _gpsToCamera.value = prefs.getBoolean("gps_to_camera", true)
        _fitMarks.value = prefs.getBoolean("fit_marks", false)
        _blinkPilot.value = prefs.getBoolean("blink_pilot", true)
        // Quien tenía activado el «Siempre en vídeo» de las versiones 35 a 37 se queda como estaba: vídeo y noche
        _videoLock.value = prefs.getInt("video_lock", if (prefs.getBoolean("keep_video_mode", false)) VIDEO_LOCK_VIDEO_NIGHT else VIDEO_LOCK_OFF)
        _batterySaver.value = prefs.getBoolean("battery_saver", false)
        _language.value = Language.fromCode(prefs.getString("language", null)) ?: Language.ofDevice()
    }

    fun setStopWithOneTap(on: Boolean) = save("stop_one_tap", on, _stopWithOneTap)
    fun setAlertBattery(on: Boolean) = save("alert_battery", on, _alertBattery)
    fun setAlertCard(on: Boolean) = save("alert_card", on, _alertCard)
    fun setAlertStopped(on: Boolean) = save("alert_stopped", on, _alertStopped)
    fun setGpsToCamera(on: Boolean) = save("gps_to_camera", on, _gpsToCamera)
    fun setFitMarks(on: Boolean) = save("fit_marks", on, _fitMarks)
    fun setBlinkPilot(on: Boolean) = save("blink_pilot", on, _blinkPilot)
    fun setBatterySaver(on: Boolean) = save("battery_saver", on, _batterySaver)
    fun setVideoLock(lock: Int) {
        prefs.edit().putInt("video_lock", lock).apply()
        _videoLock.value = lock
    }

    fun setLanguage(language: Language) {
        // En las pruebas no hay almacenamiento: el idioma se cambia igual
        if (::prefs.isInitialized) prefs.edit().putString("language", language.code).apply()
        _language.value = language
    }

    fun setClipMinutes(minutes: Int) {
        prefs.edit().putInt("clip_minutes", minutes).apply()
        _clipMinutes.value = minutes
    }

    private fun save(key: String, on: Boolean, flow: MutableStateFlow<Boolean>) {
        prefs.edit().putBoolean(key, on).apply()
        flow.value = on
    }
}
