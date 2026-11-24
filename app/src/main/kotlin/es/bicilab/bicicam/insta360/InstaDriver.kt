package es.bicilab.bicicam.insta360

import es.bicilab.bicicam.camera.t
import android.content.Context
import android.os.SystemClock
import es.bicilab.bicicam.camera.Brand
import es.bicilab.bicicam.camera.CameraDriver
import es.bicilab.bicicam.camera.AppSettings
import es.bicilab.bicicam.camera.CameraNotAnsweringException
import es.bicilab.bicicam.camera.CameraProblem
import es.bicilab.bicicam.camera.LocationFeed
import es.bicilab.bicicam.camera.PairedCamera
import es.bicilab.bicicam.camera.SessionHost
import es.bicilab.bicicam.camera.StatusUpdate
import es.bicilab.bicicam.camera.toHex
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.seconds

/** Resultado de emparejar una Insta360: la cámara y, si lo ha dicho, su número de serie. Aún sin guardar. */
class InstaPairing(val camera: PairedCamera, val serial: String?)

/**
 * Primer emparejamiento: el Karoo se anuncia como mando nuevo y espera a que la cámara lo elija en su menú
 * de mandos Bluetooth. Al conectarse, la cámara manda su número de serie, que es lo que después la despierta.
 */
suspend fun pairInstaCamera(context: Context, log: (String) -> Unit): InstaPairing = coroutineScope {
    val remote = InstaRemote(context, serial = null, pairedAddress = null, log = log)
    try {
        var serial: String? = null
        launch(start = CoroutineStart.UNDISPATCHED) {
            remote.frames.collect { frame -> frame.serial()?.let { serial = it } }
        }
        remote.start()
        val device = withTimeoutOrNull(120.seconds) { remote.camera.await() }
            ?: throw IOException(t("Ninguna cámara ha elegido al Karoo como mando. Búscalo en el menú de mandos Bluetooth de la cámara.", "No camera has picked the Karoo yet. On the camera, open the Bluetooth remote menu and choose \"Insta360 GPS Remote\"."))
        // El número de serie llega en cuanto se conecta
        withTimeoutOrNull(15.seconds) {
            while (serial == null) delay(200)
        }
        if (serial == null) log(t("La cámara no ha dicho su número de serie: no se la podrá encender desde el Karoo", "The camera did not send its serial number: the Karoo will not be able to switch it on"))
        val name = serial?.let { "Insta360 $it" } ?: "Insta360 ${device.address.takeLast(5)}"
        InstaPairing(PairedCamera(Brand.INSTA360, device.address, name), serial)
    } finally {
        remote.close()
        InstaRemote.restoreName(context)
        coroutineContext.cancelChildren()
    }
}

/**
 * @param serial número de serie guardado de la cámara, si se conoce
 * @param onSerial se llama cuando la cámara dice un número de serie distinto del guardado
 */
