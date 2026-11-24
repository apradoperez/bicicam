package es.bicilab.bicicam.dji

import es.bicilab.bicicam.camera.t
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.SystemClock
import es.bicilab.bicicam.camera.BleClient
import es.bicilab.bicicam.camera.BluetoothOffException
import es.bicilab.bicicam.camera.Brand
import es.bicilab.bicicam.camera.CameraAsleepException
import es.bicilab.bicicam.camera.CameraDriver
import es.bicilab.bicicam.camera.CameraNotAnsweringException
import es.bicilab.bicicam.camera.CameraProblem
import es.bicilab.bicicam.camera.FoundCamera
import es.bicilab.bicicam.camera.LocationFeed
import es.bicilab.bicicam.camera.PairedCamera
import es.bicilab.bicicam.camera.AppSettings
import es.bicilab.bicicam.camera.SessionHost
import es.bicilab.bicicam.camera.StatusUpdate
import es.bicilab.bicicam.camera.bluetoothAdapter
import es.bicilab.bicicam.camera.toHex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Identidad con la que BiciCAM se presenta a las cámaras DJI: un identificador y una dirección inventados
 * una sola vez y guardados, para que la cámara reconozca al Karoo como el mando que ya aceptó.
 */
class DjiIdentity(val deviceId: Long, val mac: ByteArray) {
    companion object {
        private const val PREFS = "dji"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_MAC = "mac"

        fun load(context: Context): DjiIdentity {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val savedMac = prefs.getString(KEY_MAC, null)
            if (savedMac != null && prefs.contains(KEY_DEVICE_ID)) {
                return DjiIdentity(prefs.getLong(KEY_DEVICE_ID, 0), savedMac.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            }
            val deviceId = Random.nextLong(0x10000000L, 0x7FFFFFFFL)
            val mac = Random.nextBytes(6)
            prefs.edit()
                .putLong(KEY_DEVICE_ID, deviceId)
                .putString(KEY_MAC, mac.joinToString("") { "%02x".format(it) })
                .apply()
            return DjiIdentity(deviceId, mac)
        }
    }
}

/** La cámara ha dicho que no a la conexión (en su pantalla se pulsó rechazar, o ya no reconoce al Karoo). */
class DjiRejectedException : IOException(t("La cámara ha rechazado la conexión", "The camera rejected the connection"))

/** Busca cámaras DJI compatibles que se estén anunciando, hasta que se cancele el flujo. */
@SuppressLint("MissingPermission")
fun scanForDjiCameras(context: Context): Flow<FoundCamera> = callbackFlow {
    val scanner = context.bluetoothAdapter().bluetoothLeScanner ?: throw BluetoothOffException()
    val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord ?: return
            if (!isDjiCameraAdvert(record.getManufacturerSpecificData(DjiUuids.COMPANY_ID))) return
            val name = record.deviceName ?: result.device.name ?: t("Cámara DJI", "DJI camera")
            trySend(FoundCamera(result.device.address, name))
        }

        override fun onScanFailed(errorCode: Int) {
            close(IOException(t("No se pudo buscar por Bluetooth (error $errorCode)", "Bluetooth scan failed (error $errorCode)")))
        }
    }
    // Con filtro de fabricante, la búsqueda sigue funcionando con la pantalla apagada
    val filter = ScanFilter.Builder()
        .setManufacturerData(DjiUuids.COMPANY_ID, byteArrayOf(0, 0, 0xFA.toByte()), byteArrayOf(0, 0, 0xFF.toByte()))
        .build()
    val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
    scanner.startScan(listOf(filter), settings, callback)
    awaitClose { scanner.stopScan(callback) }
}

@Volatile
private var lastAdvert: String? = null

/** Dirección de la cámara emparejada para la que ya se ha apuntado que hay otra con su nombre: una vez basta. */
@Volatile
private var sameNameLoggedFor: String? = null

/**
 * Busca a la cámara emparejada entre las DJI que se anuncian.
 * @param calm buscar gastando menos batería (la cámara lleva mucho sin aparecer)
 * @return el aparato tal como lo da la búsqueda, o null si no se la oye en [timeout]
 */
