package es.bicilab.bicicam.extension

import es.bicilab.bicicam.camera.t
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import es.bicilab.bicicam.R
import es.bicilab.bicicam.camera.CameraController
import es.bicilab.bicicam.camera.CameraState
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ShowCustomStreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import es.bicilab.bicicam.camera.EventLog
import es.bicilab.bicicam.camera.crashGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * Contenido de un campo. Se lee como un campo de datos del Karoo, que es donde vive: una etiqueta pequeña
 * arriba a la izquierda que dice qué es, y debajo el valor en grande, alineado a la izquierda. El campo
 * entero es el botón, así que no lleva ayuda: solo aparece una línea abajo cuando hay que hacer algo
 * distinto de tocarlo (confirmar, pulsar el botón de la cámara, emparejar).
 *
 * El color entero dice el estado, para leerlo de reojo: rojo graba, azul marino lista, gris claro (el
 * piloto apagado) sin cámara, ámbar pide tu atención.
 *
 * @param label qué es el campo o en qué estado está, en minúscula de frase
 * @param value lo que se lee de un vistazo
 * @param icon dibujo delante del valor
 * @param hint qué hacer, solo cuando no basta con tocar el campo
 * @param labelBattery batería de la cámara, pequeña, a la derecha de la etiqueta (campos anchos)
 */
data class TileModel(
    val label: String,
    val value: String,
    val background: Color,
    val icon: TileIcon? = null,
    val hint: String? = null,
    val labelBattery: Int? = null,
    /** Encoge el valor (por debajo del tamaño que cabe), por ejemplo para un texto más largo. */
    val valueScale: Float = 1f,
    /** El valor va centrado en el campo en vez de a la izquierda: para los campos que son solo un botón (MARCAR). */
    val centered: Boolean = false,
    /**
     * Segunda línea en grande: la batería de la cámara como texto ("87 %"). Con ella el valor se encoge
     * para que quepan las dos.
     */
    val bigSecond: String? = null,
    /** Nivel para el dibujo de la batería que acompaña a [bigSecond]; null: solo el contorno. */
    val bigBattery: Int? = null,
    /** Con [bigSecond]: el valor a la izquierda y la batería a la derecha, en una fila, para campos anchos. */
    val sideBySide: Boolean = false,
    /** Con [bigSecond]: tercera fila, lo que cabe en la tarjeta, con el dibujo de una tarjeta delante. */
    val third: String? = null,
    /** Queda poco sitio en la tarjeta: la tercera fila cambia de color. */
    val thirdLow: Boolean = false,

) {
    /** Color del texto y los dibujos: blanco sobre marino y rojo; marino sobre gris claro y ámbar. */
    val foreground: Color get() = if (background == UNAVAILABLE || background == ATTENTION) INK else Color.White

    companion object {
        /** Cámara conectada y lista: el azul marino de la equipación BCLB 2027. */
        val READY = Color(0xFF1F2555)

        /** Grabando: el rojo del piloto de cualquier cámara. */
        val RECORDING = Color(0xFFD7261E)

        /**
         * Sin cámara a mano: el piloto apagado. Es el gris de BiciLAB (#61737F) al 30 % sobre blanco, con el
         * texto en marino. El gris entero pesaba lo mismo que el rojo (1,02:1 de luminancia) y al sol, o con
         * daltonismo, «sin cámara» y «grabando» se confundían.
         */
        val UNAVAILABLE = Color(0xFFCFD5D9)

        /** Pide tu atención: confirmar con otro toque, o la tarjeta casi llena. Con texto marino (6,2:1). */
        val ATTENTION = Color(0xFFF59300)

        /** El marino, como tinta sobre los fondos claros. */
        val INK = READY

        /**
         * La batería cambia de color según se gasta: verde con un 30 % o más, amarillo del 15 al 29 % y rojo
         * por debajo. Cada color tiene una versión para cada fondo, porque tiene que leerse igual sobre marino,
         * sobre rojo (grabando, que es cuando más importa) y sobre gris claro. Sobre el campo rojo van tonos
         * claros: el verde y el amarillo de los otros fondos daban 3,3:1 y 3,1:1 contra el rojo, menos que el
         * blanco del contador (5,0:1), y el porcentaje era lo que peor se leía justo al grabar (auditoría de
         * diseño del 21/11/2026). Ahí tampoco hay rojo que valga: la batería baja se queda en amarillo claro, y
         * lo dice el único segmento encendido.
         */
        fun batteryColor(percent: Int?, background: Color): Color {
            val ink = if (background == UNAVAILABLE || background == ATTENTION) INK else Color.White
            return when {
                percent == null || background == ATTENTION -> ink
                background == UNAVAILABLE -> when {
                    percent >= 30 -> Color(0xFF1B7F3B)
                    percent >= 15 -> Color(0xFF8A4A00)
                    else -> Color(0xFFA3160F)
                }
                background == RECORDING -> if (percent >= 30) Color(0xFFCFF7DA) else Color(0xFFFFE58F)
                percent >= 30 -> Color(0xFF7DE89A)
                percent >= 15 -> Color(0xFFFFC233)
                else -> Color(0xFFFF6B5E)
            }
        }

        /**
         * Campo para los estados en los que la cámara no se puede usar, o null si está conectada o apagada.
         * @param label la etiqueta del campo que lo pide
         * @param empty si la cámara desapareció con poca batería, con cuánta
         */
        fun unavailable(state: CameraState, label: String, compact: Boolean, empty: CameraController.ProbablyEmpty? = CameraController.probablyEmpty.value): TileModel? = when (state) {
            CameraState.NotPaired -> TileModel(label, t("Sin cámara", "No camera"), UNAVAILABLE, hint = if (compact) t("Abre BiciCAM", "Open BiciCAM") else t("Emparéjala en la app BiciCAM", "Pair it in the BiciCAM app"))
            // En el campo estrecho, una palabra: «No permission» salía a la mitad que sus vecinos, y qué hacer ya lo dice la ayuda
            CameraState.NoPermission -> TileModel(label, if (compact) t("Permiso", "Permission") else t("Sin permiso", "No permission"), UNAVAILABLE, hint = if (compact) t("Abre BiciCAM", "Open BiciCAM") else t("Abre la app BiciCAM", "Open the BiciCAM app"))
            CameraState.Standby, CameraState.Searching ->
                if (empty != null) {
                    // Desapareció con poca batería: probablemente se ha agotado
                    TileModel(
                        label,
                        t("¿Vacía?", "Flat?"),
                        UNAVAILABLE,
                        icon = TileIcon.Battery(empty.percent),
                        hint = if (compact) t("Tenía un ${empty.percent} %", "Was at ${empty.percent}%") else t("Tenía un ${empty.percent} % de batería", "Battery was at ${empty.percent}%"),
                    )
                } else {
                    TileModel(label, t("Esperando", "Waiting"), UNAVAILABLE)
                }
            // La cámara ha contestado y está arrancando: solo queda esperar
            CameraState.Starting -> TileModel(label, t("Arrancando", "Starting"), UNAVAILABLE)
            // El Karoo no puede despertarla: hay que pulsar su botón, y entonces conecta sola
            CameraState.Asleep -> TileModel(label, t("Dormida", "Asleep"), UNAVAILABLE, hint = pressItsButton(compact))
            is CameraState.Off, is CameraState.Connected -> null
        }

        fun pressItsButton(compact: Boolean) = if (compact) t("Pulsa su botón", "Press button") else t("Pulsa el botón de la cámara", "Press the camera's button")

        /** El porcentaje como se escribe en cada idioma: con espacio en español, sin él en inglés. */
        fun percent(value: Int) = t("$value %", "$value%")
    }
}