class InstaDriver(
    private val context: Context,
    private val camera: PairedCamera,
    serial: String?,
    private val onSerial: (String) -> Unit,
) : CameraDriver {
    @Volatile
    private var serial: String? = serial

    /** La cámara conectada ha dicho el número de serie guardado: solo entonces se le pasa la posición. */
    @Volatile
    private var verified = false

    /** La cámara ya ha enseñado algo en la pantalla del mando en esta conexión: se sabe si graba. */
    @Volatile
    private var displaySeen = false

    /** Sin haber visto nunca su contador, BiciCAM solo sabe lo que ha pulsado, no lo que hace la cámara. */
    override val confirmsRecording: Boolean get() = seenTimer

    /** Con el número de serie, anunciarse ya despierta a la cámara. */
    override val canWake: Boolean get() = serial != null

    @Volatile
    private var remote: InstaRemote? = null

    /** El mando que se anuncia a la espera de la cámara: se conserva entre intentos para no cambiar de dirección. */
    @Volatile
    private var waiting: InstaRemote? = null

    @Volatile
    private var lastShutterAt = 0L

    private val prefs = context.getSharedPreferences("insta360", Context.MODE_PRIVATE)

    /**
     * Si esta cámara ha enseñado alguna vez su contador de grabación. Solo entonces se da por bueno que un
     * texto sin contador significa "no graba"; si nunca lo enseña, BiciCAM se fía de sus propias pulsaciones.
     */
    @Volatile
    private var seenTimer = prefs.getBoolean(KEY_SEEN_TIMER, false)

    override suspend fun runSession(host: SessionHost, quick: Boolean, calm: Boolean) = coroutineScope {
        // El mando que ya se estaba anunciando se conserva de un intento a otro. Cada vez que el Karoo vuelve
        // a anunciarse cambia de dirección Bluetooth, y la cámara conecta a la dirección que vio: reiniciando
        // el anuncio cada pocos segundos, cuando ella llama ya no hay nadie (visto con una Ace Pro 2 el
        // 13/11/2026: «error de conexión» en la cámara y ningún enlace llegaba al Karoo).
        val reused = waiting
        val remote = reused ?: InstaRemote(context, serial, camera.address, calm, host::log)
        waiting = remote
        var keep = false
        verified = false
        displaySeen = false
        try {
            // Suscrito antes de anunciarse: el número de serie llega nada más conectar
            launch(start = CoroutineStart.UNDISPATCHED) { readCamera(remote, host) }
            if (reused == null) remote.start()
            if (withTimeoutOrNull(if (quick) 20.seconds else 60.seconds) { remote.camera.await() } == null) {
                keep = true
                throw CameraNotAnsweringException()
            }
            host.onLinkUp()
            withTimeoutOrNull(20.seconds) { remote.subscribed.await() }
                ?: throw IOException(t("La cámara se ha conectado pero no ha activado los avisos del mando", "The camera connected but isn't listening to the remote yet"))
            // Con la cámara conectada ya no hay que anunciarse
            remote.stopAdvertising()
            this@InstaDriver.remote = remote
            host.onReady()
            launch { feedGps(remote, host) }
            launch { tryBond(remote, host) }
            val status = remote.disconnected.await()
            host.log(t("La cámara se ha desconectado (estado $status)", "The camera disconnected (status $status)"))
        } finally {
            this@InstaDriver.remote = null
            if (!keep) {
                waiting = null
                remote.close()
            }
            coroutineContext.cancelChildren()
        }
    }

    /**
     * La cámara guarda al mando por su dirección Bluetooth, y el Karoo cambia de dirección cada vez que
     * vuelve a anunciarse (visto con una Ace Pro 2 el 13/11/2026: tras apagarla, el Karoo le sale como
     * aparato nuevo y no reconecta). Vinculados, los dos aparatos se intercambian la clave con la que la
     * cámara podría reconocer al Karoo aunque cambie de dirección. Es un intento: si la cámara no quiere
     * vincularse, todo sigue como estaba.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun tryBond(remote: InstaRemote, host: SessionHost) {
        val device = remote.camera.await()
        try {
            delay(3_000)
            when (device.bondState) {
                android.bluetooth.BluetoothDevice.BOND_BONDED -> host.log(t("La cámara ya está vinculada con el Karoo", "The camera is already bonded with the Karoo"))
                android.bluetooth.BluetoothDevice.BOND_NONE -> {
                    val started = device.createBond()
                    host.log(t("Se pide a la cámara vincularse con el Karoo (${if (started) "pedido" else "el Karoo no lo ha admitido"})", "Asking the camera to bond with the Karoo (${if (started) "requested" else "the Karoo refused"})"))
                    delay(12_000)
                    val bonded = device.bondState == android.bluetooth.BluetoothDevice.BOND_BONDED
                    host.log(if (bonded) t("La cámara se ha vinculado con el Karoo", "The camera bonded with the Karoo") else t("La cámara no se ha vinculado con el Karoo", "The camera did not bond with the Karoo"))
                }
                else -> host.log(t("La cámara se está vinculando con el Karoo", "The camera is bonding with the Karoo"))
            }
        } catch (e: SecurityException) {
            host.log(t("Sin permiso para vincular: ${e.message}", "No permission to bond: ${e.message}"))
        }
    }

    /** Lee lo que escribe la cámara: su número de serie y los textos de la pantalla del mando. */
    private suspend fun readCamera(remote: InstaRemote, host: SessionHost) {
        var lastTimerAt = 0L
        var recording: Boolean? = null
        var lastText = ""
        var loggedTexts = 0
        var loggedOthers = 0
        remote.frames.collect { frame ->
            val now = SystemClock.elapsedRealtime()
            val newSerial = frame.serial()
            val text = frame.displayText()
            when {
                newSerial != null -> when (val saved = serial) {
                    null -> {
                        // Primera vez que lo dice: se guarda y ya no se cambia
                        serial = newSerial
                        verified = true
                        onSerial(newSerial)
                        host.log(t("Número de serie de la cámara: $newSerial", "Camera serial number: $newSerial"))
                    }
                    newSerial -> verified = true
                    else -> {
                        // No es la cámara emparejada: ni se guarda su número ni se le pasa la posición
                        host.log(t("La cámara conectada dice otro número de serie: no se le pasa el GPS y se corta", "The connected camera reports a different serial number: no GPS is sent and it is disconnected"))
                        throw IOException(t("no es la cámara emparejada", "not the paired camera"))
                    }
                }
                text != null -> when (val display = InstaDisplay.classify(text)) {
                    is InstaDisplay.Recording -> {
                        displaySeen = true
                        lastTimerAt = now
                        if (!seenTimer) {
                            seenTimer = true
                            prefs.edit().putBoolean(KEY_SEEN_TIMER, true).apply()
                        }
                        if (recording != true) {
                            recording = true
                            host.log(t("La cámara enseña el contador de grabación", "The camera shows the recording timer"))
                        }
                        host.onStatus(StatusUpdate(recording = true, recordingSeconds = display.seconds))
                    }
                    else -> {
                        if (display is InstaDisplay.Runtime) host.onStatus(StatusUpdate(runtime = display.text))
                        // Cada texto nuevo se apunta unas cuantas veces: así se aprende qué enseña cada modelo
                        if (display is InstaDisplay.Other && display.text != lastText && loggedTexts < 30) {
                            loggedTexts++
                            lastText = display.text
                            // Lo escribe la cámara: se limpia para que no pueda colar líneas falsas en el registro
                            val clean = display.text.replace(Regex("\\p{Cntrl}"), " ").take(60)
                            host.log(t("Pantalla de la cámara: «$clean»", "Camera display: \"$clean\""))
                        }
                        if (!displaySeen) {
                            displaySeen = true
                            // Si esta cámara nunca enseña su contador, lo único que se puede decir es que no consta que grabe
                            if (!seenTimer) host.onStatus(StatusUpdate(recording = false))
                        }
                        // Sin contador desde hace un rato y sin una pulsación reciente: ya no graba
                        val quiet = now - lastTimerAt > 2_500 && now - lastShutterAt > 4_000
                        if (seenTimer && quiet && recording != false) {
                            if (recording == true) host.log(t("La cámara ya no enseña el contador de grabación", "The camera no longer shows the recording timer"))
                            recording = false
                            host.onStatus(StatusUpdate(recording = false))
                        }
                    }
                }
                frame.type != InstaFrames.TYPE_DISPLAY && loggedOthers < 12 -> {
                    loggedOthers++
                    host.log(t("Trama %02X de la cámara: %s", "Frame%02X from the camera: %s").format(frame.type, frame.payload.toHex()))
                }
            }
        }
    }

    /**
     * Una posición por segundo. El mando original manda diez; con una, la cámara sigue viendo al mando vivo
     * y guarda el recorrido. Sin posición reciente del Karoo, con el GPS desactivado en los ajustes o mientras
     * la cámara no haya dicho su número de serie, se manda la frase vacía.
     */
    private suspend fun feedGps(remote: InstaRemote, host: SessionHost) {
        var warned = false
        var announced = false
        while (true) {
            val fix = if (AppSettings.gpsToCamera.value && verified) {
                LocationFeed.current()?.let { InstaFix(it.latitude, it.longitude, it.speedMps, it.bearing) }
            } else {
                null
            }
            val sent = remote.gps(fix)
            if (!sent && !warned) {
                warned = true
                host.log(t("La cámara usa paquetes de ${remote.mtu} bytes y la posición no cabe: no se le pasa el GPS", "The camera uses ${remote.mtu}-byte packets and the position does not fit: GPS is not sent"))
            }
            if (sent && fix != null && !announced) {
                announced = true
                host.log(t("Pasando a la cámara el GPS del Karoo", "Sending the Karoo's GPS to the camera"))
            }
            delay(1000)
        }
    }

    /** El disparador del mando empieza y para: no hay una orden distinta para cada cosa. */
    override suspend fun setRecording(on: Boolean) {
        val remote = remote ?: throw IllegalStateException(t("sin conexión con la cámara", "not connected to the camera"))
        // El disparador alterna: pulsarlo sin saber si la cámara graba podría pararla mientras el marco se enciende
        if (!displaySeen) throw CameraProblem(t("Sin confirmar", "Unconfirmed"), t("Sin estado. Toca otra vez", "No status yet. Tap again"))
        lastShutterAt = SystemClock.elapsedRealtime()
        if (!remote.button(InstaButton.SHUTTER, InstaButton.CLICK)) {
            throw IllegalStateException(t("la cámara no ha recibido la pulsación", "the camera did not get the button press"))
        }
    }

    /**
     * Apagar es mantener pulsado el botón de encendido: una ráfaga de tramas "pulsado" cada 20 ms durante
     * unos dos segundos, cada una con su número. Una sola solo apaga la pantalla.
     */
    override suspend fun sleep() {
        val remote = remote ?: throw IllegalStateException(t("sin conexión con la cámara", "not connected to the camera"))
        val until = SystemClock.elapsedRealtime() + 2_400
        var delivered = 0
        while (SystemClock.elapsedRealtime() < until && !remote.disconnected.isCompleted) {
            if (remote.button(InstaButton.POWER, InstaButton.HELD)) delivered++
            delay(20)
        }
        if (delivered == 0) throw IllegalStateException(t("la cámara no ha recibido la orden de apagarse", "the camera did not get the switch-off command"))
    }

    /** El Karoo recupera su nombre Bluetooth en cuanto deja de buscar a la cámara. */
    override fun onIdle() {
        waiting?.close()
        waiting = null
        InstaRemote.restoreName(context)
    }

    companion object {
        private const val KEY_SEEN_TIMER = "seen_timer"
    }
}