@SuppressLint("MissingPermission")
internal suspend fun findDjiCamera(
    context: Context,
    camera: PairedCamera,
    timeout: Duration,
    calm: Boolean = false,
    log: (String) -> Unit,
): BluetoothDevice? {
    // Las búsquedas de fondo se espacian para no pasar del límite de Android (ver [ScanGate])
    es.bicilab.bicicam.camera.ScanGate.await()
    return withTimeoutOrNull(timeout) {
        callbackFlow {
            val scanner = context.bluetoothAdapter().bluetoothLeScanner ?: throw BluetoothOffException()
            var otherLogged = false
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val record = result.scanRecord ?: return
                    if (!isDjiCameraAdvert(record.getManufacturerSpecificData(DjiUuids.COMPANY_ID))) return
                    val name = record.deviceName
                    // Lo que anuncia la cámara, apuntado cuando cambia: sirve para distinguir despierta de dormida
                    if (result.device.address.equals(camera.address, ignoreCase = true)) {
                        val advert = record.getManufacturerSpecificData(DjiUuids.COMPANY_ID)?.toHex()
                        if (advert != null && advert != lastAdvert) {
                            lastAdvert = advert
                            log(t("Anuncio de la cámara: $advert", "Camera advert: $advert"))
                        }
                    }
                    when {
                        result.device.address.equals(camera.address, ignoreCase = true) -> trySend(result.device)
                        // El mismo nombre con otra dirección no basta para conectar: el nombre se emite en
                        // abierto y cualquiera cerca puede anunciarlo. Solo se apunta, por si la cámara ha
                        // cambiado de dirección de verdad: entonces hay que emparejarla otra vez.
                        name != null && name == camera.advertName -> {
                            if (sameNameLoggedFor != camera.address) {
                                sameNameLoggedFor = camera.address
                                log(t("Se oye una cámara con el nombre de la emparejada pero con otra dirección: no se conecta. Si es la tuya, olvídala en Ajustes y emparéjala otra vez", "A camera with the paired camera's name but another address was heard: not connecting. If it is yours, forget it in Settings and pair it again"))
                            }
                        }
                        // De las cámaras de otros no se apunta ni el nombre ni la dirección
                        else -> if (!otherLogged) {
                            otherLogged = true
                            log(t("Se oye otra cámara DJI", "Another DJI camera heard"))
                        }
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    close(IOException(t("No se pudo buscar por Bluetooth (error $errorCode)", "Bluetooth scan failed (error $errorCode)")))
                }
            }
            val filter = ScanFilter.Builder()
                .setManufacturerData(DjiUuids.COMPANY_ID, byteArrayOf(0, 0, 0xFA.toByte()), byteArrayOf(0, 0, 0xFF.toByte()))
                .build()
            val mode = if (calm) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_LATENCY
            scanner.startScan(listOf(filter), ScanSettings.Builder().setScanMode(mode).build(), callback)
            awaitClose { scanner.stopScan(callback) }
        }.first()
    }
}

/**
 * Primer emparejamiento: conecta, pide permiso a la cámara con un código de cuatro cifras y espera a que
 * se acepte en su pantalla.
 * @param onCode se llama con el código que la cámara va a enseñar
 */
suspend fun pairDjiCamera(context: Context, found: FoundCamera, log: (String) -> Unit, onCode: (Int) -> Unit): PairedCamera =
    coroutineScope {
        val link = DjiLink(context, context.bluetoothAdapter().getRemoteDevice(found.address), log)
        try {
            if (!link.open(this, 15.seconds, onLinkUp = {})) throw IOException(t("La cámara no contesta. Acércala y comprueba que está encendida.", "The camera does not answer. Bring it closer and check that it is on."))
            val code = Random.nextInt(1000, 10000)
            onCode(code)
            link.handshake(firstPairing = true, verifyCode = code)
            // Un momento para que la cámara guarde el emparejamiento antes de cortar
            delay(700)
            val modelId = (link.cameraDeviceId and 0xFFFF).toInt()
            val model = DjiDeviceId.name(modelId)
            // El nombre anunciado y el modelo se guardan tal cual: con ellos se reconoce después a esta cámara y no a otra
            PairedCamera(
                Brand.DJI, found.address, model?.let { "$it (${found.name})" } ?: found.name,
                advertName = found.name, modelId = modelId.takeIf { it != 0 },
            )
        } finally {
            link.close()
            coroutineContext.cancelChildren()
        }
    }

