package es.bicilab.bicicam.insta360

import es.bicilab.bicicam.camera.t
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import es.bicilab.bicicam.camera.CLIENT_CHARACTERISTIC_CONFIG
import es.bicilab.bicicam.camera.bluetoothAdapter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * El Karoo haciendo de mando GPS de Insta360: se anuncia por Bluetooth, ofrece los mismos servicios que el
 * mando original y espera a que la cámara se conecte. Una instancia por intento; se cierra con [close].
 *
 * @param serial número de serie de la cámara (seis caracteres). Con él, el anuncio es la baliza que la
 *   despierta; sin él (primer emparejamiento), el anuncio general con los servicios del mando.
 * @param pairedAddress dirección de la cámara emparejada; null solo en el primer emparejamiento. Con ella,
 *   ningún otro aparato pasa por la cámara.
 * @param calm anunciarse gastando menos batería (la cámara lleva mucho sin aparecer)
 */
@SuppressLint("MissingPermission")
internal class InstaRemote(
    private val context: Context,
    private val serial: String?,
    private val pairedAddress: String?,
    private val calm: Boolean = false,
    private val log: (String) -> Unit,
) {
    private val adapter = context.bluetoothAdapter()
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var toCamera: BluetoothGattCharacteristic? = null

    private val pendingServices = ArrayDeque<BluetoothGattService>()
    private val servicesReady = CompletableDeferred<Unit>()

    /**
     * La cámara: el aparato emparejado que se suscribe a los avisos del mando o le escribe una trama. El
     * servidor es visible para cualquiera que esté cerca, así que solo se acepta a la cámara emparejada, por
     * su dirección. En el primer emparejamiento vale el primero
     * que llegue, y la app pide confirmarlo antes de guardarlo.
     */
    val camera = CompletableDeferred<BluetoothDevice>()

    /** Aparatos que han activado los avisos antes de saberse si son la cámara. */
    private val subscribers = ConcurrentHashMap.newKeySet<String>()
    private val ignored = ConcurrentHashMap.newKeySet<String>()

    /** La cámara ha activado los avisos: ya se le pueden mandar botones. */
    val subscribed = CompletableDeferred<Unit>()

    /** Se completa con el código de Android cuando la cámara se desconecta. */
    val disconnected = CompletableDeferred<Int>()

    private val _frames = MutableSharedFlow<InstaCameraFrame>(extraBufferCapacity = 64)

    /** Tramas que escribe la cámara (número de serie, textos de pantalla, estado). */
    val frames: SharedFlow<InstaCameraFrame> = _frames.asSharedFlow()

    @Volatile
    private var cameraDevice: BluetoothDevice? = null
    private val mtus = ConcurrentHashMap<String, Int>()

    /** Bytes por paquete negociados por la cámara. */
    val mtu: Int get() = cameraDevice?.let { mtus[it.address] ?: lastMtus[it.address] } ?: 23

    private var buttonSn = 0
    private var linkLogs = 0
    private val notifyMutex = Mutex()

    @Volatile
    private var sent: CompletableDeferred<Int>? = null

    /** Abre los servicios del mando y empieza a anunciarse. */
    suspend fun start() {
        val advertiser = adapter.bluetoothLeAdvertiser
            ?: throw IOException(t("Este Karoo no puede anunciarse por Bluetooth, así que no puede hacer de mando de Insta360", "This Karoo cannot advertise over Bluetooth, so it cannot act as an Insta360 remote"))
        val manager = context.getSystemService(BluetoothManager::class.java)
        val server = manager?.openGattServer(context, callback) ?: throw IOException(t("El Karoo no ha podido abrir los servicios del mando", "The Karoo could not open the remote's services"))
        this.server = server
        this.advertiser = advertiser
        synchronized(pendingServices) {
            pendingServices += primaryService()
            pendingServices += secondaryService()
        }
        // Android admite los servicios de uno en uno: el siguiente se añade al confirmarse el anterior
        server.addService(synchronized(pendingServices) { pendingServices.removeFirst() })
        withTimeoutOrNull(3.seconds) { servicesReady.await() }
            ?: log(t("El Karoo no ha confirmado los servicios del mando; se sigue igualmente", "The Karoo did not confirm the remote's services; carrying on"))
        useRemoteName()
        advertise(advertiser, withName = true)
    }

    private suspend fun advertise(advertiser: BluetoothLeAdvertiser, withName: Boolean) {
        // Anuncio conectable clásico cada 100 ms: la cámara dormida escucha a ratos y con menos se lo pierde
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(if (calm) AdvertiseSettings.ADVERTISE_MODE_BALANCED else AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(if (calm) AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM else AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = if (serial != null) {
            // Baliza con el número de serie: llena el anuncio entero, así que el nombre va en la respuesta
            AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addManufacturerData(InstaFrames.BEACON_COMPANY_ID, InstaFrames.beacon(serial))
                .build()
        } else {
            AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(true)
                .addServiceUuid(ParcelUuid(InstaUuids.SERVICE))
                .addServiceUuid(ParcelUuid(InstaUuids.SECONDARY_SERVICE))
                .build()
        }
        val started = CompletableDeferred<Int>()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                started.complete(0)
            }

            override fun onStartFailure(errorCode: Int) {
                started.complete(errorCode)
            }
        }
        advertiseCallback = callback
        if (withName) {
            advertiser.startAdvertising(settings, data, AdvertiseData.Builder().setIncludeDeviceName(true).build(), callback)
        } else {
            advertiser.startAdvertising(settings, data, callback)
        }
        val error = withTimeoutOrNull(3.seconds) { started.await() } ?: 0
        when {
            error == 0 -> log(if (serial != null) t("Anunciándose como mando con la baliza de la cámara $serial", "Advertising as a remote with the beacon of camera $serial") else t("Anunciándose como mando nuevo", "Advertising as a new remote"))
            // El nombre no cabía en la respuesta: se anuncia sin él
            error == AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE && withName -> {
                stopAdvertising()
                advertise(advertiser, withName = false)
            }
            else -> throw IOException(t("El Karoo no ha podido anunciarse como mando (error $error)", "The Karoo could not advertise as a remote (error $error)"))
        }
    }

    fun stopAdvertising() {
        val callback = advertiseCallback ?: return
        advertiseCallback = null
        try {
            advertiser?.stopAdvertising(callback)
        } catch (_: Exception) {
            // el Bluetooth se apagó entretanto
        }
    }

    /**
     * Pulsa un botón del mando.
     * @return false si la cámara no ha recibido la pulsación
     */
    suspend fun button(button: Int, state: Int): Boolean {
        val sn = buttonSn
        buttonSn = (buttonSn + 2) and 0xFF
        return notify(InstaFrames.button(sn, button, state))
    }

    /**
     * Manda una posición (o la frase vacía si [fix] es null).
     * @return false si no cabe en un paquete con el tamaño que ha negociado la cámara: partida no la entiende
     */
    suspend fun gps(fix: InstaFix?): Boolean {
        val frame = InstaFrames.gps(fix, System.currentTimeMillis())
        if (frame.size > mtu - 3) return false
        return notify(frame)
    }

    private suspend fun notify(value: ByteArray): Boolean = notifyMutex.withLock {
        val device = cameraDevice ?: return false
        val characteristic = toCamera ?: return false
        val server = server ?: return false
        val done = CompletableDeferred<Int>()
        sent = done
        @Suppress("DEPRECATION")
        characteristic.value = value
        @Suppress("DEPRECATION")
        val accepted = server.notifyCharacteristicChanged(device, characteristic, false)
        if (!accepted) return false
        // Android solo admite el siguiente aviso cuando ha salido el anterior
        withTimeoutOrNull(500.milliseconds) { done.await() }
        true
    }

    fun close() {
        stopAdvertising()
        val server = server
        this.server = null
        cameraDevice?.let { device ->
            try {
                server?.cancelConnection(device)
            } catch (_: Exception) {
                // ya no estaba conectada
            }
        }
        try {
            server?.close()
        } catch (_: Exception) {
            // el Bluetooth se apagó entretanto
        }
        if (!disconnected.isCompleted) disconnected.complete(0)
    }

    /**
     * La cámara reconoce al mando por su nombre Bluetooth, y Android solo sabe anunciar el nombre del propio
     * aparato: mientras busca a la cámara o está conectado a ella, el Karoo se llama como el mando original.
     * Recupera su nombre con [restoreName] cuando deja de buscarla.
     */
    private suspend fun useRemoteName() {
        try {
            val current = adapter.name
            if (current == InstaUuids.REMOTE_NAME) return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.contains(KEY_ORIGINAL_NAME) && current != null) prefs.edit().putString(KEY_ORIGINAL_NAME, current).apply()
            adapter.name = InstaUuids.REMOTE_NAME
            // El cambio no es inmediato
            repeat(15) {
                if (adapter.name == InstaUuids.REMOTE_NAME) return
                delay(100)
            }
            log(t("El Karoo no ha cambiado su nombre Bluetooth; la cámara puede no reconocerlo como mando", "The Karoo did not change its Bluetooth name; the camera may not recognise it as a remote"))
        } catch (e: SecurityException) {
            log(t("Sin permiso para cambiar el nombre Bluetooth: ${e.message}", "No permission to change the Bluetooth name: ${e.message}"))
        }
    }

    /** ¿Puede este aparato ser la cámara? En el primer emparejamiento, cualquiera; después, solo la emparejada. */
    private fun mayBeCamera(device: BluetoothDevice): Boolean = when {
        pairedAddress == null -> true
        // Solo la dirección emparejada. El número de serie no vale como prueba: es justo lo que el Karoo
        // emite en su baliza, así que cualquiera que la escuche lo conoce.
        else -> device.address.equals(pairedAddress, ignoreCase = true)
    }

    private fun identify(device: BluetoothDevice) {
        if (cameraDevice != null) return
        cameraDevice = device
        if (camera.complete(device)) log(t("Se ha conectado la cámara (dirección terminada en ${device.address.takeLast(5)})", "The camera connected (address ending in ${device.address.takeLast(5)})"))
    }

    private fun ignore(device: BluetoothDevice) {
        // Sin nombre ni dirección: de los aparatos de otros no se apunta nada
        if (ignored.add(device.address) && ignored.size == 1) log(t("Se ha ignorado un aparato que no es la cámara emparejada. Si has restablecido o cambiado la cámara, vuelve a emparejarla en Ajustes", "Ignored a device that is not the paired camera. If you have reset or swapped the camera, pair it again in Settings"))
    }

    private fun isCamera(device: BluetoothDevice) = cameraDevice?.address == device.address

    private fun respond(device: BluetoothDevice, requestId: Int, offset: Int, value: ByteArray?) {
        try {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        } catch (_: Exception) {
            // la conexión se cayó entretanto
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            val next = synchronized(pendingServices) { pendingServices.removeFirstOrNull() }
            if (next != null) server?.addService(next) else servicesReady.complete(Unit)
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            // Diagnóstico de la reconexión: se apunta cada enlace que llega, sin datos de aparatos ajenos
            if (linkLogs < 12) {
                linkLogs++
                val mine = pairedAddress != null && device.address.equals(pairedAddress, ignoreCase = true)
                val who = if (mine) t("la cámara emparejada", "the paired camera") else if (pairedAddress == null) t("un aparato", "a device") else t("otro aparato", "another device")
                val bonded = try { device.bondState == BluetoothDevice.BOND_BONDED } catch (_: SecurityException) { false }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    log(t("Enlace entrante de $who (vinculada: ${if (bonded) "sí" else "no"})", "Incoming link from $who (bonded: ${if (bonded) "yes" else "no"})"))
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    log(t("Enlace cerrado con $who (estado $status)", "Link closed with $who (status $status)"))
                }
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                mtus.remove(device.address)
                lastMtus.remove(device.address)
                subscribers.remove(device.address)
                if (cameraDevice?.address == device.address) disconnected.complete(status)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            mtus[device.address] = mtu
            // Se recuerda entre sesiones: si se aprovecha un enlace que ya estaba abierto, este aviso no se repite
            lastMtus[device.address] = mtu
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (characteristic.uuid == InstaUuids.FROM_CAMERA && value != null) {
                InstaCameraFrame.parse(value)?.let { frame ->
                    if (cameraDevice == null && mayBeCamera(device)) {
                        identify(device)
                        if (device.address in subscribers) {
                            buttonSn = 0
                            subscribed.complete(Unit)
                        } else if (pairedAddress != null) {
                            // La cámara emparejada sigue enlazada de antes (pasa nada más emparejar: ella dice
                            // «Conectado»): ya activó los avisos en ese enlace y no lo repite. Visto con una
                            // Ace Pro 2 el 13/11/2026. Se aprovecha el enlace en vez de esperar una suscripción
                            // que no va a llegar; cortarlo obligaría a la cámara a encontrar otra vez al Karoo.
                            log(t("La cámara seguía enlazada de antes: se aprovecha ese enlace", "The camera was still linked from before: reusing that link"))
                            buttonSn = 0
                            subscribed.complete(Unit)
                        }
                    }
                    // Las tramas de cualquier otro aparato se tiran: no pueden cambiar el estado ni el registro
                    if (isCamera(device)) _frames.tryEmit(frame) else ignore(device)
                }
            }
            if (responseNeeded) respond(device, requestId, offset, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val enables = value != null && value.isNotEmpty() && (value[0].toInt() and 0x03) != 0
            if (descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG && descriptor.characteristic?.uuid == InstaUuids.TO_CAMERA && enables) {
                subscribers.add(device.address)
                if (cameraDevice == null && mayBeCamera(device)) identify(device)
                if (isCamera(device)) {
                    // El contador de pulsaciones empieza de cero en cada conexión
                    buttonSn = 0
                    subscribed.complete(Unit)
                } else {
                    ignore(device)
                }
            }
            if (responseNeeded) respond(device, requestId, offset, value)
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            // En algunas versiones de Android el valor guardado llega vacío: se contesta según la característica
            val value = when (characteristic.uuid) {
                InstaUuids.INFO -> InstaUuids.INFO_VALUE
                else -> InstaUuids.SECONDARY_CHARACTERISTICS.firstOrNull { it.first == characteristic.uuid }?.second ?: byteArrayOf(0x00)
            }
            respond(device, requestId, offset, if (offset in 1..value.size) value.copyOfRange(offset, value.size) else value)
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            respond(device, requestId, offset, byteArrayOf(if (subscribed.isCompleted) 0x01 else 0x00, 0x00))
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            respond(device, requestId, 0, null)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            sent?.complete(status)
        }
    }

    private fun primaryService(): BluetoothGattService {
        val service = BluetoothGattService(InstaUuids.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                InstaUuids.FROM_CAMERA,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            ),
        )
        val notify = BluetoothGattCharacteristic(
            InstaUuids.TO_CAMERA,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        notify.addDescriptor(
            BluetoothGattDescriptor(
                CLIENT_CHARACTERISTIC_CONFIG,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        service.addCharacteristic(notify)
        toCamera = notify
        service.addCharacteristic(
            BluetoothGattCharacteristic(InstaUuids.INFO, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ),
        )
        return service
    }

    private fun secondaryService(): BluetoothGattService {
        val service = BluetoothGattService(InstaUuids.SECONDARY_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        InstaUuids.SECONDARY_CHARACTERISTICS.forEach { (uuid, readValue) ->
            service.addCharacteristic(
                if (readValue != null) {
                    BluetoothGattCharacteristic(uuid, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ)
                } else {
                    BluetoothGattCharacteristic(uuid, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE)
                },
            )
        }
        return service
    }

    companion object {
        /** Tamaño de paquete negociado por cada cámara mientras dura su enlace, aunque cambie la sesión. */
        private val lastMtus = ConcurrentHashMap<String, Int>()

        private const val PREFS = "insta360"
        private const val KEY_ORIGINAL_NAME = "original_bluetooth_name"

        /** Devuelve al Karoo su nombre Bluetooth si se quedó con el del mando (también tras un cierre brusco). */
        @SuppressLint("MissingPermission")
        fun restoreName(context: Context) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val original = prefs.getString(KEY_ORIGINAL_NAME, null) ?: return
            try {
                context.bluetoothAdapter().name = original
                prefs.edit().remove(KEY_ORIGINAL_NAME).apply()
            } catch (_: Exception) {
                // sin permiso o sin Bluetooth: se intenta la próxima vez
            }
        }
    }
}
