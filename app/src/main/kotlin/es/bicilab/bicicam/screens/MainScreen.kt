package es.bicilab.bicicam.screens

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import es.bicilab.bicicam.BuildConfig
import es.bicilab.bicicam.R
import es.bicilab.bicicam.camera.AppSettings
import es.bicilab.bicicam.camera.Brand
import es.bicilab.bicicam.camera.ENABLED_BRANDS
import es.bicilab.bicicam.camera.CameraController
import es.bicilab.bicicam.camera.CameraState
import es.bicilab.bicicam.camera.EventLog
import es.bicilab.bicicam.camera.FoundCamera
import es.bicilab.bicicam.camera.Language
import es.bicilab.bicicam.camera.MarkerLog
import es.bicilab.bicicam.camera.PairedCamera
import es.bicilab.bicicam.camera.t
import es.bicilab.bicicam.dji.pairDjiCamera
import es.bicilab.bicicam.gopro.pairGoProCamera
import es.bicilab.bicicam.gopro.scanForGoProCameras
import es.bicilab.bicicam.dji.scanForDjiCameras
import es.bicilab.bicicam.extension.CardDataType
import es.bicilab.bicicam.extension.FieldsSeen
import es.bicilab.bicicam.extension.RecordingFrame
import es.bicilab.bicicam.extension.TileModel
import es.bicilab.bicicam.insta360.InstaPairing
import es.bicilab.bicicam.insta360.pairInstaCamera
import es.bicilab.bicicam.theme.LinkButton
import es.bicilab.bicicam.theme.MainButton
import es.bicilab.bicicam.theme.PlainButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DateFormat
import java.util.Date
import kotlin.time.Duration.Companion.seconds

private enum class Screen { HOME, SETTINGS, HELP, FIELDS, ABOUT, LOG }

@Composable
fun MainScreen(hasPermission: Boolean, onRequestPermission: () -> Unit, canDrawFrame: Boolean) {
    val paired by CameraController.pairedCamera.collectAsState()
    val state by CameraController.state.collectAsState()
    // Leer el idioma aquí hace que toda la pantalla se repinte al cambiarlo
    val language by AppSettings.language.collectAsState()

    var screen by remember { mutableStateOf(Screen.HOME) }
    // De dónde se llegó a la muestra de los campos, para volver ahí
    var fieldsFrom by remember { mutableStateOf(Screen.HOME) }

    // Volver lleva a la pantalla de la que se vino en vez de salir de la app
    BackHandler(enabled = screen != Screen.HOME) {
        screen = when (screen) {
            Screen.LOG -> Screen.ABOUT
            Screen.FIELDS -> fieldsFrom
            else -> Screen.HOME
        }
    }

    // Refresca el contador de grabación
    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }

    key(language) {
        when (screen) {
            Screen.LOG -> EventLogScreen(onClose = { screen = Screen.ABOUT })
            Screen.ABOUT -> AboutScreen(onClose = { screen = Screen.HOME }, onShowLog = { screen = Screen.LOG })
            Screen.HELP -> HelpScreen(onClose = { screen = Screen.HOME }, onShowFields = { fieldsFrom = Screen.HELP; screen = Screen.FIELDS })
            Screen.FIELDS -> FieldsScreen(onClose = { screen = fieldsFrom })
            Screen.SETTINGS -> SettingsScreen(canDrawFrame = canDrawFrame, hasCamera = paired != null, onClose = { screen = Screen.HOME })
            Screen.HOME -> HomeScreen(hasPermission, onRequestPermission, paired, state, now) { target ->
                if (target == Screen.FIELDS) fieldsFrom = Screen.HOME
                screen = target
            }
        }
    }
}