/** Batería que enseñan los campos de muestra mientras se edita una página. */
const val SAMPLE_BATTERY = 85

/**
 * En un campo de media anchura (unos 102 dp, con 8 dp de margen por lado) caben 15 letras de etiqueta o de
 * ayuda a 13 sp. Medido en pantalla: con 16 ya se corta. Lo vigila una prueba.
 */
const val COMPACT_TEXT_MAX = 15

sealed interface TileIcon {
    /** Batería en cuatro segmentos, encendidos hasta [percent] (solo el contorno si no se sabe). */
    data class Battery(val percent: Int?) : TileIcon

    /** El círculo de grabar. */
    data object Record : TileIcon

    /** El piloto de la cámara mientras graba: el mismo círculo, encendido o apagado cuando parpadea. */
    data class Pilot(val lit: Boolean) : TileIcon

    /** La cámara no está en vídeo: el dibujo de su modo (foto, reloj de timelapse, o vídeo tachado). */
    data class Mode(val mode: es.bicilab.bicicam.camera.CameraMode) : TileIcon

    /** El cuadrado de parar. */
    data object Stop : TileIcon
}

/**
 * Campo gráfico de la ruta que se puede tocar, sin la cabecera del Karoo, dibujado según el estado de la
 * cámara. La cámara se mantiene conectada mientras el campo está a la vista.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
abstract class ButtonTileDataType(extension: String, typeId: String) : DataTypeImpl(extension, typeId) {
    private val glance by lazy { GlanceRemoteViews() }

    /** Toque en el campo (ver [TileTapReceiver]), o null si no se puede tocar. */
    open fun clickAction(context: Context): Action? = null

    /** Valor más ancho, en múltiplos del tamaño de letra: con él se ajusta el valor al campo. */
    abstract val titleWidthEm: Float

    /**
     * Se llama cada segundo (por los contadores) y en cada cambio.
     * @param pending acción que espera el segundo toque o que se está ejecutando
     * @param compact el campo es estrecho (media anchura o menos): textos cortos y fuera lo secundario
     */
    abstract fun model(state: CameraState, pending: TapConfirmation.Pending?, compact: Boolean): TileModel

    /** Contenido de muestra mientras se edita la página del Karoo: ahí los campos no conectan con la cámara. */
    abstract fun previewModel(compact: Boolean): TileModel

    /**
     * Medidas con las que se dibuja el campo en el hueco que le da el Karoo.
     * @param valueSize tamaño del valor cuando solo hay etiqueta; [valueSizeWithHint], cuando además hay ayuda
     * @param labelSize tamaño de la etiqueta y de la ayuda
     */
    internal class Layout(
        val valueSize: Float,
        val valueSizeWithHint: Float,
        val labelSize: Float,
        val row: RowFit,
        val compact: Boolean,
        /** Tamaño del valor cuando el campo no lleva ni etiqueta ni ayuda: todo el alto es suyo. */
        val valueSizeAlone: Float = valueSize,
    )

    internal fun layoutFor(context: Context, config: ViewConfig): Layout {
        val metrics = context.resources.displayMetrics
        val fontScale = context.resources.configuration.fontScale
        // Una primera medida con la etiqueta más pequeña, para saber cuánto puede crecer la etiqueta
        // Campos de media anchura (30 de 60 columnas) o menos
        val compact = config.gridSize.first <= 30
        // El marco rojo tapa el borde de la pantalla mientras graba: el valor se mide dejándole sitio, para
        // que al encenderse no se coma las letras de la izquierda (ni, en los campos anchos, las de la derecha)
        val sidePadding = startPad(frameInset()) + endPad(frameInset(), compact)
        val rough = fitFontSize(context, config, reservedHeightDp = PAD_TOP + PAD_BOTTOM + LABEL_MIN_SP * LINE, widthEm = titleWidthEm, horizontalPaddingDp = sidePadding, lineHeight = LINE)
        // En un campo grande, una etiqueta de 13 sp se queda en nada: crece con el valor, hasta 18 sp
        val labelSize = (rough * 0.24f).coerceIn(LABEL_MIN_SP, LABEL_MAX_SP)
        val labelLine = labelSize * LINE * fontScale
        val reserved = PAD_TOP + PAD_BOTTOM + labelLine
        // Mismo ancho de valor en todos los campos para que casen entre sí
        val valueSize = fitFontSize(context, config, reserved, titleWidthEm, sidePadding, LINE)
        val valueSizeWithHint = fitFontSize(context, config, reserved + labelLine, titleWidthEm, sidePadding, LINE)
        // Para los campos con la batería en grande: lo que cabe a lo ancho y a lo alto, debajo de la etiqueta
        val row = RowFit(
            widthSp = (config.viewSize.first / metrics.density - sidePadding) / fontScale,
            heightSp = (config.viewSize.second / metrics.density - reserved) / LINE / fontScale,
            maxSp = config.textSize.toFloat(),
        )
        val valueSizeAlone = fitFontSize(context, config, PAD_TOP + PAD_BOTTOM, titleWidthEm, sidePadding, LINE)
        return Layout(valueSize, valueSizeWithHint, labelSize, row, compact, valueSizeAlone)
    }

    /**
     * Dibuja el campo con [model] tal como lo vería el Karoo en un hueco de [config]. Sirve para la muestra
     * y para el campo en vivo de la pantalla principal de la app.
     * @param clickable que el toque haga lo mismo que en una página del Karoo
     */
    internal suspend fun render(context: Context, config: ViewConfig, model: TileModel, clickable: Boolean = false): android.widget.RemoteViews {
        val layout = layoutFor(context, config)
        val onClick = if (clickable) clickAction(context) else null
        return glance.compose(context, DpSize.Unspecified) { ButtonTile(model, layout, titleWidthEm, onClick) }.remoteViews
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        // Esconde el contenedor de datos del Karoo, que si no tapa la parte baja de la vista
        emitter.onNext(ShowCustomStreamState("", null))
        val layout = layoutFor(context, config)
        Log.d("BiciCAM", "campo $typeId $config -> ${layout.valueSize}sp")
        // Mantiene la cámara conectada mientras el campo está a la vista (no en la vista previa del editor)
        if (!config.preview) {
            CameraController.acquire()
            FieldsSeen.note(context)
        }
        // En el editor de páginas el toque selecciona el campo: el campo no debe quedárselo
        val onClick = if (config.preview) null else clickAction(context)
        val compact = layout.compact
        val ticker = flow {
            while (true) {
                emit(Unit)
                delay(1000)
            }
        }
        val job = CoroutineScope(Dispatchers.IO + crashGuard).launch {
            combine(CameraController.state, TapConfirmation.pending, CameraController.probablyEmpty, ticker) { state, pending, _, _ ->
                // Mientras la cámara graba, el marco rojo está puesto: el contenido se aparta de él
                val framed = !config.preview && (state as? CameraState.Connected)?.let { it.recording && !it.stalled } == true
                (if (config.preview) previewModel(compact) else model(state, pending, compact)) to (if (framed) frameInset() else 0f)
            }
                .distinctUntilChanged()
                .conflate()
                .collect { (model, frame) ->
                    try {
                        val result = glance.compose(context, DpSize.Unspecified) { ButtonTile(model, layout, titleWidthEm, onClick, frame) }
                        emitter.updateView(result.remoteViews)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Un dibujo fallido no puede cortar las actualizaciones siguientes
                        Log.w("BiciCAM", "campo $typeId: no se pudo dibujar", e)
                        EventLog.add(t("No se pudo dibujar un campo (${e.javaClass.simpleName})", "A field could not be drawn (${e.javaClass.simpleName})"))
                    }
                    // El Karoo descarta las actualizaciones que llegan con menos de un segundo de separación
                    delay(1000)
                }
        }
        emitter.setCancellable {
            job.cancel()
            if (!config.preview) CameraController.release()
        }
    }

    companion object {
        /** Lo que tapa el marco rojo por cada lado mientras graba, más un respiro; 0 si el marco está quitado. */
        internal fun frameInset(): Float = if (RecordingFrame.enabled.value) RecordingFrame.strokeDp.value + 4f else 0f

        /** Margen izquierdo: el de siempre o, si es mayor, lo que tapa el marco. */
        internal fun startPad(frame: Float) = maxOf(PAD_SIDE, frame)

        /**
         * Margen derecho. Los campos anchos llegan a los dos bordes de la pantalla, así que también se apartan
         * del marco por la derecha; los estrechos, con el marco puesto, se quedan con un margen mínimo.
         */
        internal fun endPad(frame: Float, compact: Boolean) = if (frame <= PAD_SIDE) PAD_SIDE else if (compact) 4f else frame

        private const val PAD_TOP = 4f
        private const val PAD_BOTTOM = 2f
        private const val PAD_SIDE = 8f
        private const val LABEL_MIN_SP = 13f
        private const val LABEL_MAX_SP = 18f

        /** Las líneas de texto de Glance son más altas que la letra. */
        private const val LINE = 1.35f
    }
}

