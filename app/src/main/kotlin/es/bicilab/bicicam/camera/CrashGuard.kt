package es.bicilab.bicicam.camera

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Red de seguridad de las corrutinas que viven dentro del Karoo: un fallo inesperado se apunta en el
 * registro en vez de cerrar la extensión (y con ella los campos y el marco) en plena ruta.
 */
val crashGuard = CoroutineExceptionHandler { _, e ->
    Log.w("BiciCAM", "Fallo interno", e)
    EventLog.add(t("Fallo interno: ${e.javaClass.simpleName}", "Internal error: ${e.javaClass.simpleName}"))
}

/** Lanza un trabajo que tiene que durar lo que dure la app: si se cae por un fallo inesperado, vuelve a empezar. */
fun CoroutineScope.launchForever(block: suspend () -> Unit): Job = launch {
    while (true) {
        try {
            block()
            return@launch
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("BiciCAM", "Fallo interno", e)
            EventLog.add(t("Fallo interno: ${e.javaClass.simpleName}. Se reinicia esa parte", "Internal error: ${e.javaClass.simpleName}. Restarting that part"))
            delay(2_000)
        }
    }
}