@Composable
private fun HomeScreen(
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    paired: PairedCamera?,
    state: CameraState,
    now: Long,
    open: (Screen) -> Unit,
) {
    Page {
        if (!hasPermission) {
            Title(t("Permiso de Bluetooth", "Bluetooth permission"))
            Explain(
                t("BiciCAM necesita el permiso de Bluetooth para hablar con tu cámara. En algunos Karoo, Android lo llama permiso de ubicación: BiciCAM no lo usa para saber dónde estás.", "BiciCAM needs the Bluetooth permission to talk to your camera. On some Karoos, Android calls it the location permission: BiciCAM does not use it to find out where you are."),
            )
            MainButton(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) { Text(t("Dar permiso", "Grant permission")) }
        } else {
            val camera = paired
            if (camera != null) {
                // El titular es la cámara, en una línea; debajo, cómo distinguirla de otra igual
                val (model, ending) = cameraTitle(camera)
                Column {
                    Title(model)
                    ending?.let { Text(t("Bluetooth: acaba en $it", "Bluetooth: ends in $it"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                // El mando es el mismo campo que se ve en la ruta, con el estado real, y se toca igual
                LiveRecordField(state, now)
                val empty by CameraController.probablyEmpty.collectAsState()
                statusDetail(state, camera.brand, empty)?.let { Explain(it) }
                val connected = state is CameraState.Connected
                when {
                    // Dos toques: una DJI apagada ya no se enciende desde el Karoo, y apagar corta la grabación
                    connected -> ConfirmButton(
                        t("Apagar la cámara", "Switch camera off"),
                        if ((state as? CameraState.Connected)?.recording == true) t("Toca otra vez: para REC y apaga", "Tap again: stops REC, switches off") else t("Toca otra vez para apagarla", "Tap again to switch it off"),
                    ) { CameraController.togglePower(announce = false) }
                    // Una DJI no se enciende desde el Karoo: no hay botón que lo prometa
                    camera.brand != Brand.DJI -> MainButton(onClick = { CameraController.togglePower(announce = false) }, modifier = Modifier.fillMaxWidth()) {
                        Text(t("Encender la cámara", "Switch camera on"))
                    }
                }
                val fieldsSeen by FieldsSeen.seen.collectAsState()
                if (!fieldsSeen) {
                    Explain(t("Siguiente paso: añade los campos de BiciCAM a una página de datos de tu perfil, en el Karoo.", "Next step: add the BiciCAM fields to a data page of your ride profile, on the Karoo."))
                }
                PlainButton(onClick = { open(Screen.FIELDS) }, modifier = Modifier.fillMaxWidth()) { Text(t("Ver cómo son los campos", "Preview the fields")) }
            } else {
                PairingSection()
            }
            // Lo que se toca pocas veces, en su propia pantalla
            PlainButton(onClick = { open(Screen.SETTINGS) }, modifier = Modifier.fillMaxWidth()) { Text(t("Ajustes", "Settings")) }
        }

        LinkButton(onClick = { open(Screen.HELP) }, modifier = Modifier.fillMaxWidth()) { Text(t("Ayuda", "Help")) }
        LinkButton(onClick = { open(Screen.ABOUT) }, modifier = Modifier.fillMaxWidth()) { Text(t("Acerca de BiciCAM", "About BiciCAM")) }
        Spacer(Modifier.height(8.dp))
    }
}

/** «Osmo Action 6 (OsmoAction6-40A0)» se parte en el modelo y las cuatro últimas letras de su nombre Bluetooth. */
private fun cameraTitle(camera: PairedCamera): Pair<String, String?> {
    val inBrackets = camera.name.substringAfter(" (", "").removeSuffix(")")
    return when {
        inBrackets.isNotEmpty() -> camera.name.substringBefore(" (") to inBrackets.takeLast(4)
        camera.brand == Brand.INSTA360 -> "Insta360" to camera.name.takeLast(4)
        else -> camera.name to camera.address.takeLast(5)
    }
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.headlineMedium, maxLines = 2)

/** Las explicaciones van a 14 sp: a la distancia del manillar, menos no se lee. */
@Composable
private fun Explain(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium)

/**
 * Un ajuste: a la izquierda dice qué es y a la derecha, en negrita, cómo está ahora. Al tocarlo pasa al
 * valor siguiente.
 */
@Composable
private fun Setting(name: String, value: String, onClick: () -> Unit) {
    // Una fila de lista, sin caja: el borde se queda para las acciones («Dar permiso», «Olvidar esta cámara»).
    // Con todo en cajas iguales, nueve ajustes seguidos pesaban lo mismo que los botones de verdad
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            // El nombre a la izquierda y el valor a la derecha de la misma línea: el valor nunca se cae solo abajo
            Text(name, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
            Spacer(Modifier.width(12.dp))
            Text(value, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.End)
        }
        HorizontalDivider()
    }
}

private fun yesNo(on: Boolean) = if (on) t("sí", "on") else t("no", "off")

/** Todo lo que se elige una vez y se deja: cómo se para, cuándo corta sola, avisos, marco, GPS y apagado. */
@Composable
private fun SettingsScreen(canDrawFrame: Boolean, hasCamera: Boolean, onClose: () -> Unit) {
    val oneTap by AppSettings.stopWithOneTap.collectAsState()
    val clip by AppSettings.clipMinutes.collectAsState()
    val alertBattery by AppSettings.alertBattery.collectAsState()
    val alertCard by AppSettings.alertCard.collectAsState()
    val alertStopped by AppSettings.alertStopped.collectAsState()
    val gps by AppSettings.gpsToCamera.collectAsState()
    Page {
        Title(t("Ajustes", "Settings"))
        Explain(t("Toca un ajuste para cambiarlo.", "Tap a setting to change it."))

        val language by AppSettings.language.collectAsState()
        // En los dos idiomas a la vez, para encontrarlo aunque no se entienda el que está puesto
        Setting("Idioma / Language", language.label) {
            AppSettings.setLanguage(if (language == Language.SPANISH) Language.ENGLISH else Language.SPANISH)
        }

        SettingsTitle(t("Grabación", "Recording"))
        Setting(t("Parar", "Stop"), if (oneTap) t("un toque", "one tap") else t("dos toques", "two taps")) { AppSettings.setStopWithOneTap(!oneTap) }
        val clipOptions = listOf(0, 3, 6, 9)
        Setting(t("Parar sola", "Auto-stop"), if (clip == 0) t("nunca", "off") else t("a los $clip min", "after $clip min")) {
            AppSettings.setClipMinutes(clipOptions[(clipOptions.indexOf(clip) + 1) % clipOptions.size])
        }

        val videoLock by AppSettings.videoLock.collectAsState()
        Setting(
            t("Modo de la cámara", "Camera mode"),
            when (videoLock) {
                AppSettings.VIDEO_LOCK_VIDEO -> t("solo vídeo", "video only")
                AppSettings.VIDEO_LOCK_VIDEO_NIGHT -> t("vídeo o noche", "video or night")
                else -> t("el que tenga", "leave as is")
            },
        ) { AppSettings.setVideoLock((videoLock + 1) % 3) }
        Explain(t("Con «solo vídeo», si la cámara pasa a foto, timelapse, noche u otro modo sin estar grabando, BiciCAM la devuelve a vídeo. Con «vídeo o noche» también respeta el vídeo nocturno. El campo avisa del modo en cualquier caso.", "With \"video only\", if the camera goes to photo, timelapse, night or another mode while not recording, BiciCAM switches it back to video. With \"video or night\" it also leaves night video alone. The field shows the mode either way."))

        val blink by AppSettings.blinkPilot.collectAsState()
        Setting(t("Piloto intermitente", "Blinking REC light"), yesNo(blink)) { AppSettings.setBlinkPilot(!blink) }
        Explain(t("Mientras graba, el punto del campo parpadea cada segundo, como el piloto de una cámara. No gasta más batería.", "While recording, the dot in the field blinks every second, like a camera's tally light. It uses no extra battery."))

        SettingsTitle(t("Avisos durante la ruta", "In-ride alerts"))
        Setting(t("Batería baja", "Low battery"), yesNo(alertBattery)) { AppSettings.setAlertBattery(!alertBattery) }
        Setting(t("Tarjeta casi llena", "Card almost full"), yesNo(alertCard)) { AppSettings.setAlertCard(!alertCard) }
        Setting(t("Parada inesperada", "Unexpected stop"), yesNo(alertStopped)) { AppSettings.setAlertStopped(!alertStopped) }
        Explain(t("Batería: al 20 % y al 10 %. Tarjeta: cuando caben menos de 10 minutos. Solo las DJI dicen cuánto queda de tarjeta.", "Battery: at 20% and 10%. Card: when less than 10 minutes are left. Only DJI cameras report the card."))

        FrameSettings(canDrawFrame)

        SettingsTitle(t("GPS del Karoo", "Karoo GPS"))
        Setting(t("GPS a la cámara", "GPS to camera"), yesNo(gps)) { AppSettings.setGpsToCamera(!gps) }
        Explain(
            t("La cámara guarda con el vídeo por dónde ibas, a qué velocidad y a qué hora, y su app puede ponerlo encima de la imagen. Quien reciba el archivo original verá dónde se grabó. Los marcadores también apuntan la posición si esto está activado.", "The camera stores where you were, how fast and when with the video, and its app can overlay it on the picture. Anyone who gets the original file will see where it was recorded. Markers also log the position while this is on."),
        )

        SettingsTitle(t("Datos para editar el vídeo", "Data for video editing"))
        val fitMarks by AppSettings.fitMarks.collectAsState()
        Setting(t("Clips en la ruta", "Clips in ride file"), yesNo(fitMarks)) { AppSettings.setFitMarks(!fitMarks) }
        Explain(
            t("Apunta en el archivo de la ruta del Karoo cuándo graba la cámara y cada marcador, para saber después qué potencia, pulso o velocidad llevabas en cada momento del vídeo. Si subes la ruta a Hammerhead o a Strava, esos datos van con ella. Aún sin probar.", "Writes when the camera records, and every marker, into the Karoo ride file, so you can match power, heart rate and speed to any moment of the video. If you upload the ride to Hammerhead or Strava, that data goes with it. Not tested yet."),
        )

        SettingsTitle(t("Batería de la cámara", "Camera battery"))
        val saver by AppSettings.batterySaver.collectAsState()
        Setting(t("Ahorro de batería", "Battery saver"), yesNo(saver)) { AppSettings.setBatterySaver(!saver) }
        Explain(t("El GPS solo se manda a la cámara mientras graba, no también en espera. El primer segundo de cada clip puede quedar sin posición. Lo que más gasta es tener la cámara encendida: para eso está el ajuste de abajo.", "GPS is only sent to the camera while it records, not while it waits. The first second of each clip may have no position. What uses the most battery is keeping the camera on: the setting below is for that."))
        IdleOffSetting()
        Explain(t("Una DJI apagada desde el Karoo solo se vuelve a encender con su botón.", "A DJI camera switched off from the Karoo only comes back on with its own button."))

        if (hasCamera) {
            SettingsTitle(t("Cámara emparejada", "Paired camera"))
            ConfirmButton(t("Olvidar esta cámara", "Forget this camera"), t("Toca otra vez para olvidarla", "Tap again to forget it")) {
                CameraController.setPairedCamera(null)
                onClose()
            }
        }
        MainButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(t("Cerrar los ajustes", "Close settings")) }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SettingsTitle(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(text, style = MaterialTheme.typography.titleMedium)
}

/**
 * Un botón que borra algo: el primer toque solo pregunta y el segundo, antes de cuatro segundos, lo hace.
 * Es la misma lógica de los campos, para que un toque sin querer no se lleve nada por delante.
 */
@Composable
private fun ConfirmButton(text: String, confirmText: String, onConfirmed: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    LaunchedEffect(asking) {
        if (asking) {
            delay(4.seconds)
            asking = false
        }
    }
    PlainButton(
        onClick = {
            if (asking) {
                asking = false
                onConfirmed()
            } else {
                asking = true
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (asking) confirmText else text, fontWeight = if (asking) FontWeight.Bold else null)
    }
}

/**
 * El marco rojo que rodea la pantalla del Karoo mientras la cámara graba. Necesita que el Karoo deje a
 * BiciCAM mostrarse sobre otras apps; ese permiso se da en los ajustes de Android, no dentro de la app.
 */
@Composable
private fun FrameSettings(canDraw: Boolean) {
    val context = LocalContext.current
    val enabled by RecordingFrame.enabled.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    SettingsTitle(t("Marco rojo al grabar", "Red frame while recording"))
    if (!canDraw) {
        Explain(
            t("Mientras la cámara graba, BiciCAM puede rodear de rojo toda la pantalla del Karoo, estés en la página que estés. Para eso el Karoo tiene que dejarle mostrarse sobre otras apps.", "While the camera records, BiciCAM can draw a red frame around the whole Karoo screen, whatever page you are on. For that, the Karoo has to let it display over other apps."),
        )
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
        PlainButton(
            onClick = {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } catch (e: Exception) {
                    // El Karoo no trae esa pantalla de ajustes
                    message = t("Este Karoo no deja dar el permiso desde aquí. Hay que darlo una vez desde el ordenador, con el cable: ", "This Karoo won't let you grant this here. Do it once from a computer over USB: ") +
                        "adb shell appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow"
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(t("Dar permiso para el marco", "Grant permission for the frame"))
        }
    } else {
        Setting(t("Marco rojo", "Red frame"), yesNo(enabled)) { RecordingFrame.setEnabled(!enabled) }
        val stroke by RecordingFrame.strokeDp.collectAsState()
        val strokes = RecordingFrame.STROKES
        Setting(t("Grosor del marco", "Frame width"), stroke.toString()) {
            RecordingFrame.setStrokeDp(strokes[(strokes.indexOf(stroke) + 1) % strokes.size])
        }
        // Es una acción, no un ajuste: va como enlace para que no se confunda con ellos
        LinkButton(onClick = { RecordingFrame.test() }, modifier = Modifier.fillMaxWidth()) { Text(t("Probar el marco (5 s)", "Test the frame (5 s)")) }
    }
}

/** Columna con desplazamiento que deja sitio abajo para el botón de volver del Karoo. */
@Composable
private fun Page(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = BACK_BUTTON_SPACE),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

/** Emparejar una cámara: primero se elige la marca, porque cada una se empareja a su manera. */
@Composable
private fun PairingSection() {
    var brand by remember { mutableStateOf<Brand?>(null) }
    Title(t("Emparejar una cámara", "Pair a camera"))
    if (ENABLED_BRANDS.size == 1) {
        // Una sola marca: no hay nada que elegir
        DjiPairing(onBack = null)
        return
    }
    when (brand) {
        null -> {
            Explain(t("¿De qué marca es?", "Which brand is it?"))
            MainButton(onClick = { brand = Brand.DJI }, modifier = Modifier.fillMaxWidth()) { Text("DJI Osmo Action") }
            // En pruebas: pesa menos que la que sí está probada
            PlainButton(onClick = { brand = Brand.GOPRO }, modifier = Modifier.fillMaxWidth()) { Text("GoPro (experimental)") }
            PlainButton(onClick = { brand = Brand.INSTA360 }, modifier = Modifier.fillMaxWidth()) { Text("Insta360 (experimental)") }
        }
        Brand.GOPRO -> GoProPairing(onBack = { brand = null })
        Brand.DJI -> DjiPairing(onBack = { brand = null })
        Brand.INSTA360 -> InstaPairing(onBack = { brand = null })
    }
}

@Composable
private fun GoProPairing(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val found = remember { mutableStateListOf<FoundCamera>() }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var pairing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun startScan() {
        found.clear()
        message = t("Buscando cámaras GoPro", "Searching for GoPro cameras")
        scanJob = scope.launch {
            try {
                withTimeoutOrNull(20.seconds) {
                    scanForGoProCameras(context).collect { camera ->
                        if (found.none { it.address == camera.address }) found.add(camera)
                    }
                }
                message = if (found.isEmpty()) t("No aparece ninguna. Deja la cámara en su pantalla de emparejar y busca otra vez.", "No cameras found. Leave the camera on its pairing screen and search again.") else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EventLog.add(t("No se pudo buscar cámaras (${e.message})", "Camera search failed (${e.message})"))
                message = t("El Bluetooth no deja buscar ahora. Apágalo y enciéndelo en el Karoo.", "Bluetooth can't search right now. Switch it off and on again on the Karoo.")
            } finally {
                scanJob = null
            }
        }
    }

    fun pair(camera: FoundCamera) {
        scanJob?.cancel()
        pairing = true
        message = t("Emparejando con ${camera.name}. Si el Karoo o la cámara preguntan, acepta.", "Pairing with ${camera.name}. If the Karoo or the camera asks, accept.")
        scope.launch {
            try {
                CameraController.setPairedCamera(pairGoProCamera(context, camera, log = EventLog::add))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EventLog.add(t("No se ha podido emparejar (${e.message})", "Couldn't pair (${e.message})"))
                message = t("No se ha podido emparejar. Comprueba que la cámara sigue en su pantalla de emparejar y prueba otra vez.", "Couldn't pair. Check that the camera is still on its pairing screen and try again.")
            } finally {
                pairing = false
            }
        }
    }

    Explain(
        t("En la cámara, abre Preferencias, Conexiones inalámbricas, Conectar dispositivo, y elige la app de GoPro. Déjala en esa pantalla, busca aquí y toca la tuya.", "On the camera, open Preferences, Wireless Connections, Connect Device, and choose the GoPro app. Leave it on that screen, search here and tap yours."),
    )
    Explain(
        t("Para la HERO9 Black y posteriores. Aún no la hemos probado con ninguna GoPro: si la pruebas, cuéntanos qué tal.", "For the HERO9 Black and later. We haven't tried it with any GoPro yet: if you do, tell us how it went."),
    )
    if (!pairing) {
        if (scanJob == null) {
            MainButton(onClick = ::startScan, modifier = Modifier.fillMaxWidth()) { Text(t("Buscar cámaras GoPro", "Search for GoPro cameras")) }
        } else {
            PlainButton(onClick = { scanJob?.cancel() }, modifier = Modifier.fillMaxWidth()) { Text(t("Dejar de buscar", "Stop searching")) }
        }
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
    if (!pairing) {
        found.forEach { camera ->
            key(camera.address) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { pair(camera) }
                        .padding(vertical = 12.dp),
                ) {
                    Text(camera.name, style = MaterialTheme.typography.bodyLarge)
                    Text(camera.address, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
        LinkButton(onClick = { scanJob?.cancel(); onBack() }, modifier = Modifier.fillMaxWidth()) { Text(t("Elegir otra marca", "Choose another brand")) }
    }
}

@Composable
private fun DjiPairing(onBack: (() -> Unit)?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val found = remember { mutableStateListOf<FoundCamera>() }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var pairing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun startScan() {
        found.clear()
        message = t("Buscando cámaras DJI", "Searching for DJI cameras")
        scanJob = scope.launch {
            try {
                withTimeoutOrNull(20.seconds) {
                    scanForDjiCameras(context).collect { camera ->
                        if (found.none { it.address == camera.address }) found.add(camera)
                    }
                }
                message = if (found.isEmpty()) t("No aparece ninguna. Comprueba que la cámara está encendida y cerca, y busca otra vez.", "No cameras found. Make sure the camera is on and nearby, then search again.") else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Lo técnico, al registro; en pantalla, qué hacer
                EventLog.add(t("No se pudo buscar cámaras (${e.message})", "Camera search failed (${e.message})"))
                message = t("El Bluetooth no deja buscar ahora. Apágalo y enciéndelo en el Karoo.", "Bluetooth can't search right now. Switch it off and on again on the Karoo.")
            } finally {
                scanJob = null
            }
        }
    }

    fun pair(camera: FoundCamera) {
        scanJob?.cancel()
        pairing = true
        message = t("Conectando con ${camera.name}", "Connecting to ${camera.name}")
        scope.launch {
            try {
                val paired = pairDjiCamera(context, camera, log = EventLog::add) { code ->
                    message = t("La cámara tiene que enseñar el código $code. Si coincide, acéptalo en su pantalla.", "The camera should show code $code. If it matches, accept it on the camera.")
                }
                // La pantalla pasa a enseñar la cámara y su estado: no hace falta más confirmación
                CameraController.setPairedCamera(paired)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EventLog.add(t("No se ha podido emparejar (${e.message})", "Couldn't pair (${e.message})"))
                message = pairingFailed()
            } finally {
                pairing = false
            }
        }
    }

    Explain(
        t("Enciende la cámara y déjala cerca del Karoo. Busca aquí y toca la tuya; después acepta en la pantalla de la cámara.", "Switch the camera on and keep it close to the Karoo. Search here and tap yours; then accept on the camera's screen."),
    )
    Explain(
        t("Probada con la Osmo Action 6. Debería valer para la Osmo Action 4, la 5 Pro y la Osmo 360, pero aún no las hemos probado.", "Tested with the Osmo Action 6. It should also work with the Osmo Action 4, 5 Pro and Osmo 360, but we haven't tried them yet."),
    )
    if (!pairing) {
        if (scanJob == null) {
            MainButton(onClick = ::startScan, modifier = Modifier.fillMaxWidth()) { Text(t("Buscar cámaras DJI", "Search for DJI cameras")) }
        } else {
            PlainButton(onClick = { scanJob?.cancel() }, modifier = Modifier.fillMaxWidth()) { Text(t("Dejar de buscar", "Stop searching")) }
        }
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
    if (!pairing) {
        found.forEach { camera ->
            key(camera.address) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { pair(camera) }
                        .padding(vertical = 12.dp),
                ) {
                    Text(camera.name, style = MaterialTheme.typography.bodyLarge)
                    Text(camera.address, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
        if (onBack != null) LinkButton(onClick = { scanJob?.cancel(); onBack() }, modifier = Modifier.fillMaxWidth()) { Text(t("Elegir otra marca", "Choose another brand")) }
    }
}

private fun pairingFailed() = t(
    "No se ha podido emparejar. Apaga y enciende la cámara, déjala junto al Karoo, acepta en su pantalla y busca otra vez.",
    "Couldn't pair. Turn the camera off and on, keep it next to the Karoo, accept on its screen and search again.",
)

@Composable
private fun InstaPairing(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    // Lo que se ha conectado, a la espera de que se confirme que es la cámara y no otro aparato cercano
    var candidate by remember { mutableStateOf<InstaPairing?>(null) }

    fun start() {
        message = t("El Karoo ya aparece como «Insta360 GPS Remote». Elígelo en la cámara antes de dos minutos.", "The Karoo now shows up as \"Insta360 GPS Remote\". Choose it on the camera within two minutes.")
        job = scope.launch {
            try {
                candidate = pairInstaCamera(context, log = EventLog::add)
                message = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EventLog.add(t("No se ha podido emparejar (${e.message})", "Couldn't pair (${e.message})"))
                message = t("Ninguna cámara ha elegido al Karoo. En la cámara, abre el mando a distancia Bluetooth y elige «Insta360 GPS Remote».", "No camera has picked the Karoo yet. On the camera, open the Bluetooth remote menu and choose \"Insta360 GPS Remote\".")
            } finally {
                job = null
            }
        }
    }

    val pending = candidate
    if (pending != null) {
        val ending = pending.camera.address.takeLast(5)
        Explain(
            pending.serial?.let { t("Se ha conectado una cámara que dice tener el número de serie $it. Compáralo con el de tu cámara, que sale en sus ajustes (puede que aquí solo veas el final). Si coincide, es ella.", "A camera connected that reports serial number $it. Compare it with your camera's, shown in its settings (you may only see the end of it here). If it matches, this is it.") }
                ?: t("Se ha conectado un aparato (dirección terminada en $ending) que no ha dicho su número de serie, así que no se puede comprobar que sea tu cámara. Si dudas, di que no y repite el emparejamiento.", "A device connected (address ending in $ending) that did not report a serial number, so there is no way to check it is your camera. If in doubt, say no and pair again."),
        )
        MainButton(onClick = { CameraController.setPairedCamera(pending.camera, pending.serial) }, modifier = Modifier.fillMaxWidth()) { Text(t("Es mi cámara", "It's my camera")) }
        PlainButton(onClick = { candidate = null }, modifier = Modifier.fillMaxWidth()) { Text(t("No es la mía", "It's not mine")) }
        return
    }

    // Pasos de verdad y en orden: aquí la numeración sí cuenta algo
    Explain(t("Con Insta360 es la cámara la que busca al Karoo, que se presenta como su mando GPS.", "With Insta360 the camera looks for the Karoo, which poses as its GPS remote."))
    Explain(t("1. Enciende la cámara.", "1. Switch the camera on."))
    Explain(t("2. Toca aquí «Hacer de mando».", "2. Tap \"Act as remote\" here."))
    Explain(t("3. En la cámara: ajustes de Bluetooth, Bluetooth Remote, «Insta360 GPS Remote».", "3. On the camera: Bluetooth settings, Bluetooth Remote, \"Insta360 GPS Remote\"."))
    Explain(t("Es experimental: aún no la hemos probado con ninguna Insta360. Si la pruebas, cuéntanos qué tal en GitHub.", "This is experimental: we haven't tested it with any Insta360 yet. If you try it, tell us how it went on GitHub."))
    // El aviso va antes del botón: debajo quedaría fuera de la pantalla del Karoo
    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
    if (job == null) {
        MainButton(onClick = ::start, modifier = Modifier.fillMaxWidth()) { Text(t("Hacer de mando", "Act as remote")) }
    } else {
        PlainButton(onClick = { job?.cancel(); message = null }, modifier = Modifier.fillMaxWidth()) { Text(t("Dejar de hacer de mando", "Stop acting as remote")) }
    }
    if (job == null) {
        LinkButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(t("Elegir otra marca", "Choose another brand")) }
    }
}

/** Cómo son los campos y dónde se ponen: lo que hay que hacer después de emparejar. */
@Composable
private fun FieldsScreen(onClose: () -> Unit) {
    Page {
        Title(t("Los campos", "The fields"))
        Explain(
            t("Se añaden en el Karoo, al editar una página de datos de tu perfil: al elegir un campo, busca BiciCAM. El campo entero es el botón. El color dice el estado: azul marino, lista; rojo, grabando; ámbar, pide tu atención; gris claro, ahora no está disponible.", "Add them on the Karoo, when you edit a data page of your ride profile: when you pick a field, look for BiciCAM. The whole field is the button. The colour tells the state: navy, ready; red, recording; amber, needs your attention; light grey, not available right now."),
        )
        FieldSamples()
        MainButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(t("Cerrar", "Close")) }
        Spacer(Modifier.height(8.dp))
    }
}

/** Respuestas a las dudas corrientes, para consultarlas en el Karoo en plena ruta. */
@Composable
private fun HelpScreen(onClose: () -> Unit, onShowFields: () -> Unit) {
    val idleMinutes by CameraController.idleMinutes.collectAsState()
    val paired by CameraController.pairedCamera.collectAsState()
    // Dos textos, según el ajuste: con "nunca", la frase única quedaba rota
    val whyOff = if (idleMinutes > 0) {
        t("Con un campo a la vista, BiciCAM apaga la cámara a los $idleMinutes minutos sin grabar, para ahorrarle batería. Nunca corta una grabación.", "With a field on screen, BiciCAM switches the camera off after $idleMinutes minutes without recording, to save its battery. It never cuts a recording.")
    } else {
        t("BiciCAM no apaga la cámara si tú no se lo pides. Si quieres que lo haga tras unos minutos sin grabar, ponlo en Ajustes, «Apagarla sin grabar». Si no lo has puesto, la ha apagado su propio apagado automático o se ha quedado sin batería.", "BiciCAM doesn't switch the camera off unless you ask it to. To have it switch off after a few idle minutes, set \"Switch off when idle\" in Settings. If you haven't, the camera's own auto power-off switched it off, or its battery ran out.")
    }
    Page {
        Title(t("Ayuda", "Help"))
        PlainButton(onClick = onShowFields, modifier = Modifier.fillMaxWidth()) { Text(t("Ver cómo son los campos", "Preview the fields")) }
        HelpItem(
            t("Los campos se quedan en «Esperando»", "The fields stay on \"Waiting\""),
            t("BiciCAM está buscando la cámara. Comprueba que está encendida y cerca. Las cámaras solo atienden a un aparato a la vez: si la app de la cámara en tu móvil (DJI Mimo) está conectada a ella, ciérrala mientras ruedas.", "BiciCAM is looking for the camera. Check that it is on and close. Cameras talk to only one device at a time: if the camera's app on your phone (DJI Mimo) is connected to it, close it while you ride."),
        )
        if (Brand.DJI in ENABLED_BRANDS && paired?.brand in setOf(null, Brand.DJI)) {
            HelpItem(
                if (ENABLED_BRANDS.size > 1) t("DJI: la cámara no se enciende desde el Karoo", "DJI: the camera does not switch on from the Karoo") else t("La cámara no se enciende desde el Karoo", "The camera does not switch on from the Karoo"),
                t("El Karoo puede apagarla, pero no encenderla: las DJI solo despiertan con su propio mando. Pulsa el botón de la cámara y BiciCAM conecta sola en unos segundos, sin tocar nada en el Karoo. Si quieres poder grabar en cualquier momento de la ruta, pon en la cámara el apagado automático en «Nunca». La cámara gastará más batería mientras espera.", "The Karoo can switch it off, but not on: DJI cameras only wake up for their own remote. Press the camera's button and BiciCAM reconnects in a few seconds, without touching the Karoo. If you want to record at any point in the ride, set the camera's auto power-off to \"Never\". The camera will use more battery while it waits."),
            )
            HelpItem(
                if (ENABLED_BRANDS.size > 1) t("DJI: la cámara vuelve a preguntar si acepta la conexión", "DJI: the camera asks again whether to accept the connection") else t("La cámara vuelve a preguntar si acepta la conexión", "The camera asks again whether to accept the connection"),
                t("Pasa si la cámara ha perdido el emparejamiento (por ejemplo, tras restablecerla). Acepta en su pantalla. Si la rechazaste sin querer, ve a Ajustes, «Olvidar esta cámara», y emparéjala de nuevo.", "This happens when the camera has lost the pairing (for example after a reset). Accept on its screen. If you rejected it by mistake, go to Settings, \"Forget this camera\", and pair it again."),
            )
        }
        if (Brand.GOPRO in ENABLED_BRANDS && paired?.brand in setOf(null, Brand.GOPRO)) {
            HelpItem(
                t("GoPro: cómo se enciende y se apaga", "GoPro: switching on and off"),
                t("Apagada desde BiciCAM, la cámara queda dormida y el Karoo la despierta al tocar el campo (HERO9 Black y posteriores). Si la apagas del todo con su botón o le quitas la batería, enciéndela a mano. La GoPro usa su propio GPS: BiciCAM no le manda la posición del Karoo.", "Switched off from BiciCAM, the camera sleeps and the Karoo wakes it when you tap the field (HERO9 Black and later). If you switch it off completely with its button or remove the battery, switch it on by hand. The GoPro uses its own GPS: BiciCAM does not send it the Karoo's position."),
            )
        }
        if (Brand.INSTA360 in ENABLED_BRANDS && paired?.brand in setOf(null, Brand.INSTA360)) {
            HelpItem(
                t("Insta360: cómo se enciende y se apaga", "Insta360: switching on and off"),
                t("El Karoo se presenta como el mando GPS de Insta360. Mientras un campo de BiciCAM está a la vista, el Karoo emite la señal que despierta a tu cámara, así que se enciende sola. Para apagarla, usa el campo de encendido: si la apagas con su botón con un campo a la vista, se volverá a encender. Esa señal lleva el número de serie de tu cámara y otros aparatos cercanos pueden verla.", "The Karoo poses as the Insta360 GPS remote. While a BiciCAM field is on screen, the Karoo sends the signal that wakes your camera, so it switches on by itself. To switch it off, use the power field: if you switch it off with its button while a field is on screen, it will switch on again. That signal carries your camera's serial number and nearby devices can see it."),
            )
            HelpItem(
                t("Insta360: el campo de batería enseña horas y minutos", "Insta360: the battery field shows hours and minutes"),
                t("La cámara no le dice al mando el porcentaje de batería: le dice el tiempo de grabación que calcula que le queda, y eso es lo que se enseña.", "The camera does not tell the remote its battery percentage: it tells the recording time it estimates it has left, and that is what is shown."),
            )
            HelpItem(
                t("Insta360: la cámara deja de reconocer al Karoo", "Insta360: the camera no longer recognises the Karoo"),
                t("Puede pasar después de reiniciar el Karoo. Abre en la cámara el mando a distancia de los ajustes de Bluetooth y vuelve a elegir «Insta360 GPS Remote» con un campo de BiciCAM a la vista. Si la lista de la cámara tiene mandos repetidos, bórralos antes. Si has restablecido la cámara o la has cambiado por otra, olvídala en Ajustes y emparéjala de nuevo.", "This can happen after restarting the Karoo. On the camera, open Bluetooth Remote in the Bluetooth settings and pick \"Insta360 GPS Remote\" again with a BiciCAM field on screen. If the camera's list has duplicate remotes, delete them first. If you have reset the camera or swapped it for another one, forget it in Settings and pair it again."),
            )
        }
        HelpItem(
            t("¿Se para la grabación si cambio de página?", "Does the recording stop when I switch pages?"),
            t("No. Mientras graba, BiciCAM no suelta la cámara aunque cambies de página. Sin grabar, la suelta dos minutos después de dejar de ver sus campos. La grabación sigue en cualquier caso.", "No. While it records, BiciCAM keeps the camera connected even if you switch pages. When it's not recording, it disconnects two minutes after its fields leave the screen. The recording carries on either way."),
        )
        HelpItem(
            t("¿Por qué se ha apagado la cámara?", "Why did the camera switch off?"),
            whyOff,
        )
        HelpItem(
            t("¿Cómo cambio o quito un campo?", "How do I change or remove a field?"),
            t("Al editar las páginas de datos de tu perfil, en el Karoo. Durante la ruta, tocar un campo de BiciCAM graba o apaga la cámara, así que ahí no se abre el editor del Karoo.", "When you edit the data pages of your ride profile, on the Karoo. During a ride, tapping a BiciCAM field records or switches the camera off, so the Karoo's own editor does not open there."),
        )
        HelpItem(
            t("Si publicas tus vídeos", "If you publish your videos"),
            t("Respondes tú de las personas que salen en ellos, sobre todo de los menores. Tápalas o pide permiso.", "You are responsible for the people in them, especially minors. Blur them or ask first."),
        )
        MainButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(t("Cerrar la ayuda", "Close help")) }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun HelpItem(question: String, answer: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(question, style = MaterialTheme.typography.titleMedium)
        Text(answer, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AboutScreen(onClose: () -> Unit, onShowLog: () -> Unit) {
    Page {
        // El logo: el triángulo de BiciLAB dentro del marco rojo que la app pone al grabar
        Image(painter = painterResource(R.drawable.ic_launcher), contentDescription = "BiciCAM", modifier = Modifier.size(72.dp))
        Title(t("Acerca de BiciCAM", "About BiciCAM"))
        Text(t("Versión ${BuildConfig.VERSION_NAME}", "Version ${BuildConfig.VERSION_NAME}"), style = MaterialTheme.typography.titleMedium)
        Explain(t("Maneja tu cámara de acción desde el Karoo: graba y para, mira su batería y su tarjeta, deja marcadores y ten un marco rojo mientras graba. Probada con la DJI Osmo Action 6.", "Control your action camera from the Karoo: record and stop, check its battery and card, drop markers and get a red frame while it records. Tested with the DJI Osmo Action 6."))
        Explain(
            t("Hecha por BiciLAB (BiciLAB Factory SL). Usa la biblioteca karoo-ext de Hammerhead (licencia Apache 2.0) y el protocolo que DJI publica para su mando GPS.", "Made by BiciLAB (BiciLAB Factory SL). Uses Hammerhead's karoo-ext library (Apache License 2.0) and the protocol DJI publishes for its GPS remote."),
        )
        Explain(
            t("BiciCAM no se conecta a internet ni manda nada a BiciLAB. Guarda en este Karoo un registro de eventos y una lista de marcadores, que puedes borrar en Registro de eventos. Publica BiciLAB Factory SL, info@bicilab.es.", "BiciCAM does not connect to the internet or send anything to BiciLAB. It keeps an event log and a marker list on this Karoo, which you can delete under Event log. Published by BiciLAB Factory SL, info@bicilab.es."),
        )
        Explain(
            t("DJI y Osmo son marcas de sus dueños. BiciCAM no tiene relación con DJI ni con Hammerhead, ni cuenta con su respaldo.", "DJI and Osmo are trademarks of their owners. BiciCAM is not affiliated with or endorsed by DJI or Hammerhead."),
        )
        Explain(
            t("BiciCAM es software libre y se entrega sin garantía: lo usas bajo tu responsabilidad. En la medida en que la ley lo permite, sus autores no responden de daños en la cámara, el Karoo u otros aparatos, ni de grabaciones perdidas.", "BiciCAM is free software and comes without any warranty: you use it at your own risk. To the extent permitted by law, its authors are not liable for damage to the camera, the Karoo or other devices, or for lost recordings."),
        )
        // El aviso de derechos tiene que ir dentro de la app: lo pide la licencia MIT del código del que parte
        Text(t("Licencia MIT. © 2026 BiciLAB Factory SL y Horst Tellioglu.", "MIT License. © 2026 BiciLAB Factory SL and Horst Tellioglu."), style = MaterialTheme.typography.bodySmall)
        PlainButton(onClick = onShowLog, modifier = Modifier.fillMaxWidth()) { Text(t("Registro de eventos", "Event log")) }
        MainButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(t("Cerrar", "Close")) }
        Spacer(Modifier.height(8.dp))
    }
}

/** Lo que ha pasado con la cámara últimamente (lo más reciente, arriba). Sirve para saber por qué falló algo. */
@Composable
private fun EventLogScreen(onClose: () -> Unit) {
    val entries by EventLog.entries.collectAsState()
    Page {
        Title(t("Registro de eventos", "Event log"))
        if (entries.isEmpty()) {
            Explain(t("Aún no ha pasado nada. Empareja una cámara y aquí quedará apuntado cada paso.", "Nothing yet. Pair a camera and every step will be logged here."))
        } else {
            Explain(t("Lo que ha pasado con la cámara, del más reciente al más antiguo. Se guarda en este Karoo, en:", "What happened with the camera, newest first. It is saved on this Karoo, in:"))
            EventLog.path?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            ConfirmButton(t("Vaciar el registro", "Clear the log"), t("Toca otra vez para vaciar", "Tap again to clear")) { EventLog.clear() }
        }
        var markersDeleted by remember { mutableStateOf(false) }
        Explain(
            if (markersDeleted) {
                t("Marcadores borrados.", "Markers deleted.")
            } else {
                t("La lista de marcadores (hora y, con el GPS activado, posición) se guarda en:", "The marker list (time and, with GPS on, position) is saved in:")
            },
        )
        if (!markersDeleted) MarkerLog.path?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        ConfirmButton(t("Borrar los marcadores", "Delete markers"), t("Toca otra vez para borrar", "Tap again to delete")) {
            MarkerLog.clear()
            markersDeleted = true
        }
        entries.asReversed().take(150).forEach { line ->
            Text(line, style = MaterialTheme.typography.bodySmall)
        }
        MainButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(t("Cerrar", "Close")) }
    }
}

/** Va pasando por los tiempos que puede estar la cámara sin grabar antes de apagarse. */
@Composable
private fun IdleOffSetting() {
    val minutes by CameraController.idleMinutes.collectAsState()
    val options = listOf(0, 2, 5, 10, 15)
    Setting(t("Apagarla sin grabar", "Switch off when idle"), if (minutes == 0) t("nunca", "never") else t("a los $minutes min", "after $minutes min")) {
        CameraController.setIdleMinutes(options[(options.indexOf(minutes) + 1) % options.size])
    }
}

/**
 * Lo que no cabe en el campo de arriba: qué hacer si la cámara no está, y pantalla y tarjeta si está.
 * @return null si el campo ya lo dice todo
 */
private fun statusDetail(state: CameraState, brand: Brand, empty: CameraController.ProbablyEmpty?): String? {
    // Una DJI no se enciende desde el Karoo: se dice qué hacer en vez de ofrecer un botón que no puede cumplir
    val pressButton = t("Pulsa el botón de la cámara: BiciCAM conecta sola.", "Press the camera's button: BiciCAM reconnects by itself.")
    return when (state) {
        CameraState.NotPaired -> null
        CameraState.NoPermission -> t("Falta el permiso de Bluetooth.", "The Bluetooth permission is missing.")
        CameraState.Standby, CameraState.Searching ->
            if (empty != null) {
                t("Tenía un ${empty.percent} % de batería a las ${emptyTime(empty.atMillis)}: probablemente se ha agotado.", "Its battery was at ${empty.percent}% at ${emptyTime(empty.atMillis)}: probably flat.")
            } else if (brand == Brand.DJI) {
                t("BiciCAM está buscando la cámara. Si está apagada, pulsa su botón: BiciCAM conecta sola.", "BiciCAM is looking for the camera. If it is off, press its button: BiciCAM reconnects by itself.")
            } else {
                t("BiciCAM está buscando la cámara.", "BiciCAM is looking for the camera.")
            }
        CameraState.Starting -> t("La cámara ya contesta y está arrancando.", "The camera is responding and starting up.")
        CameraState.Asleep -> pressButton
        is CameraState.Off -> if (state.canWake) t("Toca el campo o el botón para encenderla.", "Tap the field or the button to switch it on.") else pressButton
        is CameraState.Connected -> buildString {
            if (!state.recording) state.screenOff?.let { append(if (it) t("Pantalla de la cámara apagada. ", "Camera screen off. ") else t("Pantalla de la cámara encendida. ", "Camera screen on. ")) }
            if (state.batteryPercent == null) state.runtime?.let { append(t("Le quedan $it de grabación. ", "$it of recording left. ")) }
            state.remainingSeconds?.let { append(t("En la tarjeta caben ${CardDataType.format(it)}. ", "${CardDataType.format(it)} left on the card. ")) }
            state.format?.let { append("$it. ") }
            if (state.busy) append(t("En la galería: sal de ahí para grabar. ", "In playback mode: exit it to record. "))
            if (state.markers > 0) append(t("Marcadores en este clip: ${state.markers}. ", "Markers in this clip: ${state.markers}. "))
        }.trim().ifEmpty { null }
    }
}

/** Espacio al final de las pantallas con desplazamiento, para que lo último no quede bajo el botón de volver. */
private val BACK_BUTTON_SPACE = 72.dp

/** "14:05", o "2 oct 2026, 14:05" si no fue hoy (en el idioma del aparato). */
private fun emptyTime(millis: Long): String {
    val format = if (DateUtils.isToday(millis)) {
        DateFormat.getTimeInstance(DateFormat.SHORT)
    } else {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    }
    return format.format(Date(millis))
}
