package es.bicilab.bicicam.extension

import es.bicilab.bicicam.camera.t
import es.bicilab.bicicam.camera.CameraState

/** Tiempo de vídeo que cabe aún en la tarjeta de la cámara, con el modo en que está. Solo DJI. No se toca. */
class CardDataType(extension: String) : ButtonTileDataType(extension, TYPE_ID) {
    // El valor más ancho es del estilo de "12 h 30 m"
    override val titleWidthEm = 4.6f

    private fun label(compact: Boolean) = if (compact) t("Tarjeta", "Card") else t("Tarjeta de la cámara", "Camera card")

    override fun model(state: CameraState, pending: TapConfirmation.Pending?, compact: Boolean): TileModel = TileModel.unavailable(state, label(compact), compact)
        ?: when (state) {
            is CameraState.Off -> TileModel(if (compact) t("Apagada", "Camera off") else t("Cámara apagada", "Camera off"), "--", TileModel.UNAVAILABLE)
            is CameraState.Connected -> {
                val seconds = state.remainingSeconds
                // El modo (4K 60) solo va donde cabe: no es algo sobre lo que haya que actuar
                val label = state.format?.takeUnless { compact }?.let { t("Tarjeta, $it", "Card, $it") } ?: label(compact)
                when {
                    // Insta360 no le da este dato al mando
                    seconds == null -> TileModel(label, "--", TileModel.READY)
                    // Con menos de diez minutos, en ámbar: hay que ir pensando en parar o cambiar la tarjeta
                    // Aquí la etiqueta sí cambia: dice por qué el campo está en ámbar
                    seconds <= LOW_SECONDS -> TileModel(if (compact) label(compact) else t("Tarjeta casi llena", "Card almost full"), format(seconds), TileModel.ATTENTION)
                    else -> TileModel(label, format(seconds), TileModel.READY)
                }
            }
            else -> error("inalcanzable")
        }

    override fun previewModel(compact: Boolean) = TileModel(if (compact) label(compact) else t("Tarjeta, 4K 60", "Card, 4K 60"), format(92 * 60), TileModel.READY)

    companion object {
        const val TYPE_ID = "card"
        private const val LOW_SECONDS = 10 * 60

        /** «1 h 32», sin «m» detrás: en un ciclocomputador, «m» se lee como metros. Por debajo de la hora, «45 min». */
        fun format(seconds: Int): String {
            val minutes = seconds / 60
            return if (minutes >= 60) "%d h %02d".format(minutes / 60, minutes % 60) else "$minutes min"
        }
    }
}