/**
 * Lo que cabe debajo de la etiqueta: el ancho y el alto disponibles, en sp, y el tamaño de letra más grande
 * que el Karoo quiere para un valor ([maxSp]).
 */
internal class RowFit(val widthSp: Float, val heightSp: Float, val maxSp: Float)

/**
 * Ancho de un texto en negrita, en múltiplos del tamaño de letra, carácter a carácter (Roboto negrita, que es
 * la letra del Karoo). Antes se contaba 0,6 por carácter, y un contador mide bastante menos: «1:02:45» son
 * unos 3,3 em y se contaba como 4,2, así que topaba con un ancho que no existía y salía pequeño (auditoría de
 * diseño del 21/11/2026, ronda 3). Los valores van un pelo por encima de la letra real: si se quedaran cortos,
 * el texto se cortaría, que es peor que salir algo más pequeño.
 */
internal fun textEm(text: String): Float = text.sumOf { charEm(it).toDouble() }.toFloat()

private fun charEm(c: Char): Float = when {
    c.isDigit() -> 0.58f
    c == ':' || c == '.' || c == ',' || c == '\'' -> 0.28f
    c == ' ' -> 0.26f
    c == '%' -> 0.76f
    c == '-' -> 0.36f
    c == '?' || c == '¿' -> 0.52f
    c == 'M' || c == 'W' -> 0.9f
    c == 'I' -> 0.3f
    c == 'E' || c == 'F' || c == 'L' || c == 'J' || c == 'S' -> 0.58f
    c.isUpperCase() -> 0.66f
    c == 'm' || c == 'w' -> 0.88f
    c == 'i' || c == 'l' || c == 'j' -> 0.29f
    c == 'f' || c == 't' || c == 'r' -> 0.38f
    c.isLowerCase() -> 0.57f
    else -> 0.6f
}

