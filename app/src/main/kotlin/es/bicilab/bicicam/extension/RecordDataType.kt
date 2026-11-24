package es.bicilab.bicicam.extension

import es.bicilab.bicicam.camera.t
import android.content.Context
import android.os.SystemClock
import androidx.glance.action.Action
import es.bicilab.bicicam.camera.AppSettings
import es.bicilab.bicicam.camera.CameraMode
import es.bicilab.bicicam.camera.CameraState

/** Enseña el estado de la cámara; un toque empieza o para la grabación. */
open class RecordDataType(extension: String, typeId: String = TYPE_ID) : ButtonTileDataType(extension, typeId) {
    override fun clickAction(context: Context): Action = TileTapReceiver.tapAction(context, TileTapReceiver.ACTION_TOGGLE_RECORDING)

    // El valor más ancho es el cuadrado de parar con "0:00:00"
    override val titleWidthEm = 4.6f

    override fun model(state: CameraState, pending: TapConfirmation.Pending?, compact: Boolean): TileModel = TileModel.unavailable(state, LABEL(), compact)
        ?: when (state) {
            is CameraState.Off -> TileModel(
                if (compact) t("Apagada", "Camera off") else t("Cámara apagada", "Camera off"),
                "REC",
                TileModel.UNAVAILABLE,
                icon = TileIcon.Record,
                // Si el Karoo puede encenderla, basta con tocar; si no, hay que decir qué hacer
                hint = if (state.canWake) null else TileModel.pressItsButton(compact),
            )
            is CameraState.Connected -> {
                // En los campos estrechos no cabe la batería
                val battery = state.batteryPercent?.takeUnless { compact }
                when {
                    // Grababa y ha dejado de dar señales: no se sabe si sigue. Ni rojo ni contador a ciegas
                    state.stalled -> TileModel(LABEL(), t("Sin señal", "No signal"), TileModel.ATTENTION, hint = if (compact) t("Mira la cámara", "Check camera") else t("Mira si la cámara graba", "Check the camera is recording"))
                    // Hasta que la cámara avisa de que ha dejado de grabar
                    state.recording && pending?.kind == TapConfirmation.Kind.STOP_RECORDING && pending.confirmed ->
                        TileModel(LABEL(), t("Parando", "Stopping"), TileModel.ATTENTION)
                    state.recording && pending?.kind == TapConfirmation.Kind.STOP_RECORDING ->
                        TileModel(LABEL(), t("¿PARAR?", "STOP?"), TileModel.ATTENTION, hint = if (compact) t("Toca otra vez", "Tap again") else t("Toca otra vez para parar", "Tap again to stop"))
                    state.recording -> TileModel(
                        // La etiqueta es el nombre del campo: que graba ya lo dicen el rojo y el contador. Si lo
                        // que graba no es vídeo normal (un timelapse, cámara lenta), lo dice arriba
                        state.mode?.takeIf { it != CameraMode.VIDEO }?.let { modeName(it, compact) } ?: LABEL(),
                        formatDuration(state.recordingSince),
                        TileModel.RECORDING,
                        // El piloto de la cámara: parpadea cada segundo si así está en los ajustes
                        icon = TileIcon.Pilot(lit = !AppSettings.blinkPilot.value || clipSeconds(state.recordingSince) % 2 == 0L),
                        labelBattery = battery,
                    )
                    // La cámara no está en vídeo: un toque haría una foto o empezaría un timelapse. Se ve de un
                    // vistazo, en ámbar y con el dibujo del modo (pedido por Antun el 24/11/2026)
                    !state.busy && state.mode != null && state.mode != CameraMode.VIDEO && state.mode != CameraMode.NIGHT -> TileModel(
                        if (compact) t("No es vídeo", "Not video") else t("La cámara no está en vídeo", "The camera is not in video"),
                        modeName(state.mode, compact).uppercase(),
                        TileModel.ATTENTION,
                        icon = TileIcon.Mode(state.mode),
                    )
                    // Viendo vídeos en su pantalla, la cámara no graba desde el mando: hay que salir de ahí
                    state.busy -> TileModel(LABEL(), "REC", TileModel.READY, icon = TileIcon.Record, hint = if (compact) t("En la galería", "In playback") else t("En la galería: sal para grabar", "In playback: exit it to record"), labelBattery = battery)
                    // En vídeo nocturno se graba igual, y puede ser a propósito: el campo lo dice arriba, sin ámbar
                    state.mode == CameraMode.NIGHT -> TileModel(t("Modo noche", "Night mode"), "REC", TileModel.READY, icon = TileIcon.Record, labelBattery = battery)
                    else -> TileModel(LABEL(), "REC", TileModel.READY, icon = TileIcon.Record, labelBattery = battery)
                }
            }
            else -> error("inalcanzable")
        }

    override fun previewModel(compact: Boolean) = TileModel(LABEL(), "REC", TileModel.READY, icon = TileIcon.Record, labelBattery = SAMPLE_BATTERY.takeUnless { compact })

    /** El nombre del modo, corto: es lo que se lee en el campo. */
    private fun modeName(mode: CameraMode, compact: Boolean): String = when (mode) {
        CameraMode.VIDEO -> t("Vídeo", "Video")
        CameraMode.NIGHT -> t("Noche", "Night")
        CameraMode.PHOTO -> t("Foto", "Photo")
        CameraMode.TIMELAPSE -> if (compact) "Lapse" else "Timelapse"
        CameraMode.SLOW_MOTION -> if (compact) t("Lenta", "Slow") else t("Cámara lenta", "Slow motion")
        CameraMode.HYPERLAPSE -> if (compact) "Hyper" else "Hyperlapse"
        CameraMode.OTHER -> if (compact) t("Otro", "Other") else t("Otro modo", "Other mode")
    }

    private fun clipSeconds(since: Long?): Long = since?.let { (SystemClock.elapsedRealtime() - it) / 1000 }?.coerceAtLeast(0) ?: 0

    private fun formatDuration(since: Long?): String {
        val seconds = clipSeconds(since)
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    companion object {
        const val TYPE_ID = "record"

        /**
         * La etiqueta de los campos de grabar: ninguna. Antes decía «Cámara», pero el punto de REC ya dice
         * qué es el campo, y esa línea le quitaba al valor la altura que le hace falta en un campo de una
         * fila (pedido por Antun el 14/11/2026: con el título no cabía la batería). La línea de arriba solo
         * sale cuando tiene algo que decir: «Cámara apagada», la tarjeta o la batería en pequeño.
         */
        val LABEL = { "" }
    }
}
