package es.bicilab.bicicam.camera

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/** UUID completo de uno de 16 bits, por ejemplo "fff4". */
fun shortUuid(hex: String): UUID = UUID.fromString("0000$hex-0000-1000-8000-00805f9b34fb")

fun Context.bluetoothAdapter(): BluetoothAdapter =
    getSystemService(BluetoothManager::class.java)?.adapter ?: throw IOException(t("Este aparato no tiene Bluetooth", "This device has no Bluetooth"))

fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

/**
 * Android solo deja empezar cinco búsquedas Bluetooth cada 30 segundos; a la sexta deja de buscar sin avisar,
 * y BiciCAM diría «no se oye a la cámara» con la cámara delante. Con una cámara que corta la conexión nada
 * más aceptarla, las búsquedas de fondo podían encadenarse más deprisa que eso: aquí se espacian.
 */
object ScanGate {
    private const val MIN_GAP_MS = 6_500L
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private var last = 0L

    /** Espera, si hace falta, a que haya pasado el tiempo mínimo desde la búsqueda anterior. */
    suspend fun await() {
        mutex.lock()
        try {
            val wait = scanWaitMs(last, android.os.SystemClock.elapsedRealtime(), MIN_GAP_MS)
            if (wait > 0) kotlinx.coroutines.delay(wait)
            last = android.os.SystemClock.elapsedRealtime()
        } finally {
            mutex.unlock()
        }
    }
}

/** Lo que hay que esperar antes de empezar otra búsqueda. */
internal fun scanWaitMs(last: Long, now: Long, minGap: Long): Long = if (last == 0L) 0L else (last + minGap - now).coerceAtLeast(0L)

/**
 * Escucha durante [duration] si la cámara se está anunciando (es decir, si se le podría conectar).
 * @return su señal en dBm, o null si no se la ha oído
 */
@SuppressLint("MissingPermission")
suspend fun listenForCamera(context: Context, address: String, duration: Duration): Int? {
    ScanGate.await()
    return listenOnce(context, address, duration)
}

@SuppressLint("MissingPermission")
private suspend fun listenOnce(context: Context, address: String, duration: Duration): Int? = withTimeoutOrNull(duration) {
    callbackFlow {
        val scanner = context.bluetoothAdapter().bluetoothLeScanner ?: throw BluetoothOffException()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(result.rssi)
            }

            override fun onScanFailed(errorCode: Int) {
                close(IOException(t("No se pudo buscar por Bluetooth (error $errorCode)", "Bluetooth scan failed (error $errorCode)")))
            }
        }
        val filter = ScanFilter.Builder().setDeviceAddress(address).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, callback)
        awaitClose { scanner.stopScan(callback) }
    }.first()
}

/**
 * Cliente GATT mínimo: una conexión, con las operaciones en fila y esperadas de una en una.
 * Se crea una instancia por intento de conexión y se cierra con [close] al terminar.
 *
 * @param autoConnect false: intento directo, rápido si la cámara se está anunciando, pero que se rinde;
 *   true: la conexión en segundo plano de Android, paciente pero bastante más lenta
 */
@SuppressLint("MissingPermission")
class BleClient(private val context: Context, private val device: BluetoothDevice, private val autoConnect: Boolean = true) {
    constructor(context: Context, address: String, autoConnect: Boolean = true) :
        this(context, context.bluetoothAdapter().getRemoteDevice(address), autoConnect)

    private var gatt: BluetoothGatt? = null
    private val opMutex = Mutex()

    @Volatile
    private var pending: CompletableDeferred<Pair<Int, ByteArray?>>? = null
    private val connected = CompletableDeferred<Unit>()

    /** Se completa cuando la conexión se pierde o se cierra. */
    val disconnected = CompletableDeferred<Unit>()

    /** Código de Android de la conexión perdida (8: se agotó el enlace, 19: la cortó la cámara); null si la cerramos nosotros. */
    @Volatile
    var disconnectStatus: Int? = null
        private set

    /** Bytes que caben en un paquete (MTU negociado). */
    @Volatile
    var mtu = 23
        private set

    @Volatile
    private var rssi = 0

