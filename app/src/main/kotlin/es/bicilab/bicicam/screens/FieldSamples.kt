package es.bicilab.bicicam.screens

import android.os.SystemClock
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import es.bicilab.bicicam.camera.CameraController
import es.bicilab.bicicam.camera.CameraMode
import es.bicilab.bicicam.camera.CameraState
import es.bicilab.bicicam.camera.t
import es.bicilab.bicicam.extension.BatteryDataType
import es.bicilab.bicicam.extension.BiciCamExtension
import es.bicilab.bicicam.extension.ButtonTileDataType
import es.bicilab.bicicam.extension.CardDataType
import es.bicilab.bicicam.extension.MarkerDataType
import es.bicilab.bicicam.extension.PowerDataType
import es.bicilab.bicicam.extension.RecordBatteryDataType
import es.bicilab.bicicam.extension.RecordBatteryWideDataType
import es.bicilab.bicicam.extension.RecordDataType
import es.bicilab.bicicam.extension.StatusDataType
import es.bicilab.bicicam.extension.TapConfirmation
import es.bicilab.bicicam.extension.TileModel
import io.hammerhead.karooext.models.ViewConfig

/**
 * Los campos tal como los pinta el Karoo, con datos de muestra, para verlos sin salir a rodar: a lo ancho
 * de la pantalla y a media anchura, en cada uno de los cuatro colores.
 */
@Composable
fun FieldSamples() {
    val ready = CameraState.Connected(statusKnown = true, batteryPercent = 87, remainingSeconds = 92 * 60, format = "4K 60", screenOff = true)
    val recording = ready.copy(recording = true, recordingSince = SystemClock.elapsedRealtime() - 192_000, markers = 2)
    val longRecording = ready.copy(recording = true, recordingSince = SystemClock.elapsedRealtime() - 3_765_000)
    val lowCard = recording.copy(remainingSeconds = 8 * 60, batteryPercent = 12)
    val off = CameraState.Off(canWake = false)
    val stop = TapConfirmation.Pending(TapConfirmation.Kind.STOP_RECORDING, confirmed = false)
    val id = BiciCamExtension.EXTENSION_ID
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(t("Lista para grabar", "Ready to record"), style = MaterialTheme.typography.titleMedium)
        // Encima de cada campo, el nombre con el que sale en el editor de páginas del Karoo: sin él no había
        // forma de saber cuál de los campos con REC era cada uno (auditoría de diseño del 21/11/2026)
        FieldName(t("Cámara: REC, batería y tarjeta", "Camera: REC, battery, card"))
        // El campo de tres filas, en un hueco de doble altura, que es donde más luce
        Wide(StatusDataType(id), ready, height = TALL_FIELD_HEIGHT)
        // El mismo campo en una fila normal y en una baja
        FieldName(t("El mismo campo en una fila normal y en una baja:", "The same field in a normal row and in a short one:"))
        Wide(StatusDataType(id), ready)
        Wide(StatusDataType(id), ready, height = SHORT_FIELD_HEIGHT)
        // En vídeo nocturno
        Wide(StatusDataType(id), ready.copy(mode = CameraMode.NIGHT))
        FieldName(t("Cámara: REC y batería (ancho)", "Camera: REC + battery (wide)"))
        Wide(RecordBatteryWideDataType(id), ready)
        FieldNames(t("REC y batería (estrecho)", "REC + battery (narrow)"), t("REC", "REC"))
        Pair(RecordBatteryDataType(id), ready, RecordDataType(id), ready)
        FieldNames(t("Batería", "Battery"), t("Tarjeta", "Card"))
        Pair(BatteryDataType(id), ready, CardDataType(id), ready)
        FieldNames(t("Encender y apagar", "On/off"), t("Marcador", "Marker"))
        Pair(PowerDataType(id), ready, MarkerDataType(id), ready)
        // En una página con muchos campos, cada uno tiene una sola fila de alto
        FieldName(t("Cámara: REC y batería (estrecho)", "Camera: REC + battery (narrow)"))
        Pair(RecordBatteryDataType(id), ready, RecordBatteryDataType(id), recording, height = SHORT_FIELD_HEIGHT)
        Text(t("Grabando", "Recording"), style = MaterialTheme.typography.titleMedium)
        Wide(StatusDataType(id), recording, height = TALL_FIELD_HEIGHT)
        // El de dos columnas grabando, con el contador de cinco cifras y con el de más de una hora
        Wide(StatusDataType(id), recording)
        Wide(StatusDataType(id), longRecording)
        Wide(StatusDataType(id), recording, height = SHORT_FIELD_HEIGHT)
        Wide(RecordBatteryWideDataType(id), recording)
        Wide(RecordBatteryWideDataType(id), longRecording)
        Pair(RecordBatteryDataType(id), recording, RecordDataType(id), recording)
        Wide(RecordDataType(id), recording)
        Pair(MarkerDataType(id), recording, StatusDataType(id), lowCard)
        Pair(BatteryDataType(id), ready.copy(batteryPercent = 24), BatteryDataType(id), lowCard)
        Text(t("Pide tu atención", "Needs your attention"), style = MaterialTheme.typography.titleMedium)
        Pair(RecordDataType(id), recording, CardDataType(id), lowCard, leftPending = stop)
        Pair(PowerDataType(id), ready, StatusDataType(id), lowCard, leftPending = TapConfirmation.Pending(TapConfirmation.Kind.SWITCH_OFF, confirmed = false))
        // La cámara está en foto o en timelapse, y grabando un timelapse
        Wide(StatusDataType(id), ready.copy(mode = CameraMode.PHOTO))
        Pair(RecordDataType(id), ready.copy(mode = CameraMode.TIMELAPSE), RecordBatteryDataType(id), ready.copy(mode = CameraMode.SLOW_MOTION))
        Wide(RecordDataType(id), recording.copy(mode = CameraMode.TIMELAPSE))
        // Grababa y ha dejado de dar señales
        Wide(StatusDataType(id), recording.copy(stalled = true))
        Text(t("Sin cámara", "No camera"), style = MaterialTheme.typography.titleMedium)
        Wide(RecordBatteryWideDataType(id), off)
        Pair(RecordDataType(id), CameraState.Asleep, PowerDataType(id), off)
        Pair(BatteryDataType(id), CameraState.Searching, MarkerDataType(id), ready)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val battery = BatteryDataType(id)
            // Desapareció con poca batería: probablemente se ha agotado
            Tile(battery, 102.dp, columns = 30, key = "vacia") { TileModel.unavailable(CameraState.Searching, t("Batería", "Battery"), compact = true, empty = CameraController.ProbablyEmpty(8, 0))!! }
            Tile(RecordDataType(id), 102.dp, columns = 30, key = "permiso") { RecordDataType(id).model(CameraState.NoPermission, null, compact = true) }
        }
    }
}

