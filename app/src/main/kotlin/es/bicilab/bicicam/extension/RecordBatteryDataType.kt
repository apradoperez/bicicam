package es.bicilab.bicicam.extension

import es.bicilab.bicicam.camera.CameraState
import es.bicilab.bicicam.camera.t

/**
 * Como el campo de REC, con la batería de la cámara en grande: debajo de REC (campos estrechos) o a su
 * derecha (campos anchos). Se toca igual: empieza o para la grabación.
 */
open class RecordBatteryDataType(
    extension: String,
    typeId: String = TYPE_ID,
    /** REC y batería en una fila (campos anchos) en vez de uno encima del otro (campos estrechos). */
    private val wide: Boolean = false,
) : RecordDataType(extension, typeId) {
    override fun model(state: CameraState, pending: TapConfirmation.Pending?, compact: Boolean): TileModel {
        val base = super.model(state, pending, compact)
        val connected = state as? CameraState.Connected ?: return base
        // Mientras pide el segundo toque o hay que hacer algo, el campo dice eso y nada más
        if (base.background == TileModel.ATTENTION || base.hint != null) return base.copy(labelBattery = null)
        return base.withBigBattery(connected.batteryPercent, connected.runtime)
    }

    override fun previewModel(compact: Boolean) = super.previewModel(compact).withBigBattery(SAMPLE_BATTERY, null)

    /** Con Insta360 no hay porcentaje: se enseña la autonomía que dice la cámara. */
    private fun TileModel.withBigBattery(percent: Int?, runtime: String?) = copy(
        labelBattery = null,
        bigBattery = percent,
        bigSecond = percent?.let { TileModel.percent(it) } ?: runtime ?: t("-- %", "--%"),
        sideBySide = wide,
    )

    companion object {
        const val TYPE_ID = "record-battery"
    }
}

/** La misma información en una sola fila, para un campo que ocupe la pantalla de lado a lado. */
class RecordBatteryWideDataType(extension: String) : RecordBatteryDataType(extension, TYPE_ID, wide = true) {
    companion object {
        const val TYPE_ID = "record-battery-wide"
    }
}