/** Lo que ocupa el dibujo que va delante del valor, en múltiplos del tamaño de letra. */
internal fun iconEm(icon: TileIcon?): Float = when (icon) {
    is TileIcon.Battery -> BATTERY_WIDTH_EM
    TileIcon.Record, TileIcon.Stop, is TileIcon.Pilot -> 1.0f
    is TileIcon.Mode -> 1.0f
    null -> 0f
}

/** El dibujo de la batería mide 0,6 em de alto y 1,9 veces eso de ancho, más el borne (0,12 del alto). */
private const val BATTERY_WIDTH_EM = 1.21f
private const val ICON_GAP_EM = 0.3f

/** En el campo de tres filas, lo que miden la batería y la tarjeta respecto al valor de arriba. */
private const val LOWER_ROW = 0.62f

/**
 * Parte del alto que ocupan las tres filas; el resto es aire por debajo, porque en la última fila de una
 * página el Karoo pone encima sus dos botones redondos (volver y vuelta), que ocupan el cuarto de abajo en
 * las esquinas. Con 0,86 tapaban el dibujo de la tarjeta (foto de Antun del 24/11/2026, versión 35).
 */
private const val THREE_ROWS_FILL = 0.76f

/** Por debajo de este tamaño, las filas de batería y tarjeta no se leen en marcha. */
private const val MIN_LOWER_SP = 14f

@Composable
private fun ValueIcon(icon: TileIcon, fontSize: Float, model: TileModel) {
    // La caja del texto deja más aire encima de las letras que debajo, así que centrar las cajas deja el
    // dibujo alto. Con 0,2 em de margen arriba queda en el centro visual del valor (medido en el Karoo).
    Box(modifier = GlanceModifier.padding(top = (fontSize * 0.2f).dp)) {
        when (icon) {
            is TileIcon.Battery -> BatteryIcon(icon.percent, heightDp = fontSize * 0.6f, model = model)
            TileIcon.Record -> Shape(sizeDp = fontSize * 0.62f, round = true, color = model.foreground)
            // Apagado no desaparece del todo: se queda la huella para que el contador no baile
            is TileIcon.Pilot -> Shape(sizeDp = fontSize * 0.62f, round = true, color = model.foreground.copy(alpha = if (icon.lit) 1f else 0.25f))
            TileIcon.Stop -> Shape(sizeDp = fontSize * 0.58f, round = false, color = model.foreground)
            is TileIcon.Mode -> Image(
                provider = ImageProvider(
                    when (icon.mode) {
                        es.bicilab.bicicam.camera.CameraMode.PHOTO -> R.drawable.ic_mode_photo
                        es.bicilab.bicicam.camera.CameraMode.TIMELAPSE, es.bicilab.bicicam.camera.CameraMode.HYPERLAPSE -> R.drawable.ic_mode_timelapse
                        else -> R.drawable.ic_mode_other
                    },
                ),
                contentDescription = null,
                colorFilter = ColorFilter.tint(ColorProvider(model.foreground)),
                modifier = GlanceModifier.size((fontSize * 0.9f).dp, (fontSize * 0.75f).dp),
            )
        }
    }
}

