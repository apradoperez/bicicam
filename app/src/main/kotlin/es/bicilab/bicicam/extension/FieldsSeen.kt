package es.bicilab.bicicam.extension

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Si algún campo de BiciCAM ha salido ya en una página de datos del Karoo. Mientras no, la pantalla
 * principal de la app recuerda cuál es el paso que falta: añadir los campos.
 */
object FieldsSeen {
    private const val PREFS = "fields"
    private const val KEY_SEEN = "seen_in_ride"

    private val _seen = MutableStateFlow(false)
    val seen: StateFlow<Boolean> = _seen.asStateFlow()

    fun init(context: Context) {
        _seen.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SEEN, false)
    }

    fun note(context: Context) {
        if (_seen.value) return
        _seen.value = true
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SEEN, true).apply()
    }
}
