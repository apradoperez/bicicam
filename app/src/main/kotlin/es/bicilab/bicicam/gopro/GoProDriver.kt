package es.bicilab.bicicam.gopro

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import es.bicilab.bicicam.camera.BleClient
import es.bicilab.bicicam.camera.BluetoothOffException
import es.bicilab.bicicam.camera.Brand
import es.bicilab.bicicam.camera.CameraDriver
import es.bicilab.bicicam.camera.CameraNotAnsweringException
import es.bicilab.bicicam.camera.CameraProblem
import es.bicilab.bicicam.camera.FoundCamera
import es.bicilab.bicicam.camera.PairedCamera
import es.bicilab.bicicam.camera.SessionHost
import es.bicilab.bicicam.camera.StatusUpdate
import es.bicilab.bicicam.camera.bluetoothAdapter
import es.bicilab.bicicam.camera.t
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.seconds

/*
 * Cámaras GoPro con Open GoPro (HERO9 Black en adelante). Adaptado de Kamerad, de Horst Tellioglu
 * (licencia MIT). Aún sin probar con una cámara: BiciLAB no tiene una GoPro a mano.
 */

/** Busca cámaras GoPro que se estén anunciando, hasta que se cancele el flujo. */
@SuppressLint("MissingPermission")
fun scanForGoProCameras(context: Context): Flow<FoundCamera> = callbackFlow {
    val scanner = context.bluetoothAdapter().bluetoothLeScanner ?: throw BluetoothOffException()
    val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: t("Cámara GoPro", "GoPro camera")
            trySend(FoundCamera(result.device.address, name))
        }

        override fun onScanFailed(errorCode: Int) {
            close(IOException(t("No se pudo buscar por Bluetooth (error $errorCode)", "Bluetooth scan failed (error $errorCode)")))
        }
    }
    val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(GoProUuids.ADVERTISED_SERVICE)).build()
    val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
    scanner.startScan(listOf(filter), settings, callback)
    awaitClose { scanner.stopScan(callback) }
}

/**
 * Vincula el Karoo con la cámara (el emparejamiento Bluetooth de Android, con enlace cifrado). La cámara
 * tiene que estar en modo de emparejar.
 */
@SuppressLint("MissingPermission")
private suspend fun bondWithCamera(context: Context, address: String) {
    val device = context.bluetoothAdapter().getRemoteDevice(address)
    if (device.bondState == BluetoothDevice.BOND_BONDED) return
    val bondStates = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                @Suppress("DEPRECATION")
                val changed = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (changed?.address == address) {
                    trySend(intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE))
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        if (!device.createBond()) close(IOException(t("No se pudo empezar a emparejar", "Pairing could not be started")))
        awaitClose { context.unregisterReceiver(receiver) }
    }
    val result = withTimeoutOrNull(60.seconds) {
        bondStates.first { it == BluetoothDevice.BOND_BONDED || it == BluetoothDevice.BOND_NONE }
    } ?: throw IOException(t("La cámara no terminó de emparejar", "The camera did not finish pairing"))
    if (result != BluetoothDevice.BOND_BONDED) throw IOException(t("La cámara no ha aceptado el emparejamiento", "The camera did not accept the pairing"))
}

/** Una conexión Open GoPro: enlace, respuestas juntadas y órdenes con su contestación. */
private class GoProLink(context: Context, address: String, autoConnect: Boolean) {
    val ble = BleClient(context, address, autoConnect)
    private val commandResponses = MutableSharedFlow<TlvResponse>(extraBufferCapacity = 16)
    private val commandMutex = Mutex()
    var onQuery: (TlvResponse) -> Unit = {}