/** Una conexión R SDK con la cámara: enlace Bluetooth, saludo y órdenes con su respuesta. */
internal class DjiLink(
    private val context: Context,
    device: BluetoothDevice,
    private val log: (String) -> Unit,
) {
    // Conexión directa: el aparato viene de una búsqueda recién hecha, así que se está anunciando
    val ble = BleClient(context, device, autoConnect = false)
    private val identity = DjiIdentity.load(context)
    private val assembler = DjiFrameAssembler()
    private val seq = AtomicInteger(Random.nextInt(1, 0x4000))
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<DjiFrame>>()
    private val writeMutex = Mutex()
    private var verify = CompletableDeferred<Boolean>()

    /** Número de secuencia de la petición de conexión de la cámara, que hay que repetir al confirmarla. */
    @Volatile
    private var cameraRequestSeq = 0

    /** El saludo ya ha confirmado la petición de la cámara: las repeticiones se confirman sobre la marcha. */
    @Volatile
    private var acknowledged = false
    private var lastAckAt = 0L
    private var scope: CoroutineScope? = null

    /** Tramas R SDK recibidas en esta conexión: si son cero, la cámara está conectada pero callada. */
    @Volatile
    private var framesReceived = 0

    /** Identificador de modelo que da la cámara (0xFF55: Osmo Action 6). */
    @Volatile
    var cameraDeviceId: Long = 0
        private set

    var onStatus: (DjiStatus) -> Unit = {}

    /** El primer estado que manda la cámara tras el saludo: dice, entre otras cosas, si está dormida. */
    val firstStatus = CompletableDeferred<DjiStatus>()

    /**
     * Abre el enlace y lo deja listo para el saludo.
     * @return false si la cámara no contestó en [linkTimeout]
     */
    suspend fun open(scope: CoroutineScope, linkTimeout: Duration, onLinkUp: () -> Unit): Boolean {
        this.scope = scope
        // Suscrito antes de conectar: el flujo descarta lo que llega cuando nadie escucha
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            ble.notifications.collect { (uuid, data) ->
                if (uuid == DjiUuids.NOTIFY) assembler.feed(data).forEach(::onFrame)
            }
        }
        if (!ble.connect(linkTimeout, onLinkUp)) return false
        if (!ble.hasCharacteristic(DjiUuids.WRITE) || !ble.hasCharacteristic(DjiUuids.NOTIFY)) {
            throw IOException(t("La cámara no ofrece el servicio de mando de DJI (servicios: ${ble.serviceSummary()})", "The camera does not offer DJI's remote service (services: ${ble.serviceSummary()})"))
        }
        try {
            // La petición de conexión ocupa 51 bytes: con paquetes grandes va entera
            ble.requestMtu(247)
        } catch (e: IOException) {
            log(t("La cámara no acepta paquetes grandes (${e.message}); se parten las tramas", "The camera does not accept large packets (${e.message}); frames will be split"))
        }
        ble.enableNotifications(DjiUuids.NOTIFY)
        return true
    }

    /**
     * Saludo de conexión (docs/protocol_data_segment.md, "Connection Process"):
     * 1. el mando pide conectar; 2. la cámara contesta; 3. la cámara manda su propia petición con el
     * veredicto; 4. el mando la confirma. Después se suscribe al estado.
     */
    suspend fun handshake(firstPairing: Boolean, verifyCode: Int) {
        verify = CompletableDeferred()
        acknowledged = false
        val request = DjiCommands.connectionRequest(
            nextSeq(), identity.deviceId, identity.mac, verifyMode = if (firstPairing) 1 else 0, verifyCode = verifyCode,
        )
        // La cámara puede contestar con una respuesta o pasar directamente a su propia petición
        var reply = send(request, 3.seconds)
        if (reply == null && !verify.isCompleted && !firstPairing) {
            // Una cámara dormida acepta la conexión Bluetooth pero no contesta: se prueba a despertarla
            // por el propio enlace con la orden de modo normal. En la Action 5 Pro no sirve; en otras, se verá.
            log(t("La cámara no contesta al saludo (tramas recibidas: $framesReceived): puede estar dormida. Se le pide que despierte por el enlace", "No response to the handshake (frames received: $framesReceived): the camera may be asleep. Asking it to wake over the link"))
            val woke = send(DjiCommands.powerMode(nextSeq(), sleep = false), 3.seconds)
            if (woke == null) {
                log(t("La cámara no contesta a la orden de despertar por el enlace (tramas recibidas: $framesReceived)", "The camera didn't respond to the wake-up command (frames received: $framesReceived)"))
                // Ni una trama desde que se conectó: dormida. Una cámara despierta contesta al saludo al momento
                if (framesReceived == 0 && !verify.isCompleted) throw CameraAsleepException()
            } else {
                val code = woke.payload.firstOrNull()?.toString() ?: "?"
                log(t("La cámara ha contestado a la orden de despertar (código $code)", "The camera responded to the wake-up command (code $code)"))
                delay(1_500)
                reply = send(
                    DjiCommands.connectionRequest(nextSeq(), identity.deviceId, identity.mac, verifyMode = 0, verifyCode = verifyCode),
                    3.seconds,
                )
            }
        }
        if (reply != null && reply.payload.size >= 5) {
            cameraDeviceId = reply.payload.u32(0)
            val code = reply.payload.u8(4)
            if (code != 0) throw IOException(t("La cámara no admite la petición de conexión (código $code)", "The camera refuses the connection request (code $code)"))
        }
        // Sin historial en la cámara, también una reconexión hace que pregunte en pantalla: se le da tiempo
        val allowed = withTimeoutOrNull(if (firstPairing) 90.seconds else 30.seconds) { verify.await() }
            ?: throw IOException(
                if (firstPairing) t("Nadie aceptó el emparejamiento en la pantalla de la cámara", "Nobody accepted the pairing on the camera's screen") else t("La cámara no terminó el saludo de conexión", "The camera did not finish the connection handshake"),
            )
        if (!allowed) throw DjiRejectedException()
        // Paso 4: sin esta confirmación la cámara repite su petición sin parar
        write(DjiCommands.connectionResponse(cameraRequestSeq, identity.deviceId))
        lastAckAt = SystemClock.elapsedRealtime()
        acknowledged = true
        write(DjiCommands.statusSubscribe(nextSeq()))
    }

    /**
     * Espera el primer estado de la cámara. La suscripción se manda sin acuse: si a los dos segundos y medio
     * no ha llegado nada, se pide otra vez.
     * @return null si en cinco segundos la cámara no ha dicho cómo está
     */
    suspend fun awaitFirstStatus(): DjiStatus? =
        withTimeoutOrNull(2_500) { firstStatus.await() } ?: run {
            write(DjiCommands.statusSubscribe(nextSeq()))
            withTimeoutOrNull(2_500) { firstStatus.await() }
        }

    /** Manda una orden y espera su respuesta; null si no llega en [timeout]. */
    suspend fun send(frame: DjiFrame, timeout: Duration): DjiFrame? {
        val waiter = CompletableDeferred<DjiFrame>()
        pending[frame.seq] = waiter
        waitingCommands.incrementAndGet()
        try {
            // La escritura va dentro del plazo: si el Bluetooth no la confirma, la orden no se queda colgada
            return withTimeoutOrNull(timeout + 2.seconds) {
                write(frame)
                withTimeoutOrNull(timeout) { waiter.await() }
            }
        } finally {
            waitingCommands.decrementAndGet()
            pending.remove(frame.seq)
        }
    }

    private val waitingCommands = java.util.concurrent.atomic.AtomicInteger(0)

    /** Hay una orden esperando salir o esperando respuesta: el GPS le cede el paso. */
    val commandWaiting: Boolean get() = waitingCommands.get() > 0

    /** Manda una trama que no espera respuesta. */
    suspend fun push(frame: DjiFrame) = write(frame)

    /** La manda solo si no hay otra escritura en curso. @return false si el Bluetooth estaba ocupado */
    suspend fun pushIfFree(frame: DjiFrame): Boolean {
        if (!writeMutex.tryLock()) return false
        try {
            ble.write(DjiUuids.WRITE, frame.encode(), noResponse = true)
        } finally {
            writeMutex.unlock()
        }
        return true
    }

    fun nextSeq(): Int = seq.incrementAndGet() and 0xFFFF

    fun close() {
        ble.close()
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    private suspend fun write(frame: DjiFrame) {
        writeMutex.withLock { ble.write(DjiUuids.WRITE, frame.encode(), noResponse = true) }
    }

    private fun onFrame(frame: DjiFrame) {
        framesReceived++
        when {
            // El estado llega por su cuenta y también como respuesta a la suscripción
            frame.isCommand(DjiCommands.SET_CAMERA, DjiCommands.STATUS_PUSH) ->
                DjiStatus.parse(frame.payload)?.let { status ->
                    firstStatus.complete(status)
                    onStatus(status)
                }
            frame.isCommand(DjiCommands.SET_GENERAL, DjiCommands.CONNECTION) && !frame.isResponse -> onCameraRequest(frame)
            frame.isResponse -> pending[frame.seq]?.complete(frame)
        }
    }

    private fun onCameraRequest(frame: DjiFrame) {
        val allowed = DjiCommands.verifyResult(frame) ?: return
        if (frame.payload.size >= 4 && cameraDeviceId == 0L) cameraDeviceId = frame.payload.u32(0)
        cameraRequestSeq = frame.seq
        verify.complete(allowed)
        // La primera la confirma el saludo; aquí solo las repeticiones, y sin atropellarse
        if (!allowed || !acknowledged) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAckAt < 300) return
        lastAckAt = now
        scope?.launch {
            try {
                write(DjiCommands.connectionResponse(frame.seq, identity.deviceId))
            } catch (e: IOException) {
                log(t("No se pudo confirmar la conexión: ${e.message}", "Could not confirm the connection: ${e.message}"))
            }
        }
    }
}

