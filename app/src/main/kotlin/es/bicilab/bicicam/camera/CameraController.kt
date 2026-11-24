package es.bicilab.bicicam.camera

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import es.bicilab.bicicam.dji.DjiDriver
import es.bicilab.bicicam.gopro.GoProDriver
import es.bicilab.bicicam.dji.DjiRejectedException
import es.bicilab.bicicam.insta360.InstaDriver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Dueño de la conexión con la cámara emparejada, sea de la marca que sea.
 *
 * La cámara solo se mantiene conectada mientras alguien la necesita y lo dice con [acquire]: un campo a la
 * vista, la app abierta o una pulsación pendiente. Cuando el último la suelta, la conexión aguanta [LINGER]
 * para que cambiar de página no obligue a reconectar.
 *
 * La lógica viene de Kamerad (Horst Tellioglu, licencia MIT); lo propio de cada marca está en su
 * [CameraDriver].
 */
object CameraController {
    private const val TAG = "BiciCAM"
    private const val PREFS = "camera"
    private const val KEY_BRAND = "brand"
    private const val KEY_ADDRESS = "address"
    private const val KEY_NAME = "name"
    private const val KEY_ADVERT_NAME = "advert_name"
    private const val KEY_MODEL_ID = "model_id"
    private const val KEY_SERIAL = "serial"
    private const val KEY_SWITCHED_OFF = "switched_off"
    private const val KEY_EMPTY_PERCENT = "empty_percent"
    private const val KEY_EMPTY_AT = "empty_at"
    private const val KEY_LAST_BATTERY = "last_battery"
    private const val KEY_IDLE_MINUTES = "idle_minutes"

    /** Por defecto no se apaga sola: con DJI, volver a encenderla desde el Karoo no está garantizado. */
    private const val DEFAULT_IDLE_MINUTES = 0

    /** Una cámara que desaparece con la batería en este nivel o menos probablemente se ha quedado sin ella. */
    private const val EMPTY_BATTERY_THRESHOLD = 10
    private val LINGER = 2.minutes
    private val ADVERTISING_LISTEN = 10.seconds
    private val RECORDING_SEARCH = 5.minutes
    private val WAKE_WATCH_INTERVAL = 15.seconds

    /** Un toque hecho sin conexión deja de valer pasado este tiempo: nadie espera que grabe un minuto después. */
    private const val STALE_TAP_MS = 10_000L

    /** Lo que se espera a que la cámara confirme que graba o que ha parado. */
    private val CONFIRM_RECORDING = 5.seconds

    /** Colgada este tiempo, se corta el enlace y se vuelve a conectar. */
    private const val STALL_RECONNECT_MS = 10_000L