    /** @return false si la cámara no contestó en [linkTimeout] */
    suspend fun open(scope: kotlinx.coroutines.CoroutineScope, linkTimeout: kotlin.time.Duration, onLinkUp: () -> Unit): Boolean {
        val commandPackets = PacketAccumulator()
        val queryPackets = PacketAccumulator()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            ble.notifications.collect { (uuid, data) ->
                when (uuid) {
                    GoProUuids.COMMAND_RESPONSE -> commandPackets.accept(data)?.let(TlvResponse::parse)?.let { commandResponses.emit(it) }
                    GoProUuids.QUERY_RESPONSE -> queryPackets.accept(data)?.let(TlvResponse::parse)?.let { onQuery(it) }
                }
            }
        }
        if (!ble.connect(linkTimeout, onLinkUp)) return false
        if (!ble.hasCharacteristic(GoProUuids.COMMAND)) {
            throw IOException(t("El aparato no ofrece el servicio de mando de GoPro (servicios: ${ble.serviceSummary()})", "The device does not offer GoPro's control service (services: ${ble.serviceSummary()})"))
        }
        ble.enableNotifications(GoProUuids.COMMAND_RESPONSE)
        ble.enableNotifications(GoProUuids.SETTING_RESPONSE)
        ble.enableNotifications(GoProUuids.QUERY_RESPONSE)
        return true
    }

    /**
     * Escribe una orden y espera la respuesta de la cámara a [commandId].
     * @throws IllegalStateException si la cámara la rechaza
     * @throws IOException si no contesta
     */
    suspend fun command(command: ByteArray, commandId: Int): TlvResponse = commandMutex.withLock {
        coroutineScope {
            val response = async(start = CoroutineStart.UNDISPATCHED) { commandResponses.first { it.id == commandId } }
            try {
                ble.write(GoProUuids.COMMAND, command)
                val result = withTimeoutOrNull(5.seconds) { response.await() }
                    ?: throw IOException(t("la cámara no contesta a la orden", "no response from the camera to the command"))
                if (result.status != 0) throw IllegalStateException(t("la cámara ha rechazado la orden (código ${result.status})", "the camera rejected the command (code ${result.status})"))
                result
            } finally {
                response.cancel()
            }
        }
    }

    /** Pregunta el modelo hasta que la cámara acepta órdenes: recién despierta, las rechaza un momento. */
    suspend fun awaitReady(log: (String) -> Unit): String? {
        val started = SystemClock.elapsedRealtime()
        var attempts = 0
        while (true) {
            attempts++
            try {
                return parseModelName(command(GoProCommands.GET_HARDWARE_INFO_COMMAND, GoProCommands.GET_HARDWARE_INFO).payload)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempts == 1) log(t("La cámara aún no acepta órdenes (${e.message})", "The camera does not accept commands yet (${e.message})"))
            }
            if (ble.disconnected.isCompleted) throw IOException(t("la cámara ha cortado el enlace", "the camera closed the link"))
            if (SystemClock.elapsedRealtime() - started > 20_000) {
                throw IOException(t("la cámara no llega a aceptar órdenes", "the camera never accepted commands"))
            }
            delay(500)
        }
    }

    fun close() = ble.close()
}

/**
 * Primer emparejamiento: vincula el Karoo con la cámara y le pregunta el modelo.
 * La cámara tiene que estar en su pantalla de emparejar (Conexiones, Conectar dispositivo).
 */
suspend fun pairGoProCamera(context: Context, found: FoundCamera, log: (String) -> Unit): PairedCamera = coroutineScope {
    bondWithCamera(context, found.address)
    val link = GoProLink(context, found.address, autoConnect = false)
    try {
        if (!link.open(this, 15.seconds, onLinkUp = {})) throw IOException(t("La cámara no contesta. Acércala y comprueba que está encendida.", "The camera does not answer. Bring it closer and check that it is on."))
        val model = link.awaitReady(log)
        log(t("Modelo: ${model ?: "desconocido"}", "Model: ${model ?: "unknown"}"))
        PairedCamera(Brand.GOPRO, found.address, model?.let { "$it (${found.name})" } ?: found.name, advertName = found.name)
    } finally {
        link.close()
        coroutineContext.cancelChildren()
    }
}

class GoProDriver(private val context: Context, private val camera: PairedCamera) : CameraDriver {
    /** El modelo va en el nombre guardado al emparejar, por ejemplo "HERO12 Black (GoPro 1234)". */
    override val canWake = canWakeOverBle(camera.name)

    @Volatile
    private var link: GoProLink? = null

    /** La cámara ha confirmado en su estado que está grabando. */
    @Volatile
    private var encoding = false

