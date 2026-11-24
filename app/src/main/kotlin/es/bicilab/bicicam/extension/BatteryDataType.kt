package es.bicilab.bicicam.extension

import es.bicilab.bicicam.camera.t
import es.bicilab.bicicam.camera.CameraState

/** Batería de la cámara: un dibujo de batería en cuatro segmentos y el porcentaje. No se toca. */
class BatteryDataType(extension: String) : ButtonTileDataType(extension, TYPE_ID) {
    // El dibujo de la batería y "100 %"
    override val titleWidthEm = 4.9f

    private fun label(compact: Boolean) = if (compact) t("Batería", "Battery") else t("Batería de la cámara", "Camera battery")

    override fun model(state: CameraState, pending: TapConfirmation.Pending?, compact: Boolean): TileModel = TileModel.unavailable(state, label(compact), compact)
        ?: when (state) {
            is CameraState.Off -> TileModel(if (compact) t("Apagada", "Camera off") else t("Cámara apagada", "Camera off"), t("-- %", "--%"), TileModel.UNAVAILABLE, icon = TileIcon.Battery(null))
            is CameraState.Connected -> {
                val percent = state.batteryPercent
                val runtime = state.runtime
                when {
                    percent != null -> TileModel(
                        // En espera, la pantalla encendida es lo que más gasta: se dice donde cabe entera (en el campo estrecho, no)
                        when (state.screenOff.takeUnless { state.recording }) {
                            true -> if (compact) label(compact) else t("Batería, pantalla apagada", "Battery, screen off")
                            false -> if (compact) label(compact) else t("Batería, pantalla encendida", "Battery, screen on")
                            null -> label(compact)
                        },
                        TileModel.percent(percent),
                        TileModel.READY,
                        icon = TileIcon.Battery(percent),
                    )
                    // Insta360 no da el porcentaje al mando: da el tiempo que calcula que le queda
                    runtime != null -> TileModel(if (compact) t("Autonomía", "Runtime") else t("Autonomía de la cámara", "Camera runtime"), runtime, TileModel.READY)
                    else -> TileModel(label(compact), t("-- %", "--%"), TileModel.READY, icon = TileIcon.Battery(null))
                }
            }
            else -> error("inalcanzable")
        }

    override fun previewModel(compact: Boolean) = TileModel(label(compact), TileModel.percent(SAMPLE_BATTERY), TileModel.READY, icon = TileIcon.Battery(SAMPLE_BATTERY))

    companion object {
        const val TYPE_ID = "battery"
    }
}