/** El valor con su dibujo delante: «● REC», «■ 03:12». */
@Composable
private fun Value(model: TileModel, fontSize: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        model.icon?.let { icon ->
            ValueIcon(icon, fontSize, model)
            Spacer(GlanceModifier.width((fontSize * ICON_GAP_EM).dp))
        }
        // En el campo de batería, el número lleva el color de la batería
        val color = (model.icon as? TileIcon.Battery)?.let { TileModel.batteryColor(it.percent, model.background) } ?: model.foreground
        Text(
            text = model.value,
            style = TextStyle(color = ColorProvider(color), fontSize = fontSize.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
    }
}

/** La batería en grande: su dibujo y el porcentaje, del color que toca según lo que queda. */
@Composable
private fun BigBattery(model: TileModel, text: String, fontSize: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = GlanceModifier.padding(top = (fontSize * 0.2f).dp)) {
            BatteryIcon(model.bigBattery, heightDp = fontSize * 0.6f, model = model)
        }
        Spacer(GlanceModifier.width((fontSize * ICON_GAP_EM).dp))
        Text(
            text = text,
            style = TextStyle(color = ColorProvider(TileModel.batteryColor(model.bigBattery, model.background)), fontSize = fontSize.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
    }
}

/** Tamaño de las filas de abajo si el campo se pintara en tres filas. */
private fun threeRowsLower(model: TileModel, second: String, third: String, layout: ButtonTileDataType.Layout, rowHeight: Float): Float {
    val fontSize = minOf(layout.row.maxSp, rowHeight / (1f + 2 * LOWER_ROW) * THREE_ROWS_FILL, layout.row.widthSp / valueEm(model))
    val lowerEm = maxOf(BATTERY_WIDTH_EM + ICON_GAP_EM + textEm(second), BATTERY_WIDTH_EM + ICON_GAP_EM + textEm(third))
    return minOf(fontSize * LOWER_ROW, layout.row.widthSp / lowerEm)
}

/** Por debajo de este tamaño de las filas de abajo, un campo ancho pasa de tres filas a dos columnas. */
private const val TWO_COLUMNS_BELOW_SP = 24f

/**
 * El punto de grabar cuando va encima del valor: mide 0,45 de la letra (0,73 x 0,62). Con 0,34 se leía como
 * un acento sobre la E, no como el piloto de una cámara (auditoría de diseño del 21/11/2026).
 */
private const val DOT_ABOVE_EM = 0.73f

/** Alto que se le reserva a ese punto, con su aire, en múltiplos del tamaño de la letra. */
private const val DOT_ABOVE_HEIGHT_EM = 0.7f

/** El valor de la izquierda en los campos anchos: su tamaño y si el punto va encima o delante. */
internal class LeftFit(val size: Float, val above: Boolean) {
    /** Alto que ocupa, en las mismas unidades que el tamaño de letra. */
    val height: Float get() = if (above) size * (1f + DOT_ABOVE_HEIGHT_EM) else size
}

/**
 * Cuánto puede crecer el valor de la izquierda con [room] de ancho y [rowHeight] de alto. El punto de grabar
 * va encima de la palabra, centrado, cuando así la letra sale más grande (decisión de Antun del 14/11/2026);
 * en un campo bajo le quitaría altura, y ahí sigue delante.
 */
internal fun leftFit(textEm: Float, icon: TileIcon?, room: Float, rowHeight: Float, maxSp: Float): LeftFit {
    val dot = icon == TileIcon.Record || icon is TileIcon.Pilot
    val withIconEm = textEm + iconEm(icon) + (if (icon != null) ICON_GAP_EM else 0f)
    val aboveSize = minOf(maxSp, rowHeight / (1f + DOT_ABOVE_HEIGHT_EM), room / textEm).coerceAtLeast(10f)
    val frontSize = minOf(maxSp, rowHeight * 0.95f, room / withIconEm).coerceAtLeast(10f)
    return if (dot && aboveSize > frontSize) LeftFit(aboveSize, true) else LeftFit(frontSize, false)
}

/** La columna de la derecha (batería, tarjeta) no pasa de esta parte del tamaño del valor: el valor manda. */
internal const val RIGHT_MAX_OF_VALUE = 0.65f

/** Reparto de un campo ancho: el valor a la izquierda y, a la derecha, una columna de [rightEm] de ancho. */
internal class WideFit(val left: LeftFit, val rightSize: Float)

/**
 * Reparte el ancho entre el valor y la columna derecha. La derecha empieza en [rightStart] y se encoge hasta
 * no pasar de [RIGHT_MAX_OF_VALUE] del valor: con un contador largo («1:02:45») se quedaba la mitad del ancho
 * y el contador salía casi tan pequeño como la batería (auditoría de diseño del 21/11/2026, ronda 2). Nunca
 * baja de [rightMin], que es el mínimo que se lee.
 */
internal fun wideFit(textEm: Float, icon: TileIcon?, rightEm: Float, rightStart: Float, rightMin: Float, widthSp: Float, rowHeight: Float, maxSp: Float): WideFit {
    var right = rightStart
    var left = leftFit(textEm, icon, widthSp - (rightEm + SIDE_GAP_EM) * right, rowHeight, maxSp)
    repeat(6) {
        val wanted = maxOf(rightMin, left.size * RIGHT_MAX_OF_VALUE)
        if (right <= wanted) return WideFit(left, right)
        right = maxOf(wanted, right * 0.9f)
        left = leftFit(textEm, icon, widthSp - (rightEm + SIDE_GAP_EM) * right, rowHeight, maxSp)
    }
    return WideFit(left, right)
}

/** El valor de la izquierda de un campo ancho, con el punto encima o delante según [fit]. */
@Composable
private fun LeftValue(model: TileModel, fit: LeftFit) {
    // El punto, centrado sobre la palabra, como el piloto de una cámara
    if (fit.above) Column(horizontalAlignment = Alignment.CenterHorizontally) {
        model.icon?.let { ValueIcon(it, fit.size * DOT_ABOVE_EM, model) }
        Text(
            text = model.value,
            style = TextStyle(color = ColorProvider(model.foreground), fontSize = fit.size.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
    } else {
        Value(model, fit.size)
    }
}

/** Lo que cabe en la tarjeta, con su dibujo delante, del ancho del dibujo de la batería para que los dos números casen. */
@Composable
private fun CardRow(model: TileModel, third: String, lowerSize: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // La tarjeta ocupa el mismo ancho que el dibujo de la batería, para que los dos números casen
        val color = if (model.thirdLow) TileModel.batteryColor(20, model.background) else model.foreground
        Box(modifier = GlanceModifier.width((lowerSize * BATTERY_WIDTH_EM).dp).padding(top = (lowerSize * 0.2f).dp), contentAlignment = Alignment.CenterStart) {
            Image(
                provider = ImageProvider(R.drawable.ic_sd),
                contentDescription = null,
                colorFilter = ColorFilter.tint(ColorProvider(color)),
                modifier = GlanceModifier.size((lowerSize * 0.6f).dp, (lowerSize * 0.8f).dp),
            )
        }
        Spacer(GlanceModifier.width((lowerSize * ICON_GAP_EM).dp))
        Text(text = third, style = TextStyle(color = ColorProvider(color), fontSize = lowerSize.sp, fontWeight = FontWeight.Bold), maxLines = 1)
    }
}

/** Tamaño de letra con el valor y la batería en una fila. */
private fun sideSize(model: TileModel, second: String, layout: ButtonTileDataType.Layout, rowHeight: Float): Float {
    val rightEm = BATTERY_WIDTH_EM + ICON_GAP_EM + textEm(second)
    return minOf(layout.row.maxSp, rowHeight, layout.row.widthSp / (valueEm(model) + SIDE_GAP_EM + rightEm)).coerceAtLeast(10f)
}

/** Tamaño de letra del valor con la batería debajo. */
private fun stackedSize(model: TileModel, layout: ButtonTileDataType.Layout, rowHeight: Float): Float =
    minOf(layout.row.maxSp, rowHeight / 1.85f, layout.row.widthSp / valueEm(model)).coerceAtLeast(10f)

internal const val SIDE_GAP_EM = 0.7f

private fun valueEm(model: TileModel) = textEm(model.value) + iconEm(model.icon) + (if (model.icon != null) ICON_GAP_EM else 0f)

@Composable
private fun ButtonTile(model: TileModel, layout: ButtonTileDataType.Layout, titleWidthEm: Float, onClick: Action?, frameInset: Float = 0f) {
    // Sobre rojo y ámbar, la etiqueta a medio tono no llega a leerse bien: ahí va con toda la tinta
    val full = model.background == TileModel.RECORDING || model.background == TileModel.ATTENTION
    val small = TextStyle(color = ColorProvider(model.foreground.copy(alpha = if (full) 1f else 0.85f)), fontSize = layout.labelSize.sp)
    val second = model.bigSecond
    // Las tres filas solo se pintan si el campo tiene altura para que las de abajo se lean (14 sp o más)
    // Sin etiqueta que pintar, esa línea también cuenta como altura para las filas
    val spare = if (model.label.isEmpty() && model.labelBattery == null) layout.labelSize else 0f
    val third = model.third?.takeIf { second != null && (layout.row.heightSp + spare) / (1f + 2 * LOWER_ROW) * LOWER_ROW >= MIN_LOWER_SP }
    // En un campo bajo, la tarjeta sube a la línea de la etiqueta, con su dibujo, para que no se confunda con
    // la batería ni con el tiempo de grabación
    // Campo ancho: si en tres filas las de abajo quedarían pequeñas, el valor va grande a la izquierda y la
    // batería y la tarjeta, una encima de otra, a la derecha. Solo si esas dos se leen (13 sp o más)
    val card = model.third
    val twoColumns = second != null && card != null && !layout.compact &&
        threeRowsLower(model, second, card, layout, layout.row.heightSp + spare) < TWO_COLUMNS_BELOW_SP &&
        (layout.row.heightSp + spare) / 2f * 0.92f >= MIN_LOWER_SP - 1f
    val cardInLabel = model.third?.takeIf { second != null && third == null && !twoColumns }
    // La línea de arriba solo existe si tiene algo que decir. Sin ella, su altura es para el valor
    val topLine = model.label.isNotEmpty() || cardInLabel != null || model.labelBattery != null
    val rowHeight = layout.row.heightSp + (if (topLine) 0f else layout.labelSize) - (if (model.hint != null && second != null) layout.labelSize else 0f)
    // El valor de un campo de un solo dato: el tamaño base cabe en titleWidthEm; los textos más largos se
    // encogen para caber también (una letra negrita ocupa unos 0,6 em; el dibujo cuenta en múltiplos de la letra)
    val singleBase = when {
        topLine && model.hint != null -> layout.valueSizeWithHint
        topLine || model.hint != null -> layout.valueSize
        else -> layout.valueSizeAlone
    }
    val singleSize = singleBase * minOf(1f, titleWidthEm / (textEm(model.value) + iconEm(model.icon))) * model.valueScale
    // Si cabe, el valor se centra en el alto entero del campo y no en lo que deja la cabecera: así, en una
    // fila de campos, los valores van a la misma altura lleven o no etiqueta (desde que los de REC no la
    // llevan, «Dormida» y «OFF» quedaban a alturas distintas; auditoría de diseño del 21/11/2026)
    // El campo de solo REC: sin cabecera, el valor crece hasta lo que cabe de verdad, con la misma regla del
    // punto que los anchos. Antes se medía para «0:00:00» y «REC» salía más pequeño que en el campo de al lado
    val alone = (model.icon == TileIcon.Record || model.icon is TileIcon.Pilot) && second == null && !topLine && model.hint == null && !model.centered
    val aloneFit = if (alone) leftFit(textEm(model.value), model.icon, layout.row.widthSp, rowHeight, layout.row.maxSp) else null
    val headerLines = (if (topLine) 1 else 0) + (if (model.hint != null) 1 else 0)
    val centerInField = second == null && (alone || centersInField(layout.row.heightSp + layout.labelSize, singleSize, headerLines * layout.labelSize))
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(model.background))
            .let { if (onClick != null) it.clickable(onClick) else it }
            // Con el marco rojo puesto, el contenido se aparta del borde para que el marco no se coma las letras
            .padding(
                start = ButtonTileDataType.startPad(frameInset).dp,
                top = 4.dp,
                end = ButtonTileDataType.endPad(frameInset, layout.compact).dp,
                bottom = 2.dp,
            ),
    ) {
      Column(modifier = GlanceModifier.fillMaxSize()) {
        // Etiqueta arriba a la izquierda, como en los campos del propio Karoo; a su derecha, la batería en pequeño
        if (topLine) Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // En un campo estrecho no caben el nombre y la tarjeta: se queda la tarjeta, que es el dato
            if (model.label.isNotEmpty() && (cardInLabel == null || !layout.compact)) Text(text = model.label, style = small, maxLines = 1)
            cardInLabel?.let { card ->
                if (!layout.compact) Spacer(GlanceModifier.defaultWeight())
                val color = if (model.thirdLow) TileModel.batteryColor(20, model.background) else small.color.getColor(LocalContext.current)
                Image(
                    provider = ImageProvider(R.drawable.ic_sd),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(ColorProvider(color)),
                    modifier = GlanceModifier.size((layout.labelSize * 0.66f).dp, (layout.labelSize * 0.88f).dp),
                )
                Spacer(GlanceModifier.width(4.dp))
                Text(text = card, style = TextStyle(color = ColorProvider(color), fontSize = layout.labelSize.sp), maxLines = 1)
            }
            // Solo el dibujo: el número en pequeño no se leería en color, y el dibujo ya dice el nivel
            model.labelBattery?.let { percent ->
                Spacer(GlanceModifier.defaultWeight())
                BatteryIcon(percent, heightDp = layout.labelSize * 0.8f, model = model)
            }
        }
        // La ayuda solo sale cuando hay que hacer algo distinto de tocar: así, cuando sale, se lee. Va justo
        // debajo de la etiqueta y no al pie del campo, porque abajo el Karoo pone sus botones de pasar página
        // y la tapaban (visto en un Karoo con el campo en la última fila)
        model.hint?.let { Text(text = it, style = small, maxLines = 1) }
        when {
            // Campo ancho de altura normal: tres filas quedarían pequeñas y apiñadas a la izquierda. El valor
            // va grande a la izquierda y, a la derecha, la batería encima de la tarjeta (pedido por Antun el
            // 14/11/2026: que el campo grande se lea más y no pierda la tarjeta)
            second != null && card != null && twoColumns -> {
                val rightEm = maxOf(BATTERY_WIDTH_EM + ICON_GAP_EM + textEm(second), BATTERY_WIDTH_EM + ICON_GAP_EM + textEm(card))
                val rightStart = minOf(rowHeight / 2f * 0.92f, layout.row.widthSp * 0.5f / rightEm).coerceAtLeast(10f)
                val wide = wideFit(textEm(model.value), model.icon, rightEm, rightStart, minOf(rightStart, MIN_LOWER_SP + 2f), layout.row.widthSp, rowHeight, layout.row.maxSp)
                val rightSize = wide.rightSize
                val fit = wide.left
                // El punto de grabar va encima de la palabra y no delante: así no le quita ancho y «REC» (o el
                // contador) crece hasta casi el doble (pedido por Antun el 14/11/2026)
                Row(modifier = GlanceModifier.defaultWeight().fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    LeftValue(model, fit)
                    Spacer(GlanceModifier.defaultWeight())
                    Column {
                        BigBattery(model, second, rightSize)
                        CardRow(model, card, rightSize)
                    }
                }
            }
            // Tres filas: el valor arriba, debajo la batería con su dibujo y debajo lo que cabe en la tarjeta con el suyo
            second != null && third != null -> {
                // Las tres filas no llenan el alto: van arriba y dejan aire debajo, que es donde el Karoo pone sus
                // botones de pasar página
                val fontSize = minOf(layout.row.maxSp, rowHeight / (1f + 2 * LOWER_ROW) * THREE_ROWS_FILL, layout.row.widthSp / valueEm(model)).coerceAtLeast(10f)
                val lowerEm = maxOf(BATTERY_WIDTH_EM + ICON_GAP_EM + textEm(second), BATTERY_WIDTH_EM + ICON_GAP_EM + textEm(third))
                val lowerSize = minOf(fontSize * LOWER_ROW, layout.row.widthSp / lowerEm).coerceAtLeast(10f)
                Column(modifier = GlanceModifier.defaultWeight().fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Value(model, fontSize)
                    BigBattery(model, second, lowerSize)
                    CardRow(model, third, lowerSize)
                }
            }
            // REC a la izquierda y la batería a la derecha, del mismo tamaño. También en un campo estrecho
            // de una sola fila: ahí, uno encima del otro quedan diminutos y en fila se leen (visto por Antun
            // en su Karoo el 14/11/2026: en un campo de una fila la batería no cabía)
            // Campo ancho de REC y batería: la batería, algo menor, a la derecha, y el valor con todo el ancho que
            // sobra y la misma regla del punto que el campo de dos columnas. Con los dos del mismo tamaño, REC
            // salía más pequeño que en el campo de tres datos en el mismo hueco (auditoría del 21/11/2026)
            second != null && model.sideBySide && !layout.compact -> {
                val rightEm = BATTERY_WIDTH_EM + ICON_GAP_EM + textEm(second)
                val rightStart = minOf(layout.row.maxSp, rowHeight * 0.6f, layout.row.widthSp * 0.45f / rightEm).coerceAtLeast(10f)
                val wide = wideFit(textEm(model.value), model.icon, rightEm, rightStart, minOf(rightStart, MIN_LOWER_SP + 2f), layout.row.widthSp, rowHeight, layout.row.maxSp)
                val rightSize = wide.rightSize
                val fit = wide.left
                Row(modifier = GlanceModifier.defaultWeight().fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    LeftValue(model, fit)
                    Spacer(GlanceModifier.defaultWeight())
                    BigBattery(model, second, rightSize)
                }
            }
            // REC a la izquierda y la batería a la derecha, del mismo tamaño, en un campo estrecho de una sola
            // fila: ahí, uno encima del otro quedan diminutos y en fila se leen (visto por Antun en su Karoo el
            // 14/11/2026: en un campo de una fila la batería no cabía)
            second != null && (model.sideBySide || sideSize(model, second, layout, rowHeight) > stackedSize(model, layout, rowHeight)) -> {
                val fontSize = sideSize(model, second, layout, rowHeight)
                Row(modifier = GlanceModifier.defaultWeight().fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Value(model, fontSize)
                    Spacer(GlanceModifier.defaultWeight())
                    BigBattery(model, second, fontSize)
                }
            }
            // Dos líneas grandes: el valor se lleva algo más de la mitad del alto; la batería, un poco menor, debajo
            second != null -> {
                val fontSize = stackedSize(model, layout, rowHeight)
                val secondSize = minOf(fontSize * 0.85f, layout.row.widthSp / (BATTERY_WIDTH_EM + ICON_GAP_EM + textEm(second))).coerceAtLeast(10f)
                Column(modifier = GlanceModifier.defaultWeight().fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Value(model, fontSize)
                    BigBattery(model, second, secondSize)
                }
            }
            centerInField -> Unit
            else -> Box(modifier = GlanceModifier.defaultWeight().fillMaxWidth(), contentAlignment = if (model.centered) Alignment.Center else Alignment.CenterStart) {
                Value(model, singleSize)
            }
        }
      }
      if (centerInField) Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = if (model.centered) Alignment.Center else Alignment.CenterStart) {
          if (aloneFit != null) LeftValue(model, aloneFit) else Value(model, singleSize)
      }
    }
}