    override suspend fun runSession(host: SessionHost, quick: Boolean, calm: Boolean) = coroutineScope {
        if (!context.bluetoothAdapter().isEnabled) throw BluetoothOffException()
        // Vinculada, Android recuerda la dirección: el intento corto va directo y el largo espera en segundo plano
        val link = GoProLink(context, camera.address, autoConnect = !quick)
        try {
            link.onQuery = { response -> onQueryResponse(response, host) }
            if (!link.open(this, if (quick) 8.seconds else 30.seconds, host::onLinkUp)) throw CameraNotAnsweringException()
            val model = link.awaitReady(host::log)
            host.log(t("Modelo: ${model ?: "desconocido"}", "Model: ${model ?: "unknown"}"))
            this@GoProDriver.link = link
            host.onReady()
            link.ble.write(
                GoProUuids.QUERY,
                GoProCommands.registerStatusUpdates(
                    GoProStatus.BUSY,
                    GoProStatus.ENCODING,
                    GoProStatus.VIDEO_DURATION,
                    GoProStatus.REMAINING_VIDEO_TIME,
                    GoProStatus.BATTERY_PERCENT,
                ),
            )
            launch {
                while (true) {
                    delay(3.seconds)
                    try {
                        link.ble.write(GoProUuids.SETTING, GoProCommands.KEEP_ALIVE)
                    } catch (e: IOException) {
                        // El enlace se ha caído: de avisar y reconectar se encarga la sesión
                        return@launch
                    }
                }
            }
            link.ble.disconnected.await()
            val why = link.ble.disconnectStatus?.let { t("estado $it", "status $it") } ?: t("por BiciCAM", "by BiciCAM")
            host.log(t("Enlace con la cámara cerrado ($why)", "Link to the camera closed ($why)"))
        } finally {
            this@GoProDriver.link = null
            encoding = false
            link.close()
            coroutineContext.cancelChildren()
        }
    }

    private fun onQueryResponse(response: TlvResponse, host: SessionHost) {
        if (response.status != 0) return
        if (response.id != GoProCommands.QUERY_GET_STATUS && response.id != GoProCommands.QUERY_REGISTER_STATUS && response.id != GoProCommands.QUERY_STATUS_PUSH) return
        val values = response.values()
        val recording = values[GoProStatus.ENCODING]?.let { it.toUnsignedInt() != 0L }
        if (recording != null) encoding = recording
        host.onStatus(
            StatusUpdate(
                recording = recording,
                // Parada, la cámara manda aquí valores sin sentido: solo valen mientras graba y si son creíbles
                recordingSeconds = values[GoProStatus.VIDEO_DURATION]?.toUnsignedInt()?.takeIf { encoding && it < 24 * 3600 }?.toInt(),
                batteryPercent = values[GoProStatus.BATTERY_PERCENT]?.toUnsignedInt()?.toInt()?.takeIf { it in 0..100 },
                busy = values[GoProStatus.BUSY]?.let { it.toUnsignedInt() != 0L },
                remainingSeconds = values[GoProStatus.REMAINING_VIDEO_TIME]?.toUnsignedInt()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
            ),
        )
    }

    private fun link(): GoProLink = link ?: throw IllegalStateException(t("sin conexión con la cámara", "not connected to the camera"))

    override suspend fun setRecording(on: Boolean) {
        link().command(GoProCommands.shutter(on), GoProCommands.SET_SHUTTER)
    }

    /** La marca HiLight solo existe dentro de un vídeo: sin que la cámara confirme que graba, no se manda. */
    override suspend fun mark(): Boolean {
        val link = link()
        if (!encoding) throw CameraProblem(t("Sin marcador", "No marker"), t("La cámara no confirma REC", "Camera hasn't confirmed REC"))
        link.command(GoProCommands.HILIGHT_COMMAND, GoProCommands.TAG_HILIGHT)
        return true
    }

    /** Recién parada una grabación, la cámara aún la está guardando y rechaza dormirse un momento. */
    override suspend fun sleep() {
        val link = link()
        repeat(6) { attempt ->
            try {
                link.command(GoProCommands.SLEEP_COMMAND, GoProCommands.SLEEP)
                return
            } catch (e: IllegalStateException) {
                if (attempt == 5) throw e
                delay(1.seconds)
            }
        }
    }
}
