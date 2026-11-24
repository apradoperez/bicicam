package es.bicilab.bicicam.extension

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.action.Action
import androidx.glance.appwidget.action.actionSendBroadcast
import es.bicilab.bicicam.camera.CameraController
import es.bicilab.bicicam.camera.CameraState
import es.bicilab.bicicam.camera.AppSettings

/**
 * Recibe los toques en los campos.
 *
 * Cada campo tiene su propia acción de intent: con `actionRunCallback` de Glance los intents de los campos
 * solo se distinguían por sus extras, y un toque en una podía ejecutar la acción de otra.
 */
class TileTapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val connected = CameraController.state.value as? CameraState.Connected
        when (intent.action) {
            ACTION_TOGGLE_RECORDING ->
                // Parar pide un segundo toque si así se ha elegido en los ajustes; empezar, nunca
                if (connected?.recording == true && !connected.stalled && !AppSettings.stopWithOneTap.value) {
                    if (TapConfirmation.confirm(TapConfirmation.Kind.STOP_RECORDING) && !CameraController.toggleRecording(announce = false)) TapConfirmation.clear()
                } else {
                    TapConfirmation.clear()
                    CameraController.toggleRecording(announce = false)
                }
            ACTION_MARK -> CameraController.addMarker()
            ACTION_TOGGLE_POWER ->
                // Apagar pide un segundo toque; encender, no
                if (connected != null) {
                    if (TapConfirmation.confirm(TapConfirmation.Kind.SWITCH_OFF) && !CameraController.togglePower(announce = false)) TapConfirmation.clear()
                } else {
                    TapConfirmation.clear()
                    CameraController.togglePower(announce = false)
                }
        }
    }

    companion object {
        const val ACTION_TOGGLE_RECORDING = "es.bicilab.bicicam.TOGGLE_RECORDING"
        const val ACTION_TOGGLE_POWER = "es.bicilab.bicicam.TOGGLE_POWER"
        const val ACTION_MARK = "es.bicilab.bicicam.MARK"

        fun tapAction(context: Context, action: String): Action =
            actionSendBroadcast(Intent(context, TileTapReceiver::class.java).setAction(action))
    }
}