/**
 * ¿Cabe el valor centrado en el alto entero del campo sin pisar la cabecera?
 * @param fieldHeight alto del campo, [valueSize] el del valor y [headerHeight] lo que ocupan etiqueta y ayuda,
 * todo en las mismas unidades (tamaño de letra que cabe)
 */
internal fun centersInField(fieldHeight: Float, valueSize: Float, headerHeight: Float): Boolean =
    (fieldHeight - valueSize) / 2f >= headerHeight

/** Círculo de grabar o cuadrado de parar. */
@Composable
private fun Shape(sizeDp: Float, round: Boolean, color: Color) {
    Box(
        modifier = GlanceModifier
            .size(sizeDp.dp)
            .let { if (round) it.cornerRadius((sizeDp / 2).dp) else it }
            .background(ColorProvider(color)),
    ) {}
}

/**
 * Segmentos encendidos de los cuatro que tiene la batería. Van con los colores: en verde quedan dos o más,
 * en amarillo uno y en rojo ninguno, para que el nivel se lea también sin distinguir los colores.
 */
internal fun batterySegments(percent: Int?): Int = when {
    percent == null -> 0
    percent >= 80 -> 4
    percent >= 55 -> 3
    percent >= 30 -> 2
    percent >= 15 -> 1
    else -> 0
}

