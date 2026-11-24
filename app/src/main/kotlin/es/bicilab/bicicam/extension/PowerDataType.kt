package es.bicilab.bicicam.extension

import es.bicilab.bicicam.camera.t
import android.content.Context
import androidx.glance.action.Action
import es.bicilab.bicicam.camera.CameraState

/**
 * Enseña si la cámara está encendida: ON en marino, OFF en gris claro. Dos toques la apagan; un toque la
 * enciende si el Karoo puede (Insta360) y, si no (DJI), dice que se pulse su botón.
 */
class PowerDataType(extension: String) : ButtonTileDataType(extension, TYPE_ID) {
    override fun clickAction(context: Context): Action = TileTapReceiver.tapAction(context, TileTapReceiver.ACTION_TOGGLE_POWER)

    // "¿APAGAR?" encoge; lo normal es "ON" u "OFF"
    override val titleWidthEm = 4.6f

    // Aquí el título sí hace falta: «ON» a secas no dice de qué
    private fun label() = t("Cámara", "Camera")

    override fun model(state: CameraState, pending: TapConfirmation.Pending?, compact: Boolean): TileModel = TileModel.unavailable(state, label(), compact)
        ?: when (state) {
            // No se despierta por Bluetooth: se enciende con su botón y BiciCAM conecta sola
            is CameraState.Off -> TileModel(label(), "OFF", TileModel.UNAVAILABLE, hint = if (state.canWake) null else TileModel.pressItsButton(compact))
            is CameraState.Connected -> when {
                // Hasta que la cámara se apaga
                pending?.kind == TapConfirmation.Kind.SWITCH_OFF && pending.confirmed ->
                    TileModel(label(), t("Apagando", "Switching off"), TileModel.ATTENTION)
                pending?.kind == TapConfirmation.Kind.SWITCH_OFF ->
                    TileModel(
                        label(),
                        t("¿APAGAR?", "OFF?"),
                        TileModel.ATTENTION,
                        // Apagar mientras graba para antes la grabación
                        hint = when {
                            state.recording -> if (compact) t("Parará REC", "Stops REC too") else t("También para la grabación", "Also stops the recording")
                            else -> if (compact) t("Toca otra vez", "Tap again") else t("Toca otra vez para apagar", "Tap again to switch off")
                        },
                    )
                // En los campos estrechos no cabe la batería
                else -> TileModel(label(), "ON", TileModel.READY, labelBattery = state.batteryPercent?.takeUnless { compact })
            }
            else -> error("inalcanzable")
        }

    override fun previewModel(compact: Boolean) = TileModel(label(), "ON", TileModel.READY, labelBattery = SAMPLE_BATTERY.takeUnless { compact })

    companion object {
        const val TYPE_ID = "power"
    }
}