class DjiDriver(private val context: Context, private val camera: PairedCamera) : CameraDriver {
    /**
     * Una DJI solo despierta con el anuncio de su propio mando, que tiene que salir de una dirección Bluetooth
     * pública, y Android no deja elegirla a una app normal. Probado con una Osmo Action 6, dormida desde el
     * campo y por su apagado automático: el Karoo no la despierta. La cámara se enciende con su botón y
     * BiciCAM la encuentra sola.
     */
    override val canWake = false

    /**
     * Conecta un momento, saluda y mira el primer estado. Una cámara dormida se porta de dos maneras, vistas
     * las dos en una Osmo Action 6: dormida por su apagado automático, acepta la conexión y calla; dormida
     * desde el Karoo, contesta al saludo y dice en su estado que duerme. Solo está despierta si contesta y
     * no dice que duerme.
     */
    override suspend fun probeAwake(calm: Boolean, log: (String) -> Unit): Boolean = coroutineScope {
        val device = findDjiCamera(context, camera, 6.seconds, calm = calm, log = log) ?: return@coroutineScope false
        val link = DjiLink(context, device, log = {})
        try {
            if (!link.open(this, 8.seconds, onLinkUp = {})) return@coroutineScope false
            link.handshake(firstPairing = false, verifyCode = Random.nextInt(1000, 10000))
            if (!isPairedModel(link)) return@coroutineScope false
            val first = link.awaitFirstStatus()
            first != null && !first.asleep
        } catch (e: CameraAsleepException) {
            false
        } catch (e: IOException) {
            false
        } finally {
            link.close()
            coroutineContext.cancelChildren()
        }
    }

