package es.bicilab.bicicam

import android.app.Application
import es.bicilab.bicicam.camera.CameraController
import es.bicilab.bicicam.camera.EventLog
import es.bicilab.bicicam.camera.MarkerLog
import es.bicilab.bicicam.camera.AppSettings
import es.bicilab.bicicam.extension.FieldsSeen
import es.bicilab.bicicam.extension.RecordingFrame
import es.bicilab.bicicam.insta360.InstaRemote

class BiciCamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
        MarkerLog.init(this)
        // Si la app se cerró de golpe haciendo de mando de Insta360, el Karoo recupera su nombre Bluetooth
        InstaRemote.restoreName(this)
        AppSettings.init(this)
        FieldsSeen.init(this)
        CameraController.init(this)
        RecordingFrame.init(this)
    }
}