/**
 * Batería de aparato: contorno recto con cuatro segmentos, que se lee por cuartos de un vistazo, y su borne
 * a la derecha. Los segmentos van en verde, amarillo o rojo según lo que queda (ver [TileModel.batteryColor]).
 */
@Composable
private fun BatteryIcon(percent: Int?, heightDp: Float, model: TileModel) {
    val ink = model.foreground
    val width = heightDp * 1.9f
    val stroke = maxOf(2f, heightDp * 0.1f)
    val gap = maxOf(1f, heightDp * 0.06f)
    val between = 1f
    val innerHeight = heightDp - 2 * (stroke + gap)
    val segment = (width - 2 * (stroke + gap) - 3 * between) / 4
    val fill = TileModel.batteryColor(percent, model.background)
    val lit = batterySegments(percent)
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Cuerpo: caja de tinta con el interior del color del fondo, como un contorno
        Box(
            modifier = GlanceModifier
                .size(width.dp, heightDp.dp)
                .background(ColorProvider(ink))
                .padding(stroke.dp),
        ) {
            Row(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(model.background))
                    .padding(gap.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(4) { index ->
                    if (index > 0) Spacer(GlanceModifier.width(between.dp))
                    Box(
                        modifier = GlanceModifier
                            .size(segment.dp, innerHeight.dp)
                            .background(ColorProvider(if (index < lit) fill else model.background)),
                    ) {}
                }
            }
        }
        // Borne
        Box(
            modifier = GlanceModifier
                .size((heightDp * 0.12f).dp, (heightDp * 0.4f).dp)
                .background(ColorProvider(ink)),
        ) {}
    }
}