    @Volatile
    private var link: DjiLink? = null

    /** La cámara ha confirmado en su estado que está grabando (no vale con habérselo pedido). */
    @Volatile
    private var confirmedRecording = false

    @Volatile
    private var lastStatus: DjiStatus? = null

    /** Cuándo llegó el último estado y cuándo se mandó parar: para no fiarse de un "graba" que ya es viejo. */
    @Volatile
    private var lastStatusAt = 0L

    @Volatile
    private var stopSentAt = 0L

    /** Una cámara que dice ser otro modelo que la emparejada no es ella, aunque se anuncie igual. */
    private fun isPairedModel(link: DjiLink): Boolean {
        val expected = camera.modelId ?: return true
        return (link.cameraDeviceId and 0xFFFF).toInt() == expected
    }

    override suspend fun runSession(host: SessionHost, quick: Boolean, calm: Boolean) = coroutineScope {
        // Primero se busca a la cámara y se conecta con lo que devuelve la búsqueda. Conectar a ciegas a la
        // dirección guardada falla cuando Android ya no recuerda de qué tipo es esa dirección, cosa que
        // pasa al rato de emparejar o al reiniciar el Bluetooth (con GoPro no ocurre porque queda vinculada).
        val device = findDjiCamera(context, camera, if (quick) 8.seconds else 30.seconds, calm = calm, log = host::log) ?: throw CameraNotAnsweringException()
        val link = DjiLink(context, device, host::log)
        try {
            var lastAsleep = false
            var lastMode = -1
            link.onStatus = { status ->
                lastStatus = status
                lastStatusAt = SystemClock.elapsedRealtime()
                confirmedRecording = status.recording
                if (status.mode != lastMode) {
                    // El código del modo, apuntado cuando cambia: así se aprenden los que BiciCAM aún no conoce
                    lastMode = status.mode
                    host.log(t("Modo de la cámara: ${status.format} (código ${status.mode})", "Camera mode: ${status.format} (code ${status.mode})"))
                }
                if (status.asleep != lastAsleep) {
                    lastAsleep = status.asleep
                    host.log(if (status.asleep) t("La cámara avisa de que se duerme", "The camera says it is going to sleep") else t("La cámara avisa de que está despierta", "The camera says it is awake"))
                }
                host.onStatus(
                    StatusUpdate(
                        recording = status.recording,
                        recordingSeconds = status.recordSeconds.takeIf { status.recording },
                        // Modo 1: vídeo normal. En los demás (timelapse, cámara lenta...) el contador puede ir a otro ritmo
                        realTimeVideo = status.mode == 0x01,
                        mode = status.cameraMode,
                        batteryPercent = status.batteryPercent.takeIf { it in 0..100 },
                        // Reproduciendo vídeos en su pantalla, la cámara no atiende órdenes de grabación
                        busy = status.playback,
                        asleep = status.asleep,
                        // Estado 0: pantalla apagada; 1: en espera con la pantalla encendida. Grabando no se sabe
                        screenOff = when (status.cameraStatus) {
                            0 -> true
                            1 -> false
                            else -> null
                        },
                        // Viendo fotos o vídeos en la cámara, el dato de la tarjeta llega a cero: se deja el último bueno
                        remainingSeconds = status.remainingSeconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                            // En modo foto ese dato es cero (la cámara enseña gigas, no tiempo): no es una tarjeta llena
                            .takeUnless { status.playback || status.mode == 0x05 },
                        format = status.format,
                    ),
                )
            }
            if (!link.open(this, 12.seconds, host::onLinkUp)) throw IOException(t("la cámara se anuncia pero no acepta la conexión", "the camera is advertising but does not accept the connection"))
            // El código solo se enseña si la cámara ha perdido el emparejamiento y vuelve a preguntar
            link.handshake(firstPairing = false, verifyCode = Random.nextInt(1000, 10000))
            DjiDeviceId.name(link.cameraDeviceId.toInt())?.let { host.log(t("Modelo: $it", "Model: $it")) }
            if (!isPairedModel(link)) {
                host.log(t("El aparato que ha contestado no es del modelo de la cámara emparejada: se corta", "The device that answered is not the paired camera's model: disconnecting"))
                throw IOException(t("no es la cámara emparejada", "not the paired camera"))
            }
            // Dormida desde el Karoo, la cámara contesta al saludo pero no obedece: no se da por conectada,
            // o las órdenes pendientes (grabar) le llegarían dormida y saltarían al encenderla. Tampoco si no
            // dice cómo está: sin su estado no se le manda nada. La petición de estado va sin acuse, así que
            // si se pierde se repite una vez antes de rendirse.
            val asked = SystemClock.elapsedRealtime()
            val first = link.awaitFirstStatus()
            if (first == null) {
                // Que calle no quiere decir que duerma: es un intento fallido y se vuelve a probar
                host.log(t("La cámara contesta al saludo pero no dice cómo está: se vuelve a intentar", "The camera answers the handshake but sends no status: trying again"))
                throw IOException(t("sin estado de la cámara", "no status from the camera"))
            }
            host.log(t("Primer estado de la cámara a los ${(SystemClock.elapsedRealtime() - asked) / 100 / 10.0} s", "First camera status after ${(SystemClock.elapsedRealtime() - asked) / 100 / 10.0} s"))
            if (first.asleep) {
                host.log(t("La cámara contesta, pero dice que está dormida", "The camera answers, but says it is asleep"))
                throw CameraAsleepException()
            }
            this@DjiDriver.link = link
            host.onReady()
            // El estado llegó antes de estar lista: se repite ahora para que se sepa ya si graba
            link.onStatus(first)
            // La posición solo se manda a una cámara que ha saludado, es del modelo emparejado y ha dado su estado
            launch { feedGps(link, host) }
            link.ble.disconnected.await()
            val why = link.ble.disconnectStatus?.let { t("estado $it", "status $it") } ?: t("por BiciCAM", "by BiciCAM")
            host.log(t("Enlace con la cámara cerrado ($why)", "Link to the camera closed ($why)"))
        } finally {
            this@DjiDriver.link = null
            confirmedRecording = false
            lastStatus = null
            lastStatusAt = 0
            link.close()
            coroutineContext.cancelChildren()
        }
    }

