package es.bicilab.bicicam.extension

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import es.bicilab.bicicam.camera.CameraController
import es.bicilab.bicicam.camera.CameraState
import es.bicilab.bicicam.camera.EventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Marco rojo alrededor de toda la pantalla del Karoo mientras la cámara graba: el piloto de grabación,
 * visible en cualquier página y en cualquier app, no solo donde hay un campo de BiciCAM.
 *
 * Es una ventana superpuesta de Android, así que el Karoo tiene que dar a BiciCAM el permiso de mostrarse
 * sobre otras apps. No recibe toques: todo lo que hay debajo se sigue manejando igual.
 */
object RecordingFrame {
    private const val TAG = "BiciCAM"
    private const val PREFS = "frame"
    private const val KEY_ENABLED = "enabled"

    private const val KEY_STROKE = "stroke_dp"

    /** Grosores que se pueden elegir en los ajustes: salieron de probar el marco al sol en un Karoo. */
    val STROKES = listOf(5, 10, 13)
    private const val RED = 0xFFD7261E.toInt()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var appContext: Context
    private var view: View? = null

    private val _enabled = MutableStateFlow(true)

    /** El usuario quiere el marco. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _strokeDp = MutableStateFlow(13)

    /** Grosor elegido, en dp. */
    val strokeDp: StateFlow<Int> = _strokeDp.asStateFlow()

    /** Marco de prueba, sin grabar. */
    private val demo = MutableStateFlow(false)

    /** Sube cuando hay que volver a mirar si ya se tiene el permiso. */
    private val refresh = MutableStateFlow(0)

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _enabled.value = prefs.getBoolean(KEY_ENABLED, true)
        _strokeDp.value = prefs.getInt(KEY_STROKE, 13)
        val recording = CameraController.state.map { (it as? CameraState.Connected)?.let { c -> c.recording && !c.stalled } == true }.distinctUntilChanged()
        scope.launch {
            combine(recording, _enabled, demo, refresh, _strokeDp) { rec, on, test, _, _ -> test || (rec && on) }.collect { wanted ->
                // Se quita y se vuelve a poner: así un cambio de grosor se ve al momento
                hide()
                if (wanted) show()
            }
        }
    }

    /** El Karoo deja a BiciCAM dibujar sobre las demás apps. */
    fun hasPermission(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun setEnabled(on: Boolean) {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
        _enabled.value = on
    }

    /** Cambia el grosor y enseña el marco unos segundos para verlo. */
    fun setStrokeDp(dp: Int) {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_STROKE, dp).apply()
        _strokeDp.value = dp
        test()
    }

    /** Vuelve a mirar el permiso, por si se acaba de dar con una grabación en marcha. */
    fun recheck() = refresh.update { it + 1 }

    /** Enseña el marco cinco segundos, para comprobar que el Karoo lo deja ver. */
    fun test() {
        scope.launch {
            demo.value = true
            delay(5_000)
            demo.value = false
        }
    }

    private fun show() {
        if (view != null || !hasPermission(appContext)) return
        val windowManager = appContext.getSystemService(WindowManager::class.java) ?: return
        val stroke = (_strokeDp.value * appContext.resources.displayMetrics.density).toInt().coerceAtLeast(4)
        val frame = View(appContext).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(stroke, RED)
            }
        }
        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        // Desde Android 12, una ventana superpuesta más opaca que esto hace que el sistema descarte los
        // toques que la atraviesan. Con 0,8 el rojo se sigue viendo rojo y la pantalla se maneja igual.
        params.alpha = 0.8f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try {
            windowManager.addView(frame, params)
            view = frame
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo poner el marco", e)
            EventLog.add("El Karoo no ha dejado poner el marco rojo: ${e.message}")
        }
    }

    private fun hide() {
        val frame = view ?: return
        view = null
        try {
            appContext.getSystemService(WindowManager::class.java)?.removeView(frame)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo quitar el marco", e)
        }
    }
}
