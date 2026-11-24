package es.bicilab.bicicam.extension

import es.bicilab.bicicam.camera.CameraState
import es.bicilab.bicicam.camera.t

/**
 * Todo lo de la cámara en un campo de tres filas: REC o el tiempo de grabación en grande; debajo, la batería
 * con su dibujo, que cambia de color según se gasta; y debajo, lo que cabe en la tarjeta con el suyo. Se
 * toca igual que el campo de REC. Está pensado para un hueco de doble altura: en uno bajo, las filas de
 * abajo no se leerían, así que se pintan dos y la tarjeta sube a la etiqueta.
 */
class StatusDataType(extension: String) : RecordDataType(extension, TYPE_ID) {
    override fun model(state: CameraState, pending: TapConfirmation.Pending?, compact: Boolean): TileModel {
        val base = super.model(state, pending, compact)
        val connected = state as? CameraState.Connected ?: return base
        // Mientras pide el segundo toque o hay que hacer algo, el campo dice eso y nada más
        if (base.background == TileModel.ATTENTION || base.hint != null) return base.copy(labelBattery = null)
        return base.withEverything(connected.batteryPercent, connected.runtime, connected.remainingSeconds, compact)
    }

    override fun previewModel(compact: Boolean) = super.previewModel(compact).withEverything(SAMPLE_BATTERY, null, 92 * 60, compact)

    private fun TileModel.withEverything(percent: Int?, runtime: String?, cardSeconds: Int?, compact: Boolean): TileModel {
        // Insta360 no dice la tarjeta: sin ese dato, el campo se queda en REC y batería
        val low = cardSeconds != null && cardSeconds <= 10 * 60
        return copy(
            labelBattery = null,
            bigBattery = percent,
            bigSecond = percent?.let { TileModel.percent(it) } ?: runtime ?: t("-- %", "--%"),
            third = cardSeconds?.let { CardDataType.format(it) },
            // Con menos de diez minutos de tarjeta, esa fila avisa cambiando de color
            thirdLow = low,
            // En un campo bajo no caben tres filas legibles: se pintan dos y la tarjeta sube, con su dibujo, a la línea de la etiqueta
            sideBySide = !compact,
        )
    }

    companion object {
        const val TYPE_ID = "status"
    }
}