    /**
     * Una posición por segundo, que es lo que da el Karoo (el mando de DJI manda diez). La altitud va a cero:
     * la biblioteca del Karoo no la ofrece.
     */
    private suspend fun feedGps(link: DjiLink, host: SessionHost) {
        var announced = false
        var sent = 0
        var failed = 0
        var countedAt = SystemClock.elapsedRealtime()
        // La demo de DJI manda la hora de Pekín (UTC+8), pero las cámaras guardan en el clip «la hora recibida
        // menos una hora», mandes lo que mandes: medido con una Osmo Action 6 el 13/11/2026 (los clips quedaban
        // 7 h por delante de UTC) y, antes, por el proyecto DJI-Remote de rhoenschrat con la Action 5 Pro, la
        // Action 6 y la Osmo 360. Se manda UTC+1 para que en el clip quede la hora UTC, que es la que casa con
        // la ruta del Karoo.
        val cameraClock = TimeZone.getTimeZone("GMT+1")
        while (true) {
            delay(1000)
            if (!AppSettings.gpsToCamera.value) continue
            // Ahorro de batería: en espera no se le manda nada a la cámara; el GPS empieza con la grabación
            // (el primer segundo del clip puede quedar sin posición)
            if (AppSettings.batterySaver.value && !confirmedRecording) continue
            val fix = LocationFeed.current(3_000) ?: continue
            val now = Calendar.getInstance(cameraClock)
            val heading = Math.toRadians(fix.bearing)
            val frame = DjiCommands.gpsPush(
                seq = link.nextSeq(),
                date = now.get(Calendar.YEAR) * 10000 + (now.get(Calendar.MONTH) + 1) * 100 + now.get(Calendar.DAY_OF_MONTH),
                time = now.get(Calendar.HOUR_OF_DAY) * 10000 + now.get(Calendar.MINUTE) * 100 + now.get(Calendar.SECOND),
                longitudeE7 = (fix.longitude * 1e7).toInt(),
                latitudeE7 = (fix.latitude * 1e7).toInt(),
                heightMm = 0,
                northCms = (fix.speedMps * cos(heading) * 100).toFloat(),
                eastCms = (fix.speedMps * sin(heading) * 100).toFloat(),
                downCms = 0f,
                satellites = 10,
            )
            // Una orden de quien va en la bici pasa delante: el GPS de este segundo se salta
            if (link.commandWaiting) continue
            try {
                // Con plazo: una escritura que el Bluetooth no confirma no puede retener detrás a las órdenes
                // Si el Bluetooth está ocupado con otra escritura, el GPS de este segundo se salta: ni espera ni
                // se cancela a medias, que dejaría la respuesta de esa escritura para la orden siguiente
                if (!link.pushIfFree(frame)) continue
                sent++
                failed = 0
            } catch (e: IOException) {
                // El enlace se ha caído: de avisar y reconectar se encarga la sesión
                if (link.ble.disconnected.isCompleted) return
                // Si no, se apunta y se sigue al segundo siguiente: antes el GPS se callaba para siempre sin dejar rastro
                failed++
                if (failed >= 3) {
                    // Tres seguidas sin salir con el enlace abierto: el Bluetooth no está sano. Se cierra y se reconecta
                    host.log(t("El GPS lleva tres posiciones sin salir: se cierra el enlace para reconectar", "Three GPS positions in a row were not sent: closing the link to reconnect"))
                    link.close()
                    return
                }
                if (failed == 1) host.log(t("No ha salido una posición GPS (${e.message}); van $failed seguidas", "A GPS position was not sent (${e.message}); $failed in a row"))
                continue
            }
            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs - countedAt >= 300_000) {
                host.log(t("GPS a la cámara: $sent posiciones en los últimos 5 min", "GPS to the camera: $sent positions in the last 5 min"))
                sent = 0
                countedAt = nowMs
            }
            if (!announced) {
                announced = true
                host.log(t("Pasando a la cámara el GPS del Karoo", "Sending the Karoo's GPS to the camera"))
            }
        }
    }

    /**
     * Viendo fotos o vídeos en su pantalla, la cámara ignora la orden de grabar. Se le pide volver a su modo
     * de grabación; si no sale de ahí, se dice claro en vez de fingir que graba.
     */
    private suspend fun leavePlayback(link: DjiLink) {
        val mode = lastStatus?.mode ?: 0x01
        link.send(DjiCommands.modeSwitch(link.nextSeq(), link.cameraDeviceId, mode), 2.seconds)
        repeat(6) {
            if (lastStatus?.playback != true) return
            delay(400)
        }
        throw CameraProblem(t("En la galería", "In playback"), t("Sal de la galería en la cámara", "Exit playback on the camera"))
    }

    /**
     * La marca de momento destacado de DJI es una pulsación del botón lateral mientras graba. La cámara no
     * contesta a esa pulsación, así que no hay confirmación.
     */
    override suspend fun mark(): Boolean {
        val link = link ?: throw IllegalStateException(t("sin conexión con la cámara", "not connected to the camera"))
        // Sin grabar, ese botón cambia de modo: solo se pulsa cuando la cámara acaba de confirmar que graba
        // (el estado llega dos veces por segundo) y no se le ha mandado parar hace un momento
        val now = SystemClock.elapsedRealtime()
        if (!confirmedRecording || now - lastStatusAt > 900 || now - stopSentAt < 3_000) {
            throw CameraProblem(t("Sin marcador", "No marker"), t("La cámara no confirma REC", "Camera hasn't confirmed REC"))
        }
        link.push(DjiCommands.quickSwitchKey(link.nextSeq()))
        return true
    }

    override suspend fun setRecording(on: Boolean) {
        val link = link ?: throw IllegalStateException(t("sin conexión con la cámara", "not connected to the camera"))
        if (on && lastStatus?.playback == true) leavePlayback(link)
        if (!on) stopSentAt = SystemClock.elapsedRealtime()
        val reply = link.send(DjiCommands.recordControl(link.nextSeq(), link.cameraDeviceId, on), 3.seconds)
        // Sin respuesta no hay error: el estado que empuja la cámara dirá si está grabando
        val code = reply?.payload?.firstOrNull()?.toInt()?.and(0xFF) ?: return
        if (code != 0) throw IllegalStateException(t("la cámara ha rechazado la orden (código $code)", "the camera rejected the command (code $code)"))
    }

    override suspend fun setVideoMode(): Boolean {
        val link = link ?: throw IllegalStateException(t("sin conexión con la cámara", "not connected to the camera"))
        val reply = link.send(DjiCommands.modeSwitch(link.nextSeq(), link.cameraDeviceId, 0x01), 2.seconds)
        val code = reply?.payload?.firstOrNull()?.toInt()?.and(0xFF) ?: return true
        if (code != 0) throw IllegalStateException(t("la cámara no ha querido cambiar de modo (código $code)", "the camera refused to change mode (code $code)"))
        return true
    }

    override suspend fun sleep() {
        val link = link ?: throw IllegalStateException(t("sin conexión con la cámara", "not connected to the camera"))
        val reply = link.send(DjiCommands.powerMode(link.nextSeq(), sleep = true), 3.seconds)
            ?: throw IllegalStateException(t("la cámara no ha contestado a la orden de dormir", "the camera didn't respond to the sleep command"))
        val code = reply.payload.firstOrNull()?.toInt()?.and(0xFF) ?: 0
        if (code != 0) throw IllegalStateException(t("la cámara no se quiere dormir ahora (código $code)", "the camera will not sleep now (code $code)"))
    }
}