/**
 * El campo grande (REC, batería y tarjeta) con el estado real de la cámara, que se toca como en una página del
 * Karoo: es el mando de la pantalla principal de la app.
 */
@Composable
fun LiveRecordField(state: CameraState, now: Long) {
    val pending by TapConfirmation.pending.collectAsState()
    val empty by CameraController.probablyEmpty.collectAsState()
    val type = StatusDataType(BiciCamExtension.EXTENSION_ID)
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // Todo lo que cambia el dibujo entra en la clave: el estado de la cámara (sin él, el campo se quedaba con
        // lo primero que pintó), el segundo en curso mientras graba (por el contador y el piloto), la batería
        // agotada y la confirmación pendiente
        Tile(type, maxWidth, columns = 60, height = LIVE_FIELD_HEIGHT, clickable = true, key = listOf(state, now.takeIf { (state as? CameraState.Connected)?.recording == true }, empty, pending)) {
            type.model(state, pending, compact = false)
        }
    }
}

/** Alto de un campo cuando la página del Karoo tiene cuatro filas. */
private val FIELD_HEIGHT = 88.dp

/** Alto de un campo cuando la página tiene siete u ocho filas. */
private val SHORT_FIELD_HEIGHT = 46.dp

/** Un campo que ocupa dos filas de esa página. */
private val TALL_FIELD_HEIGHT = 180.dp

/** El mando de la pantalla principal de la app. */
private val LIVE_FIELD_HEIGHT = 140.dp

/** El nombre del campo tal como sale en el Karoo al elegirlo (allí, en el idioma del aparato). */
@Composable
private fun FieldName(name: String) = Text(name, style = MaterialTheme.typography.bodyMedium)

/** Para una pareja de campos estrechos, sin el «Cámara:» de delante, que no cabe. */
@Composable
private fun FieldNames(left: String, right: String) = Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(left, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(102.dp))
    Text(right, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(102.dp))
}

@Composable
private fun Wide(type: ButtonTileDataType, state: CameraState, height: Dp = FIELD_HEIGHT) = Tile(type, 208.dp, columns = 60, height = height, key = state) { type.model(state, null, compact = false) }

@Composable
private fun Pair(left: ButtonTileDataType, leftState: CameraState, right: ButtonTileDataType, rightState: CameraState, leftPending: TapConfirmation.Pending? = null, height: Dp = FIELD_HEIGHT) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Tile(left, 102.dp, columns = 30, height = height, key = leftState to leftPending) { left.model(leftState, leftPending, compact = true) }
        Tile(right, 102.dp, columns = 30, height = height, key = rightState) { right.model(rightState, null, compact = true) }
    }
}

@Composable
private fun Tile(type: ButtonTileDataType, width: Dp, columns: Int, height: Dp = FIELD_HEIGHT, clickable: Boolean = false, key: Any?, model: () -> TileModel) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val views by produceState<RemoteViews?>(initialValue = null, type.typeId, key, width, height, clickable) {
        val config = ViewConfig(
            gridSize = Pair(columns, 15),
            viewSize = Pair((width.value * density).toInt(), (height.value * density).toInt()),
            textSize = 60,
            preview = !clickable,
        )
        value = type.render(context, config, model(), clickable)
    }
    AndroidView(
        factory = { FrameLayout(it) },
        update = { frame ->
            // Solo se vuelve a montar cuando el dibujo ha cambiado, no en cada repintado de la pantalla
            val current = views
            if (frame.tag !== current) {
                frame.tag = current
                frame.removeAllViews()
                current?.let { frame.addView(it.apply(context, frame)) }
            }
        },
        modifier = Modifier.width(width).height(height),
    )
}
