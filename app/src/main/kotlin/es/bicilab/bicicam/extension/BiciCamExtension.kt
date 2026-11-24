package es.bicilab.bicicam.extension

import android.util.Log
import es.bicilab.bicicam.BuildConfig
import es.bicilab.bicicam.R
import android.os.SystemClock
import es.bicilab.bicicam.camera.CameraController
import es.bicilab.bicicam.camera.CameraState
import es.bicilab.bicicam.camera.AppSettings
import es.bicilab.bicicam.camera.crashGuard
import es.bicilab.bicicam.camera.t
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DeveloperField
import io.hammerhead.karooext.models.FieldValue
import io.hammerhead.karooext.models.FitEffect
import io.hammerhead.karooext.models.WriteToRecordMesg
import kotlinx.coroutines.delay
import es.bicilab.bicicam.camera.LocationFeed
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.RequestBluetooth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class BiciCamExtension : KarooExtension(EXTENSION_ID, BuildConfig.VERSION_NAME) {
    private lateinit var karooSystem: KarooSystemService
    private var serviceJob: Job? = null
    private var locationConsumer: String? = null
    private var speedConsumer: String? = null

    override val types by lazy {
        listOf(StatusDataType(extension), RecordDataType(extension), RecordBatteryDataType(extension), RecordBatteryWideDataType(extension), BatteryDataType(extension), PowerDataType(extension), CardDataType(extension), MarkerDataType(extension))
    }

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(this)
        CameraController.note(t("El Karoo ha arrancado la extensión", "The Karoo started the extension"))
        // Si BiciCAM se encuentra el Bluetooth apagado en plena ruta, se lo vuelve a pedir al Karoo
        CameraController.onBluetoothOff = {
            try {
                karooSystem.dispatch(RequestBluetooth(EXTENSION_ID))
            } catch (e: Exception) {
                Log.w("BiciCAM", "No se pudo pedir el Bluetooth", e)
            }
        }
        karooSystem.connect { connected ->
            CameraController.note(if (connected) t("Conectada con el sistema del Karoo: se pide el Bluetooth", "Connected to the Karoo system: asking for Bluetooth") else t("El sistema del Karoo se ha desconectado de la extensión", "The Karoo system disconnected from the extension"))
            if (connected) {
                karooSystem.dispatch(RequestBluetooth(EXTENSION_ID))
                // Si el Karoo reconecta, no se apilan consumidores repetidos
                locationConsumer?.let(karooSystem::removeConsumer)
                speedConsumer?.let(karooSystem::removeConsumer)
                // La posición del Karoo, para las cámaras que guardan el GPS con el vídeo
                locationConsumer = karooSystem.addConsumer { location: OnLocationChanged ->
                    LocationFeed.update(location.lat, location.lng, location.orientation)
                }
                // La velocidad que enseña el Karoo (en metros por segundo), mejor que la calculada entre posiciones
                speedConsumer = karooSystem.addConsumer(OnStreamState.StartStreaming(DataType.Type.SPEED)) { event: OnStreamState ->
                    (event.state as? StreamState.Streaming)?.dataPoint?.singleValue?.let(LocationFeed::updateSpeed)
                }
            }
        }
        serviceJob = CoroutineScope(Dispatchers.IO + crashGuard).launch {
            CameraController.notices.collect { notice ->
                // Un aviso que falla no puede dejar sin avisos el resto de la ruta
                try {
                    karooSystem.dispatch(
                    InRideAlert(
                        // Los errores van aparte: un aviso de batería no puede tapar a «Sin conexión»
                        id = if (notice.isError) "bicicam-error" else "bicicam-aviso",
                        icon = R.drawable.ic_bicicam,
                        // El título es lo que se lee de un vistazo: lleva el mensaje
                        title = notice.title,
                        detail = notice.detail,
                        autoDismissMs = if (notice.isError) 8_000 else 3_000,
                        backgroundColor = if (notice.isError) R.color.alert_error_background else R.color.alert_background,
                        textColor = R.color.alert_text,
                    ),
                    )
                } catch (e: Exception) {
                    Log.w("BiciCAM", "No se pudo enseñar un aviso", e)
                }
            }
        }
    }

    /**
     * Mientras el Karoo graba la ruta, apunta en su archivo (FIT), segundo a segundo, si la cámara está
     * grabando, por qué segundo del clip va y cuántos marcadores lleva. Con eso, quien edite el vídeo sabe a
     * qué tramo de la ruta corresponde cada clip y puede sacar de ahí la potencia, el pulso o la velocidad.
     * Solo si se ha activado en los ajustes.
     */
    override fun startFit(emitter: Emitter<FitEffect>) {
        // Los marcadores se cuentan por ruta, no desde que arrancó la app
        val markersAtStart = CameraController.markerTotal
        val job = CoroutineScope(Dispatchers.IO + crashGuard).launch {
            while (true) {
                if (AppSettings.fitMarks.value) {
                    val connected = CameraController.state.value as? CameraState.Connected
                    val since = connected?.recordingSince?.takeIf { connected.recording && !connected.stalled }
                    val clipSeconds = since?.let { ((SystemClock.elapsedRealtime() - it) / 1000).coerceIn(0, 65_000) } ?: 0
                    try {
                        emitter.onNext(
                        WriteToRecordMesg(
                            listOf(
                                FieldValue(FIT_RECORDING, if (since != null) 1.0 else 0.0),
                                FieldValue(FIT_CLIP_SECONDS, clipSeconds.toDouble()),
                                FieldValue(FIT_MARKERS, ((CameraController.markerTotal - markersAtStart).coerceAtLeast(0) % 65_000).toDouble()),
                            ),
                        ),
                        )
                    } catch (e: Exception) {
                        // Un segundo sin apuntar no puede parar los siguientes
                        Log.w("BiciCAM", "No se pudo escribir en el archivo de la ruta", e)
                    }
                }
                delay(1000)
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    override fun onBonusAction(actionId: String) {
        when (BiciCamAction.fromActionId(actionId)) {
            BiciCamAction.TOGGLE_RECORDING -> CameraController.toggleRecording(announce = true)
            BiciCamAction.ADD_MARKER -> CameraController.addMarker()
            null -> Log.w("BiciCAM", "Acción desconocida: $actionId")
        }
    }

    override fun onDestroy() {
        CameraController.note(t("El Karoo ha parado la extensión: se suelta el Bluetooth", "The Karoo stopped the extension: releasing Bluetooth"))
        CameraController.onBluetoothOff = null
        serviceJob?.cancel()
        serviceJob = null
        locationConsumer?.let(karooSystem::removeConsumer)
        speedConsumer?.let(karooSystem::removeConsumer)
        karooSystem.dispatch(ReleaseBluetooth(EXTENSION_ID))
        karooSystem.disconnect()
        super.onDestroy()
    }

    companion object {
        const val EXTENSION_ID = "bicicam"

        // Tipos base de FIT: 2 = entero de 8 bits sin signo, 132 = de 16 bits
        private val FIT_RECORDING = DeveloperField(fieldDefinitionNumber = 0, fitBaseTypeId = 2, fieldName = "camara_grabando", units = "")
        private val FIT_CLIP_SECONDS = DeveloperField(fieldDefinitionNumber = 1, fitBaseTypeId = 132, fieldName = "camara_segundo_clip", units = "s")
        private val FIT_MARKERS = DeveloperField(fieldDefinitionNumber = 2, fitBaseTypeId = 132, fieldName = "camara_marcadores", units = "")
    }
}
