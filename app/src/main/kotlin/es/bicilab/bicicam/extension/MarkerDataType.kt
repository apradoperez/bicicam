package es.bicilab.bicicam.extension

import es.bicilab.bicicam.camera.t
import android.content.Context
import androidx.glance.action.Action
import es.bicilab.bicicam.camera.CameraState

/** Un toque deja un marcador en el vídeo que se está grabando; la etiqueta dice cuántos lleva el clip. */
class MarkerDataType(extension: String) : ButtonTileDataType(extension, TYPE_ID) {
    override fun clickAction(context: Context): Action = TileTapReceiver.tapAction(context, TileTapReceiver.ACTION_MARK)

    // "MARCAR" a secas
    override val titleWidthEm = 4.6f

    private fun label() = t("Marcadores", "Markers")

    private fun value() = t("MARCAR", "MARK")

    override fun model(state: CameraState, pending: TapConfirmation.Pending?, compact: Boolean): TileModel = TileModel.unavailable(state, label(), compact)?.copy(centered = true)
        ?: when (state) {
            is CameraState.Off -> TileModel(if (compact) t("Apagada", "Camera off") else t("Cámara apagada", "Camera off"), value(), TileModel.UNAVAILABLE, centered = true)
            is CameraState.Connected -> when {
                // Sin grabar no hay dónde dejar el marcador: tocar no hace nada, y hay que decir por qué
                !state.recording -> TileModel(label(), value(), TileModel.UNAVAILABLE, hint = if (compact) t("Solo al grabar", "Only on REC") else t("Solo mientras graba", "Only while recording"), centered = true)
                state.markers == 0 -> TileModel(label(), value(), TileModel.READY, centered = true)
                else -> TileModel(if (compact) t("Marcas: ${state.markers}", "Markers: ${state.markers}") else t("Marcadores: ${state.markers}", "Markers: ${state.markers}"), value(), TileModel.READY, centered = true)
            }
            else -> error("inalcanzable")
        }

    override fun previewModel(compact: Boolean) = TileModel(label(), value(), TileModel.READY, centered = true)

    companion object {
        const val TYPE_ID = "marker"
    }
}