    private val _notifications = MutableSharedFlow<Pair<UUID, ByteArray>>(extraBufferCapacity = 128)
    val notifications: SharedFlow<Pair<UUID, ByteArray>> = _notifications.asSharedFlow()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> connected.complete(Unit)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (!disconnected.isCompleted) disconnectStatus = status
                    val error = IOException(t("Desconectada (estado $status)", "Disconnected (status $status)"))
                    connected.completeExceptionally(error)
                    pending?.completeExceptionally(error)
                    disconnected.complete(Unit)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            pending?.complete(status to null)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this@BleClient.mtu = mtu
            pending?.complete(status to null)
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            this@BleClient.rssi = rssi
            pending?.complete(status to null)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            pending?.complete(status to characteristic.value?.copyOf())
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(status to null)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            pending?.complete(status to null)
        }

        // En Android 13 y posteriores se llama además de a la variante nueva
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            characteristic.value?.let { _notifications.tryEmit(characteristic.uuid to it.copyOf()) }
        }
    }

    /**
     * Conecta (esperando a que la cámara esté a tiro) y descubre sus servicios.
     * @param linkTimeout tiempo que se espera a que la cámara conteste (null: sin límite)
     * @param onLinkUp se llama en cuanto hay enlace, es decir, cuando la cámara ha contestado
     * @return false si la cámara no contestó en [linkTimeout]
     */
    suspend fun connect(linkTimeout: Duration? = null, onLinkUp: () -> Unit = {}): Boolean {
        gatt = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
        val answered = if (linkTimeout == null) {
            connected.await()
            true
        } else {
            withTimeoutOrNull(linkTimeout) {
                connected.await()
                true
            } ?: false
        }
        if (!answered) return false
        onLinkUp()
        op(t("Descubrir servicios", "Service discovery")) { it.discoverServices() }
        return true
    }

    /** Pide paquetes más grandes; si la cámara no quiere, se queda el tamaño que hubiera. */
    suspend fun requestMtu(size: Int) {
        op(t("Negociar el tamaño de paquete", "Packet size negotiation")) { it.requestMtu(size) }
    }

    fun hasService(uuid: UUID): Boolean = gatt?.getService(uuid) != null

    fun hasCharacteristic(uuid: UUID): Boolean = gatt?.services?.any { it.getCharacteristic(uuid) != null } == true

    /** Lista corta de los servicios que expone la cámara, para el registro de eventos. */
    fun serviceSummary(): String =
        gatt?.services?.joinToString(", ") { it.uuid.toString().substring(4, 8) } ?: ""

    /**
     * Escribe [value] en la característica, partido en trozos que quepan en un paquete.
     * @param noResponse sin acuse de recibo por paquete (lo que usan los mandos de DJI); si la característica
     *   no lo admite, se escribe con acuse
     */
    suspend fun write(uuid: UUID, value: ByteArray, noResponse: Boolean = false) {
        val chunkSize = (mtu - 3).coerceAtLeast(20)
        var offset = 0
        while (offset < value.size) {
            val chunk = value.copyOfRange(offset, minOf(value.size, offset + chunkSize))
            op(t("Escribir en ${uuid.toString().substring(4, 8)}", "Write to ${uuid.toString().substring(4, 8)}")) { gatt ->
                val characteristic = gatt.characteristic(uuid)
                val canSkipResponse =
                    characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
                characteristic.writeType = if (noResponse && canSkipResponse) {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
                @Suppress("DEPRECATION")
                characteristic.value = chunk
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
            offset += chunk.size
        }
    }

    suspend fun read(uuid: UUID): ByteArray? = op(t("Leer ${uuid.toString().substring(4, 8)}", "Read ${uuid.toString().substring(4, 8)}")) { gatt ->
        gatt.readCharacteristic(gatt.characteristic(uuid))
    }

    suspend fun enableNotifications(uuid: UUID) {
        op(t("Activar avisos de ${uuid.toString().substring(4, 8)}", "Enable notifications on ${uuid.toString().substring(4, 8)}")) { gatt ->
            val characteristic = gatt.characteristic(uuid)
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
                ?: throw IOException(t("La característica $uuid no admite avisos", "Characteristic $uuid has no notifications"))
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    /** Señal de la conexión en dBm (hacia -50: cerca; por debajo de -90: a punto de caerse). */
    suspend fun readRssi(): Int {
        op(t("Leer la señal", "Read signal strength")) { it.readRemoteRssi() }
        return rssi
    }

    fun close() {
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        pending?.completeExceptionally(IOException(t("Cerrada", "Closed")))
        disconnected.complete(Unit)
    }

    private fun BluetoothGatt.characteristic(uuid: UUID): BluetoothGattCharacteristic =
        services.firstNotNullOfOrNull { service -> service.getCharacteristic(uuid) }
            ?: throw IOException(t("La cámara no tiene la característica $uuid", "The camera has no characteristic $uuid"))

    private suspend fun op(name: String, start: (BluetoothGatt) -> Boolean): ByteArray? = opMutex.withLock {
        val gatt = gatt ?: throw IOException(t("Sin conexión", "Not connected"))
        if (disconnected.isCompleted) throw IOException(t("Desconectada", "Disconnected"))
        val result = CompletableDeferred<Pair<Int, ByteArray?>>()
        pending = result
        try {
            if (!start(gatt)) throw IOException(t("$name: no se pudo empezar", "$name: could not be started"))
            // Un tiempo agotado es un fallo que hay que ver, no una cancelación que se pierde en silencio
            val (status, value) = withTimeoutOrNull(10.seconds) { result.await() }
                ?: throw IOException(t("$name: la cámara no responde", "$name: no response from the camera"))
            if (status != BluetoothGatt.GATT_SUCCESS) throw IOException(t("$name: falló (estado $status)", "$name: failed (status $status)"))
            value
        } finally {
            pending = null
        }
    }
}
