package es.bicilab.bicicam.extension

import es.bicilab.bicicam.camera.CameraController
import es.bicilab.bicicam.camera.CameraState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * Two-tap confirmation for tile actions that are annoying if triggered by accident on a bumpy road
 * (stopping a recording, switching the camera off).
 */
object TapConfirmation {
    enum class Kind { STOP_RECORDING, SWITCH_OFF }

    /** [kind] is waiting for its second tap, or has been [confirmed] and is being carried out. */
    data class Pending(val kind: Kind, val confirmed: Boolean)

    private val WINDOW = 4.seconds

    /** How long a confirmed action is shown as in progress at most (the camera state usually changes sooner). */
    private val IN_PROGRESS = 10.seconds

    private val _pending = MutableStateFlow<Pending?>(null)

    /** The action waiting for its confirming second tap or being carried out, if any. */
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // Si la orden confirmada no llega a hacerse (cámara ocupada, sin conexión, rechazada), el campo deja de decir "Parando"
        // Solo lo que ya estaba en marcha: una confirmación que aún espera su segundo toque es de otra orden
        scope.launch { CameraController.actionFailures.collect { if (_pending.value?.confirmed == true) clear() } }
        // Y cuando la orden ya está hecha: si no, «Parando» seguía hasta diez segundos y se tragaba los toques
        // de una grabación empezada entretanto con el botón de la cámara
        scope.launch {
            CameraController.state.collect { state ->
                val current = _pending.value ?: return@collect
                if (isDone(current, state)) clear()
            }
        }
    }
    private var timeout: Job? = null

    /**
     * Returns true on the confirming second tap. The first tap only asks for confirmation
     * (returns false), which expires after [WINDOW].
     */
    @Synchronized
    fun confirm(kind: Kind): Boolean {
        val current = _pending.value
        if (current?.kind == kind && current.confirmed) return false // already being carried out
        val confirmed = current?.kind == kind
        val next = Pending(kind, confirmed)
        _pending.value = next
        timeout?.cancel()
        timeout = scope.launch {
            delay(if (confirmed) IN_PROGRESS else WINDOW)
            _pending.compareAndSet(next, null)
        }
        return confirmed
    }

    /** La orden confirmada ya se ha cumplido: la cámara ha dejado de grabar, o ya no está conectada. */
    fun isDone(pending: Pending, state: CameraState): Boolean {
        if (!pending.confirmed) return false
        val connected = state as? CameraState.Connected
        return when (pending.kind) {
            Kind.STOP_RECORDING -> connected == null || (connected.statusKnown && !connected.recording)
            Kind.SWITCH_OFF -> connected == null
        }
    }

    @Synchronized
    fun clear() {
        timeout?.cancel()
        _pending.value = null
    }
}