    /** Tras estos intentos fallidos seguidos, se busca con calma para no gastar la batería del Karoo. */
    private const val CALM_AFTER_FAILURES = 10
    private val CALM_PAUSE = 20.seconds

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + crashGuard)
    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences

    private val _pairedCamera = MutableStateFlow<PairedCamera?>(null)
    val pairedCamera: StateFlow<PairedCamera?> = _pairedCamera.asStateFlow()

    /**
     * La cámara desapareció del Bluetooth (sin que la apagara BiciCAM) con poca batería: probablemente se ha
     * quedado sin ella. La cámara no puede avisar de eso, así que es una suposición a partir del último nivel.
     */
    data class ProbablyEmpty(val percent: Int, val atMillis: Long)

    private val _probablyEmpty = MutableStateFlow<ProbablyEmpty?>(null)
    val probablyEmpty: StateFlow<ProbablyEmpty?> = _probablyEmpty.asStateFlow()

    @Volatile
    private var lastBattery: Int? = null

    @Volatile
    private var sleepRequestedAt = 0L

    /** Cuándo se pidió parar desde el Karoo: una parada poco después no es "la cámara ha parado sola". */
    @Volatile
    private var stopRequestedAt = 0L

    /** Último umbral de batería del que ya se avisó (20 o 10); 0: ninguno. */
    private var batteryAlertLevel = 0
    private var cardAlerted = false

    @Volatile
    private var sessionReady = false

    /** El último intento encontró a la cámara dormida: mientras siga así, los campos lo dicen. */
    @Volatile
    private var foundAsleep = false
    private var connectLogged = false
    private var linkUpAt = 0L

    private var lastLoggedBattery: Int? = null
    private var lastReportedRecording: Boolean? = null

    private val _state = MutableStateFlow<CameraState>(CameraState.NotPaired)
    val state: StateFlow<CameraState> = _state.asStateFlow()

    /** Avisos para quien va en la bici (se enseñan como alertas durante la ruta). */
    private val _notices = MutableSharedFlow<Notice>(extraBufferCapacity = 8)
    val notices: SharedFlow<Notice> = _notices.asSharedFlow()

    /** El [title] corto lleva el mensaje; [detail] es una segunda línea opcional. */
    data class Notice(val title: String, val detail: String? = null, val isError: Boolean)

    /** Apagada desde BiciCAM: no se conecta hasta que se pida encenderla. */
    private val switchedOff = MutableStateFlow(false)

    /** Sube con cada toque que pide la cámara: la búsqueda en curso se corta y empieza otra, sin esperas. */
    private val wakeRequests = MutableStateFlow(0)

    /** Minutos sin grabar tras los que se apaga la cámara; 0: nunca. */
    private val _idleMinutes = MutableStateFlow(DEFAULT_IDLE_MINUTES)
    val idleMinutes: StateFlow<Int> = _idleMinutes.asStateFlow()

    /** Cuenta lo que hace el usuario con la cámara, para que el reloj de inactividad vuelva a empezar. */
    private val activity = MutableStateFlow(0)

    /** La cámara ha grabado desde que se encendió: solo entonces corre el reloj de inactividad. */
    @Volatile
    private var recordedSinceOn = false

    /** La conexión está retenida porque la cámara graba. Fuera del bucle, para no perder la cuenta si este se reinicia. */
    @Volatile
    private var held = false

    /** Una orden pedida no ha llegado a hacerse: quien enseñe «Parando» o «Apagando» tiene que dejar de hacerlo. */
    private val _actionFailures = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val actionFailures: SharedFlow<Unit> = _actionFailures.asSharedFlow()

    /** Lo pone la extensión: vuelve a pedirle al Karoo el Bluetooth cuando BiciCAM se lo encuentra apagado. */
    @Volatile
    var onBluetoothOff: (() -> Unit)? = null

    @Volatile
    private var bluetoothOffLogged = false

    private var bluetoothOffTries = 0

    /** Para el registro: algo que le pasa a la extensión por fuera (el Karoo la arranca, la para, se desconecta). */
    fun note(message: String) = event(message)

    private val users = MutableStateFlow(0)
    private val actionRunning = AtomicBoolean(false)

    @Volatile
    private var driver: CameraDriver? = null

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val brand = prefs.getString(KEY_BRAND, null)?.let { saved -> Brand.entries.firstOrNull { it.name == saved }?.takeIf { it in ENABLED_BRANDS } }
        val address = prefs.getString(KEY_ADDRESS, null)
        val camera = if (brand != null && address != null) {
            PairedCamera(
                brand, address, prefs.getString(KEY_NAME, null) ?: address,
                advertName = prefs.getString(KEY_ADVERT_NAME, null),
                modelId = prefs.getInt(KEY_MODEL_ID, 0).takeIf { it != 0 },
            )
        } else {
            null
        }
        driver = camera?.let(::driverFor)
        _pairedCamera.value = camera
        switchedOff.value = prefs.getBoolean(KEY_SWITCHED_OFF, false)
        _idleMinutes.value = prefs.getInt(KEY_IDLE_MINUTES, DEFAULT_IDLE_MINUTES)
        if (prefs.contains(KEY_LAST_BATTERY)) lastBattery = prefs.getInt(KEY_LAST_BATTERY, 0)
        if (prefs.contains(KEY_EMPTY_PERCENT)) {
            _probablyEmpty.value = ProbablyEmpty(prefs.getInt(KEY_EMPTY_PERCENT, 0), prefs.getLong(KEY_EMPTY_AT, 0))
        }
        scope.launchForever { manageConnection() }
        scope.launchForever { watchIdle() }
        scope.launchForever { holdWhileRecording() }
        scope.launchForever { watchClipLimit() }
        scope.launchForever { watchStall() }
        scope.launchForever { watchMode() }
    }

    private fun driverFor(camera: PairedCamera): CameraDriver = when (camera.brand) {
        Brand.DJI -> DjiDriver(appContext, camera)
        Brand.GOPRO -> GoProDriver(appContext, camera)
        Brand.INSTA360 -> InstaDriver(appContext, camera, prefs.getString(KEY_SERIAL, null)) { serial ->
            // Solo se guarda el primero: uno distinto después no es esta cámara
            if (!prefs.contains(KEY_SERIAL)) prefs.edit().putString(KEY_SERIAL, serial).apply()
        }
    }

    fun setIdleMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_IDLE_MINUTES, minutes).apply()
        _idleMinutes.value = minutes
    }

    private enum class Activity { NOT_CONNECTED, RECORDING, BUSY, IDLE }

    /**
     * Apaga la cámara cuando lleva [idleMinutes] conectada sin grabar, para que no se quede encendida
     * gastando batería después de una grabación.
     */
    private suspend fun watchIdle() {
        val camera = _state
            .map { s ->
                when {
                    s !is CameraState.Connected -> Activity.NOT_CONNECTED
                    s.recording -> Activity.RECORDING
                    s.busy -> Activity.BUSY
                    else -> Activity.IDLE
                }
            }
            .distinctUntilChanged()
        combine(camera, _idleMinutes, activity) { c, minutes, _ -> c to minutes }.collectLatest { (c, minutes) ->
            if (c == Activity.RECORDING) recordedSinceOn = true
            if (c == Activity.IDLE && recordedSinceOn && minutes > 0) {
                delay(minutes.minutes)
                event(t("$minutes min sin grabar: se apaga la cámara", "Idle for $minutes min: switching the camera off"))
                switchOff(announce = true, detail = t("$minutes min sin grabar", "$minutes min without recording"))
            }
        }
    }

    /**
     * Mientras la cámara graba no se suelta la conexión, aunque no haya ningun campo a la vista: así el
     * marco rojo y los campos saben en todo momento si sigue grabando. Si la cámara desaparece grabando, se
     * la sigue buscando [RECORDING_SEARCH] y después se deja.
     */
    private suspend fun holdWhileRecording() {
        // Cuándo se perdió la cámara: el plazo cuenta desde ahí, no desde el último cambio de estado, o con la
        // cámara alternando «Arrancando» y «Esperando» no se dejaría de buscar nunca
        var lostAt = 0L
        state.collectLatest { s ->
            when {
                s is CameraState.Connected && s.recording -> {
                    lostAt = 0L
                    if (!held) {
                        held = true
                        acquire()
                    }
                }
                s is CameraState.Connected || s is CameraState.Off || s is CameraState.NotPaired -> {
                    lostAt = 0L
                    if (held) {
                        held = false
                        release()
                    }
                }
                held -> {
                    if (lostAt == 0L) lostAt = SystemClock.elapsedRealtime()
                    delay((lostAt + RECORDING_SEARCH.inWholeMilliseconds - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                    lostAt = 0L
                    event(t("Se perdió la cámara mientras grababa y no ha vuelto. BiciCAM deja de buscarla; pulsa su botón para reconectar.", "Lost the camera while recording and it hasn't come back. BiciCAM stopped looking; press its button to reconnect."))
                    held = false
                    release()
                }
            }
        }
    }

    fun acquire() = users.update { it + 1 }

    fun release() = users.update { maxOf(0, it - 1) }

    /**
     * Guarda la cámara emparejada (o la olvida con null).
     * @param serial solo Insta360: su número de serie, que es lo que la despierta
     */
    fun setPairedCamera(camera: PairedCamera?, serial: String? = null) {
        event(if (camera == null) t("Cámara olvidada", "Camera removed") else t("Emparejada con ${camera.name}", "Paired with ${camera.name}"))
        prefs.edit().apply {
            if (camera == null) {
                remove(KEY_BRAND)
                remove(KEY_ADDRESS)
                remove(KEY_NAME)
                remove(KEY_ADVERT_NAME)
                remove(KEY_MODEL_ID)
            } else {
                putString(KEY_BRAND, camera.brand.name)
                putString(KEY_ADDRESS, camera.address)
                putString(KEY_NAME, camera.name)
                if (camera.advertName != null) putString(KEY_ADVERT_NAME, camera.advertName) else remove(KEY_ADVERT_NAME)
                if (camera.modelId != null) putInt(KEY_MODEL_ID, camera.modelId) else remove(KEY_MODEL_ID)
            }
            if (serial != null) putString(KEY_SERIAL, serial) else remove(KEY_SERIAL)
            remove(KEY_EMPTY_PERCENT)
            remove(KEY_EMPTY_AT)
            remove(KEY_LAST_BATTERY)
        }.apply()
        // Lo aprendido de la cámara anterior no vale para la nueva
        appContext.getSharedPreferences("insta360", Context.MODE_PRIVATE).edit().remove("seen_timer").apply()
        _probablyEmpty.value = null
        lastBattery = null
        setSwitchedOff(false)
        driver?.onIdle()
        driver = camera?.let(::driverFor)
        _pairedCamera.value = camera
    }

    private fun setSwitchedOff(off: Boolean) {
        if (off) recordedSinceOn = false
        prefs.edit().putBoolean(KEY_SWITCHED_OFF, off).apply()
        switchedOff.value = off
    }

    /**
     * Empieza o para la grabación, encendiendo y conectando la cámara antes si hace falta. Vuelve enseguida.
     * @param announce avisar también cuando sale bien (no solo de los errores)
     */
    fun toggleRecording(announce: Boolean): Boolean {
        // Lo que quiere quien toca se decide ahora, con lo que el campo enseña, no cuando la orden llegue a
        // salir: un toque hecho sin conexión nunca para una grabación (ver [recordIntentAtTap])
        val intent = recordIntentAtTap(_state.value)
        return runAction(t("Grabar o parar", "Record or stop"), needsStatus = true, expires = true) { connected ->
            if (connected.stalled) {
                // No se sabe si graba: ni se para ni se manda grabar encima; se dice lo que hay
                _notices.emit(Notice(t("Sin señal", "No signal"), t("Mira si la cámara graba", "Check the camera is recording"), isError = true))
                return@runAction
            }
            when (recordOrder(intent, connected.recording)) {
                RecordOrder.ALREADY_RECORDING -> {
                    event(t("Toque de grabar hecho sin conexión: la cámara ya grababa y no se le manda nada", "Record tap made while disconnected: the camera was already recording; nothing sent"))
                    _notices.emit(Notice(t("Ya grababa", "Already recording"), t("Sigue grabando", "Still recording"), isError = false))
                }
                RecordOrder.ALREADY_STOPPED -> {
                    event(t("Toque de parar: la cámara ya no grababa y no se le manda nada", "Stop tap: the camera was no longer recording; nothing sent"))
                    _notices.emit(Notice(t("Ya no grababa", "Already stopped"), isError = false))
                }
                RecordOrder.START, RecordOrder.STOP -> {
                    val start = intent == RecordIntent.START
                    if (start && connected.format?.startsWith(t("Foto", "Photo")) == true) {
                        event(t("Orden de grabar con la cámara en modo foto", "Record command with the camera in photo mode"))
                    }
                    setRecording(start)
                    if (driver?.confirmsRecording == false) {
                        // Esta cámara no dice si graba: se avisa siempre, también al tocar un campo, en vez de encender
                        // el marco rojo a ciegas y callar
                        _notices.emit(Notice(t("Sin confirmar", "Unconfirmed"), t("Mira si la cámara graba", "Check the camera is recording"), isError = false))
                    } else if (awaitRecordingConfirmed(start)) {
                        if (announce) _notices.emit(Notice(if (start) t("Grabando", "Recording") else t("REC parado", "REC stopped"), isError = false))
                    } else if (_state.value !is CameraState.Connected || !sessionReady) {
                        // El enlace se cayó mientras se esperaba: de eso ya avisa la conexión, y «No graba» engañaría
                        event(t("La conexión se perdió antes de que la cámara confirmara la orden", "The connection dropped before the camera confirmed the command"))
                    } else {
                        // La cámara aceptó la orden pero no ha hecho lo pedido (modo foto, tarjeta llena o mala...)
                        event(if (start) t("La cámara no ha confirmado que graba", "The camera did not confirm it is recording") else t("La cámara no ha confirmado que ha parado", "The camera did not confirm it stopped"))
                        _state.update { current ->
                            if (current is CameraState.Connected) current.copy(recording = !start, recordingSince = if (start) null else current.recordingSince ?: SystemClock.elapsedRealtime()) else current
                        }
                        _notices.emit(
                            if (start) Notice(t("No graba", "Not recording"), t("Mira la cámara y la tarjeta", "Check camera and card"), isError = true)
                            else Notice(t("Sigue grabando", "Still recording"), t("No ha parado. Toca otra vez", "It didn't stop. Tap again"), isError = true),
                        )
                    }
                }
            }
        }
    }

    /** La orden de grabar o parar que se acaba de mandar, para no hacer caso de un estado de antes de ella. */
    @Volatile
    private var orderedRecording: Boolean? = null

    @Volatile
    private var recordingOrderedAt = 0L

    /** Lo último que la cámara ha dicho de sí misma: graba o no. */
    private val reportedRecording = MutableStateFlow<Boolean?>(null)

    /** Espera a que la cámara diga que hace lo que se le ha pedido. */
    private suspend fun awaitRecordingConfirmed(on: Boolean): Boolean =
        withTimeoutOrNull(CONFIRM_RECORDING) { reportedRecording.first { it == on } } != null

    /** Marcadores puestos desde que arrancó la app; va al archivo de la ruta para poder contarlos después. */
    @Volatile
    var markerTotal = 0
        private set

    /**
     * Deja un marcador en el momento actual de la grabación: en la cámara si sabe (DJI) y siempre en el
     * registro propio de BiciCAM. Vuelve enseguida.
     */
    fun addMarker() {
        scope.launch {
            val connected = _state.value as? CameraState.Connected
            val since = connected?.recordingSince
            if (connected == null || !connected.recording || since == null) {
                _notices.emit(Notice(t("Sin marcador", "No marker"), t("Solo mientras graba", "Only while recording"), isError = true))
                return@launch
            }
            val seconds = ((SystemClock.elapsedRealtime() - since) / 1000).coerceAtLeast(0)
            val inCamera = try {
                driver?.mark() ?: false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                event(t("No se ha podido poner el marcador (${e.message})", "Couldn't add the marker (${e.message})"))
                val advice = (e as? CameraProblem)?.advice ?: t("No ha llegado. Prueba otra vez", "It didn't arrive. Try again")
                _notices.emit(Notice(t("Sin marcador", "No marker"), advice, isError = true))
                return@launch
            }
            markerTotal++
            // La posición solo se apunta si el usuario ha dejado activado pasar el GPS
            val fix = if (AppSettings.gpsToCamera.value) LocationFeed.current(10_000) else null
            MarkerLog.add(seconds, fix?.latitude, fix?.longitude)
            val at = "%02d:%02d".format(seconds / 60, seconds % 60)
            event(t("Marcador en el $at del clip", "Marker at $at") + if (inCamera) "" else t(" (solo en el Karoo: esta cámara no admite marcadores)", " (saved on the Karoo only: this camera doesn't support markers)"))
            _state.update { if (it is CameraState.Connected) it.copy(markers = it.markers + 1) else it }
            _notices.emit(Notice(t("Marcador", "Marker"), t("En el $at del clip", "At $at in the clip"), isError = false))
        }
    }

    /** Para la grabación si está grabando. Vuelve enseguida. */
    private fun stopRecording(detail: String) = runAction(t("Parar", "Stop"), wake = false, quietIfBusy = true) {
        // Si ya se le acaba de mandar parar, no se repite: la cámara rechaza la segunda orden y saldría un aviso de fallo
        val justAsked = SystemClock.elapsedRealtime() - stopRequestedAt < 5_000
        if (!justAsked && (_state.value as? CameraState.Connected)?.recording == true) {
            setRecording(false)
            _notices.emit(Notice(t("REC parado", "REC stopped"), detail, isError = false))
        }
    }

    /** Para sola la grabación cuando lleva los minutos elegidos en los ajustes. */
    private suspend fun watchClipLimit() {
        val since = _state.map { (it as? CameraState.Connected)?.takeIf { c -> c.recording }?.recordingSince }.distinctUntilChanged()
        var lastMinutes: Int? = null
        var limitSetAt: Long? = null
        combine(since, AppSettings.clipMinutes) { s, minutes -> s to minutes }.collectLatest { (s, minutes) ->
            // Tocar el ajuste con una grabación en marcha no puede cortarla: el límite cuenta desde el cambio
            if (lastMinutes != null && lastMinutes != minutes) limitSetAt = SystemClock.elapsedRealtime()
            lastMinutes = minutes
            if (s == null) return@collectLatest
            val wait = autoStopDelayMs(s, limitSetAt, minutes, SystemClock.elapsedRealtime()) ?: return@collectLatest
            delay(wait)
            event(t("$minutes min de grabación: se para sola", "$minutes min recorded: auto-stop"))
            // Si justo hay otra orden en curso, se insiste durante un minuto
            repeat(15) {
                if ((_state.value as? CameraState.Connected)?.recording != true) return@collectLatest
                stopRecording(t("Clip de $minutes min", "$minutes min clip"))
                delay(4.seconds)
            }
            if ((_state.value as? CameraState.Connected)?.recording == true) {
                event(t("La parada automática no ha conseguido parar la grabación", "Auto-stop could not stop the recording"))
                _notices.emit(Notice(t("Sigue grabando", "Still recording"), t("No ha parado sola. Párala tú", "Auto-stop failed. Stop it"), isError = true))
            }
        }
    }

    /**
     * Con «Siempre en vídeo» activado, devuelve la cámara a vídeo cuando se la encuentra en foto, timelapse,
     * cámara lenta o hyperlapse sin grabar (por ejemplo, tras un toque sin querer en su botón de modo). Espera
     * un par de segundos por si alguien está pasando de un modo a otro, y no insiste más de tres veces.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun watchMode() {
        val wanted = combine(_state, AppSettings.videoLock) { s, keep ->
            val c = s as? CameraState.Connected
            (c?.mode).takeIf { c != null && shouldReturnToVideo(keep, c.mode, c.recording, c.busy, c.stalled) }
        }.distinctUntilChanged()
        wanted.collectLatest { mode ->
            if (mode == null) return@collectLatest
            delay(2.seconds)
            repeat(3) { attempt ->
                // Una orden de quien va en la bici pasa delante
                while (actionRunning.get()) delay(500)
                event(t("La cámara está en otro modo ($mode): se la devuelve a vídeo", "The camera is in another mode ($mode): switching it back to video"))
                try {
                    if (driver?.setVideoMode() != true) return@collectLatest
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    event(t("No se ha podido volver a vídeo (${e.message})", "Couldn't switch back to video (${e.message})"))
                }
                delay(3.seconds)
                if (attempt == 2) {
                    event(t("La cámara no vuelve al modo vídeo", "The camera will not go back to video mode"))
                    _notices.emit(Notice(t("No es vídeo", "Not video"), t("Cambia el modo en la cámara", "Change the mode on the camera"), isError = true))
                }
            }
        }
    }

    /** Cuándo llegó el último estado de la cámara, y cuándo avanzó su contador de grabación. */
    @Volatile
    private var lastStatusAt = 0L

    @Volatile
    private var lastClockSeconds: Int? = null

    @Volatile
    private var clockAdvancedAt: Long? = null

    /** La cámara dice que graba vídeo normal: su contador vale de vigía desde el primer segundo. */
    @Volatile
    private var normalVideo = false

    /** Avances seguidos del contador a ritmo de un segundo en este clip (ver [clockIsRealTime]). */
    @Volatile
    private var steadyTicks = 0

    /** Reconexiones forzadas en este clip por cámara colgada, y si ya se avisó: una vez basta. */
    @Volatile
    private var stallReconnects = 0

    @Volatile
    private var stallAlerted = false

    /** Huecos de estado de más de 1,5 s en esta sesión: es lo que faltó en el registro del 14/11/2026. */
    private var statusGaps = 0

    /**
     * Vigila que la cámara siga dando señales mientras graba. Si se cuelga con el enlace abierto, el campo
     * seguiría rojo y contando: aquí se pasa a «Sin respuesta», se quita el marco, se avisa y, si no vuelve,
     * se corta el enlace para reconectar.
     */
    private suspend fun watchStall() {
        var stalledSince = 0L
        while (true) {
            delay(1.seconds)
            val current = _state.value as? CameraState.Connected
            if (current == null || !sessionReady) {
                stalledSince = 0L
                continue
            }
            val now = SystemClock.elapsedRealtime()
            // Recién mandada una orden, la cámara puede callar un momento mientras cambia de estado
            val settling = now - recordingOrderedAt < CONFIRM_RECORDING.inWholeMilliseconds
            val stalled = !settling && cameraStalled(current.recording, lastStatusAt, clockAdvancedAt.takeIf { clockTrusted(normalVideo, steadyTicks) }, now)
            if (stalled && !current.stalled) {
                stalledSince = now
                val silence = (now - lastStatusAt) / 1000.0
                event(t("La cámara grababa y ha dejado de dar señales (último estado hace $silence s): no se sabe si sigue grabando", "The camera was recording and went silent (last status $silence s ago): unknown whether it is still recording"))
                _state.update { if (it is CameraState.Connected) it.copy(stalled = true) else it }
                if (!stallAlerted) {
                    stallAlerted = true
                    _notices.emit(Notice(t("Sin respuesta", "No response"), t("Mira si la cámara graba", "Check the camera is recording"), isError = true))
                }
            } else if (!stalled && current.stalled) {
                stalledSince = 0L
                event(t("La cámara vuelve a dar señales", "The camera is responding again"))
                _state.update { if (it is CameraState.Connected) it.copy(stalled = false) else it }
            } else if (stalled && stalledSince != 0L && now - stalledSince > STALL_RECONNECT_MS) {
                stalledSince = 0L
                if (stallReconnects >= MAX_STALL_RECONNECTS) {
                    // Insistir es darle más tráfico a una cámara enferma: se deja en «Sin señal» hasta que ella diga algo
                    if (stallReconnects == MAX_STALL_RECONNECTS) {
                        stallReconnects++
                        event(t("La cámara sigue sin responder tras $MAX_STALL_RECONNECTS reconexiones: no se insiste más en este clip", "Still no response after $MAX_STALL_RECONNECTS reconnections: not retrying again for this clip"))
                    }
                    continue
                }
                stallReconnects++
                event(t("La cámara sigue sin responder: se corta el enlace y se vuelve a conectar ($stallReconnects de $MAX_STALL_RECONNECTS)", "Still no response: dropping the link and reconnecting ($stallReconnects of $MAX_STALL_RECONNECTS)"))
                // Reinicia la conexión: la sesión en curso se cancela y cierra su enlace
                wakeRequests.update { it + 1 }
            }
        }
    }

    /** Enciende la cámara (conectándose a ella) o la apaga. Vuelve enseguida. */
    fun togglePower(announce: Boolean): Boolean =
        if (switchedOff.value || _state.value !is CameraState.Connected) switchOn(announce) else switchOff(announce)

    private fun requestWake() {
        foundAsleep = false
        wakeRequests.update { it + 1 }
    }

    private fun switchOn(announce: Boolean) = runAction(t("Encender", "Switch on")) {
        if (announce) _notices.emit(Notice(t("Cámara lista", "Camera ready"), isError = false))
    }

    private fun switchOff(announce: Boolean, detail: String? = null) = runAction(t("Apagar", "Switch off"), wake = false) {
        // Grabando, la cámara no se deja apagar: primero se para la grabación y se le da tiempo a guardar
        if ((_state.value as? CameraState.Connected)?.recording == true) {
            event(t("Se para la grabación antes de apagar", "Stopping the recording before switching off"))
            setRecording(false)
            withTimeoutOrNull(8.seconds) { state.first { it is CameraState.Connected && !it.recording && !it.busy } }
        }
        sleepWithRetry()
        // Se deja de conectar, que es lo que la volvería a despertar
        setSwitchedOff(true)
        if (announce) _notices.emit(Notice(t("Cámara apagada", "Camera off"), detail, isError = false))
    }

    /**
     * Ejecuta [action] cuando la cámara está conectada (una acción cada vez).
     * @param wake encender la cámara si se había apagado desde BiciCAM
     */
    /**
     * @param needsStatus la acción depende de si la cámara graba: se espera a que ella lo diga
     * @param expires si el toque se hizo sin conexión y esta tarda, ya no se ejecuta (se pide tocar otra vez)
     * @param quietIfBusy no avisar si hay otra orden en marcha (para lo que BiciCAM hace por su cuenta)
     * @return false si no se ha podido empezar porque había otra orden en marcha
     */
    private fun runAction(
        name: String,
        wake: Boolean = true,
        needsStatus: Boolean = false,
        expires: Boolean = false,
        quietIfBusy: Boolean = false,
        action: suspend (CameraState.Connected) -> Unit,
    ): Boolean {
        if (!actionRunning.compareAndSet(false, true)) {
            // Mejor decirlo que tragarse el toque
            if (!quietIfBusy) _notices.tryEmit(Notice(t("Espera", "Wait"), t("Hay otra orden en marcha", "Another command is in progress"), isError = false))
            return false
        }
        activity.update { it + 1 }
        val tappedAt = SystemClock.elapsedRealtime()
        val connectedAtTap = _state.value is CameraState.Connected
        scope.launch {
            acquire()
            var done = false
            try {
                if (_pairedCamera.value == null) {
                    _notices.emit(Notice(t("Sin emparejar", "Not paired"), t("Emparéjala en la app BiciCAM", "Pair it in the BiciCAM app"), isError = true))
                    return@launch
                }
                if (switchedOff.value) {
                    if (!wake) return@launch
                    foundAsleep = false
                    setSwitchedOff(false)
                } else if (wake && (_state.value is CameraState.Searching || _state.value is CameraState.Standby)) {
                    // La cámara no aparece: se corta la espera en curso y se la busca ya
                    requestWake()
                }
                val reached = try {
                    withTimeout(50.seconds) { state.first { it is CameraState.Connected || it is CameraState.Asleep } }
                } catch (e: TimeoutCancellationException) {
                    _notices.emit(Notice(t("No hay cámara", "No camera found"), t("Enciéndela con su botón", "Switch it on with its button"), isError = true))
                    return@launch
                }
                val connected = reached as? CameraState.Connected
                if (connected == null) {
                    // Los títulos de los avisos del Karoo se cortan hacia las 15 letras
                    _notices.emit(Notice(t("Cámara dormida", "Camera asleep"), t("Pulsa su botón y conecta sola", "Press its button to reconnect"), isError = true))
                    return@launch
                }
                if (expires && !connectedAtTap && SystemClock.elapsedRealtime() - tappedAt > STALE_TAP_MS) {
                    event(t("$name: el toque es de hace más de 10 s y ya no se ejecuta", "$name: the tap is over 10 s old and is dropped"))
                    _notices.emit(Notice(t("Cámara lista", "Camera ready"), t("Toca otra vez", "Tap again"), isError = false))
                    return@launch
                }
                var current: CameraState.Connected = connected
                if (needsStatus && !current.statusKnown) {
                    // Sin saber si ya graba, "grabar o parar" podría hacer lo contrario de lo que se quiere
                    val known = withTimeoutOrNull(3.seconds) { state.first { it is CameraState.Connected && it.statusKnown } } as? CameraState.Connected
                    if (known == null) {
                        event(t("$name: la cámara aún no ha dicho si graba; no se le manda nada", "$name: the camera hasn't said whether it is recording; nothing was sent"))
                        _notices.emit(Notice(t("Sin confirmar", "Unconfirmed"), t("Sin estado. Toca otra vez", "No status yet. Tap again"), isError = true))
                        return@launch
                    }
                    current = known
                }
                action((_state.value as? CameraState.Connected) ?: current)
                done = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: CameraProblem) {
                EventLog.add("$name: ${e.message}")
                _notices.emit(Notice(e.title, e.advice, isError = true))
            } catch (e: Exception) {
                // Lo técnico, al registro; en pantalla, qué hacer
                Log.w(TAG, "$name: fallo", e)
                EventLog.add(t("$name: fallo (${e.message})", "$name: failed (${e.message})"))
                _notices.emit(Notice(t("Fallo de cámara", "Camera error"), t("Si sigue, apágala y enciéndela", "If it persists, restart camera"), isError = true))
            } finally {
                if (!done) _actionFailures.tryEmit(Unit)
                release()
                actionRunning.set(false)
            }
        }
        return true
    }

    /** Apaga la cámara; puede negarse un momento mientras termina de guardar una grabación. */
    private suspend fun sleepWithRetry() {
        val driver = driver ?: throw IllegalStateException(t("sin cámara", "no camera"))
        sleepRequestedAt = SystemClock.elapsedRealtime()
        repeat(6) { attempt ->
            event(t("Orden de apagar", "Switch-off command"))
            try {
                driver.sleep()
                return
            } catch (e: IllegalStateException) {
                if (attempt == 5) throw e
                event(t("La cámara aún no se deja apagar: ${e.message}", "The camera will not switch off yet: ${e.message}"))
                delay(1.seconds)
            }
        }
    }

    private suspend fun setRecording(on: Boolean) {
        val driver = driver ?: throw IllegalStateException(t("sin cámara", "no camera"))
        event(if (on) t("Orden de grabar", "Record command") else t("Orden de parar", "Stop command"))
        if (!on) stopRequestedAt = SystemClock.elapsedRealtime()
        driver.setRecording(on)
        orderedRecording = on
        recordingOrderedAt = SystemClock.elapsedRealtime()
        // Se da por hecho; el estado que manda la cámara lo confirma poco después
        _state.update { current ->
            if (current is CameraState.Connected) {
                current.copy(recording = on, recordingSince = if (on) SystemClock.elapsedRealtime() else null, markers = 0, stalled = false)
            } else {
                current
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun manageConnection() {
        val wanted = users
            .map { it > 0 }
            .distinctUntilChanged()
            .transformLatest { active ->
                if (!active) delay(LINGER)
                emit(active)
            }
            .onStart { emit(false) }
            .distinctUntilChanged()
        combine(_pairedCamera, wanted, switchedOff, wakeRequests) { camera, want, off, _ -> Triple(camera, want, off) }.collectLatest { (camera, want, off) ->
            val driver = driver
            when {
                camera == null || driver == null -> _state.value = CameraState.NotPaired
                off -> {
                    _state.value = CameraState.Off(canWake = driver.canWake)
                    // Si el Karoo no puede encenderla, al menos se entera solo de que la han encendido a mano
                    if (want && !driver.canWake) watchForManualWake(driver)
                }
                !want -> _state.value = CameraState.Standby
                else -> {
                    connectLoop(camera, driver)
                    return@collectLatest
                }
            }
            driver?.onIdle()
        }
    }

    /**
     * Con la cámara apagada y un campo a la vista, mira cada poco si la han encendido con su botón. En
     * cuanto contesta, deja de darla por apagada y la conexión normal hace el resto.
     */
    private suspend fun watchForManualWake(driver: CameraDriver) {
        // Recién apagada no tiene sentido mirar: tarda unos segundos en dormirse del todo
        delay(WAKE_WATCH_INTERVAL)
        var probes = 0
        while (true) {
            // Tras unos minutos apagada se mira más de tarde en tarde y gastando menos; un toque vuelve a empezar
            val calm = probes++ >= CALM_AFTER_FAILURES
            val awake = try {
                hasBluetoothPermission() && driver.probeAwake(calm) { }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (awake) {
                event(t("La cámara está encendida otra vez: se conecta", "The camera is on again: connecting"))
                setSwitchedOff(false)
                return
            }
            delay(if (calm) WAKE_WATCH_INTERVAL * 3 else WAKE_WATCH_INTERVAL)
        }
    }

    /** Lo que cada sesión va contando; ver [SessionHost]. */
    private val host = object : SessionHost {
        override fun onLinkUp() {
            // La cámara ha contestado; aún puede tardar unos segundos en aceptar órdenes.
            // Si el intento anterior la encontró dormida, se deja ese rótulo hasta saber que ha despertado.
            if (!foundAsleep) _state.value = CameraState.Starting
            linkUpAt = SystemClock.elapsedRealtime()
            event(t("La cámara ha contestado", "The camera answered"))
        }

        override fun onReady() {
            foundAsleep = false
            _state.value = CameraState.Connected()
            sessionReady = true
            bluetoothOffLogged = false
            connectLogged = false
            lastLoggedBattery = null
            lastReportedRecording = null
            reportedRecording.value = null
            orderedRecording = null
            lastStatusAt = SystemClock.elapsedRealtime()
            // El contador de la cámara y su ritmo no se olvidan al reconectar: si la reconexión fue por una cámara
            // colgada y sigue igual, tiene que volver a verse (se reinician cuando ella dice que no graba)
            statusGaps = 0
            bluetoothOffTries = 0
            setProbablyEmpty(null)
            event(t("Cámara lista (${(SystemClock.elapsedRealtime() - linkUpAt) / 1000.0} s después de contestar)", "Camera ready (${(SystemClock.elapsedRealtime() - linkUpAt) / 1000.0} s after answering)"))
        }

        override fun onStatus(update: StatusUpdate) = onCameraStatus(update)

        override fun log(message: String) = event(message)
    }

    /**
     * Sigue intentando conectar mientras se quiera la cámara, alternando un intento corto con uno largo:
     * el corto encuentra enseguida una cámara despierta y el largo le da tiempo a una que tarda.
     */
    private suspend fun connectLoop(camera: PairedCamera, driver: CameraDriver) {
        var attempt = 0
        var failures = 0
        // Una sesión cancelada porque ya no se quería la cámara no ha perdido su conexión
        sessionReady = false
        while (true) {
            if (!hasBluetoothPermission()) {
                _state.value = CameraState.NoPermission
                delay(5.seconds)
                continue
            }
            _state.value = if (foundAsleep) CameraState.Asleep else CameraState.Searching
            // Solo se apunta una vez por corte: los intentos se repiten cada pocos segundos y llenarían el registro
            if (!connectLogged) {
                connectLogged = true
                event(t("Buscando la cámara", "Looking for the camera"))
            }
            val quick = attempt++ % 2 == 0
            var pause = 5.seconds
            // Lleva mucho sin aparecer: se sigue buscando, pero con calma. Un toque en un campo vuelve a la búsqueda rápida
            val calm = failures >= CALM_AFTER_FAILURES
            try {
                driver.runSession(host, quick, calm)
            } catch (e: CameraAsleepException) {
                if (!foundAsleep) event(t("La cámara está dormida: acepta la conexión pero no contesta. Se despierta con su botón", "The camera is asleep: it accepts the connection but does not answer. Wake it with its button"))
                foundAsleep = true
                _state.value = CameraState.Asleep
                // Se sigue probando, con calma, para conectar sola en cuanto la despierten
                delay(12.seconds)
                continue
            } catch (e: CameraNotAnsweringException) {
                foundAsleep = false
                // Solo se apunta el primer fallo: cientos de líneas iguales taparían lo importante
                if (attempt == 1) event(t("La cámara no contesta; se sigue intentando", "No response from the camera; still trying"))
                if (attempt >= 3) noteUnreachable()
                if (camera.brand == Brand.DJI && (attempt == 3 || attempt % 20 == 0)) checkAdvertising(camera, firstCheck = attempt == 3)
                failures++
                if (failures == CALM_AFTER_FAILURES) event(t("La cámara sigue sin aparecer: se la busca con calma para no gastar batería", "Still no camera: searching slowly to save battery"))
                if (calm) delay(CALM_PAUSE)
                continue
            } catch (e: BluetoothOffException) {
                // El Karoo ha apagado el Bluetooth de las extensiones: se le vuelve a pedir y se reintenta pronto,
                // sin pasar al modo calmado (la cámara no tiene la culpa)
                if (!bluetoothOffLogged) {
                    bluetoothOffLogged = true
                    event(t("El Karoo tiene el Bluetooth apagado para BiciCAM: se le vuelve a pedir", "The Karoo has Bluetooth off for BiciCAM: asking for it again"))
                }
                onBluetoothOff?.invoke()
                if (sessionReady) {
                    sessionReady = false
                    noteConnectionLost()
                }
                _state.value = CameraState.Searching
                // Las primeras veces, enseguida; si el Karoo no lo devuelve, cada medio minuto
                delay(if (bluetoothOffTries++ < 3) 3.seconds else 30.seconds)
                continue
            } catch (e: DjiRejectedException) {
                event(t("La cámara ha rechazado al Karoo como mando", "The camera rejected the Karoo as a remote"))
                _notices.emit(Notice(t("Karoo rechazado", "Karoo rejected"), t("Olvídala y emparéjala otra vez", "Forget it and pair it again"), isError = true))
                // Sin prisa: cada intento hace que la cámara vuelva a preguntar en pantalla
                pause = 60.seconds
            } catch (e: TimeoutCancellationException) {
                // Un paso de la conexión se quedó sin respuesta: es un intento fallido, no una cancelación
                event(t("La cámara no contestó durante la conexión (${e.message}); se vuelve a intentar", "The camera did not answer during setup (${e.message}); trying again"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                event(t("La sesión con la cámara ha terminado: ${e.message}", "The camera session ended: ${e.message}"))
            }
            if (sessionReady) {
                sessionReady = false
                val wasRecording = (_state.value as? CameraState.Connected)?.recording == true
                val switchedOffByUs = SystemClock.elapsedRealtime() - sleepRequestedAt < 15_000 || switchedOff.value
                if (wasRecording && !switchedOffByUs) {
                    // Sin conexión no se sabe si sigue grabando: el marco rojo se apaga y hay que decir por qué
                    _notices.emit(Notice(t("Sin conexión", "Not connected"), t("La cámara estaba grabando", "The camera was recording"), isError = true))
                }
                noteConnectionLost()
                // Tras perder la conexión se empieza otra vez por un intento corto
                attempt = 0
                failures = 0
            } else {
                // Una sesión que no ha llegado a servir (rechazada, sin estado, cortada al conectar) también es
                // un intento fallido: si se repite, se pasa al modo calmado en vez de reconectar sin fin
                failures++
                if (failures == CALM_AFTER_FAILURES) event(t("La cámara sigue sin dejarse usar: se la busca con calma para no gastar batería", "Still no usable camera: searching slowly to save battery"))
                if (failures >= CALM_AFTER_FAILURES) pause = maxOf(pause, CALM_PAUSE)
            }
            _state.value = if (foundAsleep) CameraState.Asleep else CameraState.Searching
            delay(pause)
        }
    }

    private var lastAdvertisingHeard: Boolean? = null

    /**
     * Durante un corte: ¿se sigue anunciando la cámara? Si se la oye, está a tiro pero no acepta la conexión;
     * si no, está lejos, apagada o dormida. Se apunta cuando la respuesta cambia.
     */
    private suspend fun checkAdvertising(camera: PairedCamera, firstCheck: Boolean) {
        val rssi = try {
            listenForCamera(appContext, camera.address, ADVERTISING_LISTEN)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            event(t("No se ha podido escuchar a la cámara: ${e.message}", "Could not listen for the camera: ${e.message}"))
            return
        }
        val heard = rssi != null
        if (!firstCheck && heard == lastAdvertisingHeard) return
        lastAdvertisingHeard = heard
        event(
            if (rssi != null) t("La cámara se anuncia ($rssi dBm) pero no acepta la conexión", "The camera is advertising ($rssi dBm) but does not accept the connection")
            else t("No se oye a la cámara en ${ADVERTISING_LISTEN.inWholeSeconds} s: está lejos, apagada o dormida", "No signal from the camera for ${ADVERTISING_LISTEN.inWholeSeconds} s: out of range, off or asleep"),
        )
    }

    /** Apunta en el registro del sistema y en el historial que se puede leer después (ver [EventLog]). */
    private fun event(message: String) {
        Log.i(TAG, message)
        EventLog.add(message)
    }

    /** Se ha perdido una conexión que funcionaba; si no la apagamos nosotros y tenía poca batería, probablemente está vacía. */
    private fun noteConnectionLost() {
        val percent = lastBattery
        val switchedOffByUs = SystemClock.elapsedRealtime() - sleepRequestedAt < 15_000 || switchedOff.value
        if (!switchedOffByUs && percent != null && percent <= EMPTY_BATTERY_THRESHOLD) {
            event(t("La cámara ha desaparecido con un $percent % de batería: probablemente vacía", "Lost the camera at $percent% battery: probably flat"))
            setProbablyEmpty(ProbablyEmpty(percent, System.currentTimeMillis()))
        } else {
            val battery = percent?.let { "$it %" } ?: t("desconocida", "unknown")
            val byUs = if (switchedOffByUs) t("sí", "yes") else t("no", "no")
            event(t("Conexión con la cámara perdida (última batería: $battery; apagada por BiciCAM: $byUs)", "Connection to the camera lost (last battery: $battery; switched off by BiciCAM: $byUs)"))
        }
    }

    /**
     * La cámara no contesta aunque se la quiere: si su última batería conocida era baja y no la apagamos
     * nosotros, probablemente está vacía (también si la conexión se perdió con la app cerrada).
     */
    private fun noteUnreachable() {
        val percent = lastBattery ?: return
        val switchedOffByUs = SystemClock.elapsedRealtime() - sleepRequestedAt < 15_000 || switchedOff.value
        if (switchedOffByUs || percent > EMPTY_BATTERY_THRESHOLD || _probablyEmpty.value != null) return
        event(t("La cámara no contesta y su última batería era del $percent %: probablemente vacía", "No response from the camera and its last battery level was $percent%: probably flat"))
        setProbablyEmpty(ProbablyEmpty(percent, System.currentTimeMillis()))
    }

    private fun setProbablyEmpty(value: ProbablyEmpty?) {
        if (_probablyEmpty.value == value) return
        _probablyEmpty.value = value
        prefs.edit().apply {
            if (value == null) {
                remove(KEY_EMPTY_PERCENT)
                remove(KEY_EMPTY_AT)
            } else {
                putInt(KEY_EMPTY_PERCENT, value.percent)
                putLong(KEY_EMPTY_AT, value.atMillis)
            }
        }.apply()
    }

    private fun onCameraStatus(raw: StatusUpdate) {
        // Lo que llega antes de que la cámara esté lista se repite después: aquí solo duplicaría el registro
        if (!sessionReady) return
        val now = SystemClock.elapsedRealtime()
        val gap = now - lastStatusAt
        if (lastStatusAt != 0L && gap > 1_500) {
            statusGaps++
            // Los primeros de cada sesión y luego uno de cada veinte: el rastro sin llenar el registro
            if (statusGaps <= 5 || statusGaps % 20 == 0) event(t("La cámara ha estado ${gap / 1000.0} s sin mandar su estado (hueco $statusGaps de esta sesión)", "The camera sent no status for ${gap / 1000.0} s (gap $statusGaps this session)"))
        }
        lastStatusAt = now
        // El contador de la cámara solo sirve de vigía mientras graba; sin grabar se deja al día para que el
        // arranque de la siguiente grabación no parezca un reloj parado
        raw.realTimeVideo?.let { normalVideo = it }
        when (raw.recording) {
            true -> {
                val seconds = raw.recordingSeconds
                if (seconds == null) {
                    clockAdvancedAt = null
                } else if (seconds != lastClockSeconds || clockAdvancedAt == null) {
                    // ¿Va a tiempo real? Solo entonces un contador parado quiere decir cámara colgada
                    steadyTicks = steadyTicksAfter(steadyTicks, clockAdvancedAt?.takeIf { lastClockSeconds != null }?.let { now - it })
                    lastClockSeconds = seconds
                    clockAdvancedAt = now
                }
            }
            false -> {
                lastClockSeconds = null
                clockAdvancedAt = now
                steadyTicks = 0
                stallReconnects = 0
                stallAlerted = false
            }
            null -> Unit
        }
        // Un «graba» o «no graba» contrario a la orden recién mandada es de antes de ella: no se le hace caso,
        // o el campo y el marco parpadearían y un toque en ese instante haría lo contrario de lo que se ve
        val update = if (raw.recording != null && isStaleRecordingStatus(raw.recording, orderedRecording, recordingOrderedAt, now)) {
            raw.copy(recording = null, recordingSeconds = null)
        } else {
            raw
        }
        update.recording?.let { reportedRecording.value = it }
        update.batteryPercent?.let { percent ->
            if (lastBattery != percent) prefs.edit().putInt(KEY_LAST_BATTERY, percent).apply()
            lastBattery = percent
            // Cada 10 % y, cuando queda poca, cada paso: el rastro antes de que se agote
            val logged = lastLoggedBattery
            if (logged == null || percent != logged && (percent <= 20 || kotlin.math.abs(percent - logged) >= 10)) {
                lastLoggedBattery = percent
                event(t("Batería de la cámara: $percent %", "Camera battery: $percent%"))
            }
        }
        update.recording?.let { recording ->
            if (recording != lastReportedRecording) {
                val wasRecording = lastReportedRecording == true
                lastReportedRecording = recording
                event(if (recording) t("La cámara dice que graba", "The camera reports: recording") else t("La cámara dice que no graba", "The camera reports: not recording"))
                val askedByUs = SystemClock.elapsedRealtime() - stopRequestedAt < 10_000 || SystemClock.elapsedRealtime() - sleepRequestedAt < 15_000
                // En modo foto la cámara dice «graba» mientras dispara y «no graba» un segundo después: no es una
                // grabación que se haya parado sola (visto en el registro de Antun del 22/11/2026)
                val photo = (_state.value as? CameraState.Connected)?.mode == CameraMode.PHOTO
                if (wasRecording && !recording && !askedByUs && !photo && AppSettings.alertStopped.value) {
                    _notices.tryEmit(Notice(t("Ya no graba", "Not recording"), t("Se paró sola. Mira la cámara", "Stopped by itself. Check it"), isError = true))
                }
            }
        }
        update.batteryPercent?.let { percent ->
            if (percent > 25) batteryAlertLevel = 0
            val level = if (percent <= 10) 10 else if (percent <= 20) 20 else 0
            if (level != 0 && (batteryAlertLevel == 0 || level < batteryAlertLevel)) {
                batteryAlertLevel = level
                if (AppSettings.alertBattery.value) {
                    _notices.tryEmit(Notice(t("Batería cámara", "Camera battery"), t("Le queda un $percent %", "$percent% left"), isError = level == 10))
                }
            }
        }
        update.remainingSeconds?.let { seconds ->
            if (seconds > 15 * 60) cardAlerted = false
            if (seconds <= 10 * 60 && !cardAlerted) {
                cardAlerted = true
                if (AppSettings.alertCard.value) {
                    // El dato va en el título, que es lo que se lee de un vistazo
                    _notices.tryEmit(Notice(t("Tarjeta: ${seconds / 60} min", "Card: ${seconds / 60} min"), t("Caben ${seconds / 60} min más de vídeo", "${seconds / 60} min of video left"), isError = true))
                }
            }
        }
        if (update.asleep == true && !switchedOff.value && _state.value is CameraState.Connected) {
            // Se ha dormido sola o con su botón: se la deja en paz hasta que se pida encenderla
            event(t("La cámara se ha dormido", "The camera went to sleep"))
            setSwitchedOff(true)
            return
        }
        _state.update { current ->
            if (current !is CameraState.Connected) return@update current
            var next: CameraState.Connected = current
            update.busy?.let { next = next.copy(busy = it) }
            update.batteryPercent?.let { next = next.copy(batteryPercent = it) }
            update.runtime?.let { next = next.copy(runtime = it) }
            update.screenOff?.let { next = next.copy(screenOff = it) }
            update.remainingSeconds?.let { if (it != next.remainingSeconds) next = next.copy(remainingSeconds = it) }
            update.format?.let { next = next.copy(format = it) }
            update.mode?.let { if (it != next.mode) next = next.copy(mode = it) }
            update.recording?.let { recording ->
                next = next.copy(
                    statusKnown = true,
                    recording = recording,
                    recordingSince = if (recording) next.recordingSince ?: SystemClock.elapsedRealtime() else null,
                    // Los marcadores se cuentan por clip
                    markers = if (recording) next.markers else 0,
                )
            }
            update.recordingSeconds?.let { seconds ->
                // Se ignoran valores imposibles; una grabación dura menos de un día
                if (next.recording && seconds < 24 * 3600) {
                    val since = SystemClock.elapsedRealtime() - seconds * 1000L
                    // Solo se corrige si se ha desviado: así el estado no cambia con cada aviso de la cámara
                    val known = next.recordingSince
                    if (known == null || kotlin.math.abs(known - since) > 1_500) next = next.copy(recordingSince = since)
                }
            }
            next
        }
    }

    fun hasBluetoothPermission(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return permissions.all { ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED }
    }
}
